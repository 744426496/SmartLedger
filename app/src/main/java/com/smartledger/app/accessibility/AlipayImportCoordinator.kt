package com.smartledger.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.smartledger.app.MainActivity
import com.smartledger.app.ocr.RapidOcrEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 支付宝账单自动导入协调器（与 [WechatImportService] 同进程、复用同一无障碍服务实例）。
 *
 * 与微信协调器 [WechatImportCoordinator] 结构一致，仅导航逻辑不同：
 * 支付宝账单页路径更短——「我的 → 账单」两步，无需钱包/服务中转。
 *
 * 职责：
 *  - 打开支付宝 → 视觉导航到「我的 → 账单」→ 滚动截取长图 → 交给 OCR → 删除截图；
 *  - 向 UI 暴露 [state]，供「记一笔」页展示导入进度。
 *
 * 导航方案与微信一致：支付宝同样屏蔽无障碍窗口内容，改用「截屏 + 内置 RapidOCR
 * 视觉定位」：截一张屏 → OCR 找到目标文字坐标 → 点击，不依赖支付宝暴露的节点树。
 */
object AlipayImportCoordinator {

    private const val TAG = "AlipayImport"

    /** 支付宝包名（前台检测用） */
    const val ALIPAY_PACKAGE = "com.eg.android.AlipayGphone"

    /** 支付宝主 Activity（显式组件启动，绕开 Android 11+ 包可见性限制） */
    private const val ALIPAY_LAUNCH_ACTIVITY = "com.eg.android.AlipayGphone.AlipayLogin"

    /** 导入进行中的阶段 */
    enum class Phase {
        IDLE,          // 空闲
        NAVIGATING,    // 正在打开支付宝并跳转账单页
        NEED_MANUAL,   // 自动跳转失败，等待用户手动进入账单页后点「开始截图」
        CAPTURING,     // 正在滚动截取账单长图
        OCR_PENDING,   // 截图完成，正在 OCR 识别
        FAILED,        // 失败
    }

    /** UI 可观测的导入状态 */
    data class ImportState(
        val phase: Phase = Phase.IDLE,
        val message: String = "",
        val frameCount: Int = 0,
        val screenshots: List<String> = emptyList(),
    )

    private val _state = MutableStateFlow(ImportState())
    val state: StateFlow<ImportState> = _state.asStateFlow()

    @Volatile private var running = false
    @Volatile private var ocrDispatched = false
    @Volatile private var service: WechatImportService? = null
    @Volatile private var appContext: Context? = null

    /** 上次导航截屏时刻：两次截屏至少间隔 [SHOT_GAP]，等系统截图提示消失，避免挡住底部导航 */
    @Volatile private var lastNavShotTime = 0L

    /** 视觉导航用的离线 OCR（懒加载，进程内单例） */
    private val rapidOcr: RapidOcrEngine? by lazy { appContext?.let { RapidOcrEngine(it) } }

    /** 文件日志（logcat 在本机不可靠，写私有目录便于拉取排查） */
    private fun log(msg: String) {
        Log.i(TAG, msg)
        val ctx = appContext ?: return
        runCatching {
            val f = java.io.File(ctx.filesDir, "alipay_import.log")
            f.appendText("[${System.currentTimeMillis()}] $msg\n")
        }
    }

    private fun warn(msg: String) {
        Log.w(TAG, msg)
        val ctx = appContext ?: return
        runCatching {
            val f = java.io.File(ctx.filesDir, "alipay_import.log")
            f.appendText("[${System.currentTimeMillis()}] WARN $msg\n")
        }
    }

    private fun err(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        val ctx = appContext ?: return
        runCatching {
            val f = java.io.File(ctx.filesDir, "alipay_import.log")
            f.appendText("[${System.currentTimeMillis()}] ERROR $msg ${t?.message}\n")
        }
    }

    internal fun onServiceConnected(svc: WechatImportService?) {
        service = svc
        appContext = svc?.applicationContext
    }

    // ---------------- 前置检查（UI 线程调用，复用微信服务的无障碍配置） ----------------

    /** 无障碍服务是否已开启（与微信共用同一个服务） */
    fun isServiceEnabled(context: Context): Boolean = WechatImportCoordinator.isServiceEnabled(context)

    /** 无障碍服务是否具备「截屏」能力（用户在服务详情页开启） */
    fun hasScreenshotCapability(context: Context): Boolean =
        WechatImportCoordinator.hasScreenshotCapability(context)

    /** 打开系统无障碍设置（与微信共用同一个服务，用户只需开一次） */
    fun openAccessibilitySettings(context: Context) = WechatImportCoordinator.openAccessibilitySettings(context)

    // ---------------- 入口 ----------------

    /** 完整导入：打开支付宝 → 视觉导航到账单页 → 截长图 */
    fun startImport() {
        val svc = service ?: run {
            _state.value = ImportState(Phase.FAILED, "无障碍服务未连接，请稍候重试")
            return
        }
        if (running) return
        // 若微信导入正在运行，先停掉，避免两个状态机抢同一个服务
        WechatImportCoordinator.cancel()
        running = true
        ocrDispatched = false
        _state.value = ImportState(Phase.NAVIGATING, "正在打开支付宝账单…")
        svc.postToWorker { runImport(svc) }
    }

    /** 仅截屏：用户已手动进入账单页后使用 */
    fun startCaptureOnly() {
        val svc = service ?: run {
            _state.value = ImportState(Phase.FAILED, "无障碍服务未连接，请稍候重试")
            return
        }
        if (running) return
        running = true
        ocrDispatched = false
        _state.value = ImportState(Phase.CAPTURING, "正在截取账单长图…", 0)
        svc.postToWorker {
            try {
                Thread.sleep(1200) // 等界面稳定
                if (running) runCaptureLoop(svc)
            } catch (t: Throwable) {
                err("capture failed", t)
                if (running) fail(svc, "截屏失败：${t.message}")
            }
        }
    }

    /** 取消导入（UI/服务销毁时调用）：清状态并删除临时截图 */
    fun cancel() {
        log("cancel() called, was running=$running")
        running = false
        service?.removeWorkerCallbacks()
        removeCaptureOverlay()
        _state.value = ImportState()
        cleanupDir()
    }

    /**
     * 停止继续截屏（悬浮窗「完成」/点面板）：保留已截取的帧继续识别，
     * 并立即跳回鲸鱼记账 App 显示识别进度。
     */
    fun stopCapture() {
        if (!running) return
        log("user requested stop capture")
        running = false
        removeCaptureOverlay()
        bringBackToAdd()
    }

    /** 跳回 App 并打开「记一笔」页 */
    private fun bringBackToAdd() {
        val ctx = appContext ?: return
        runCatching {
            val intent = Intent(ctx, com.smartledger.app.MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(com.smartledger.app.MainActivity.EXTRA_OPEN_ADD, true)
            }
            ctx.startActivity(intent)
        }
    }

    /** OCR 识别完成（ViewModel 回调）：复位状态并清理临时截图 */
    fun onOcrFinished() {
        running = false
        removeCaptureOverlay()
        _state.value = ImportState()
        cleanupDir()
    }

    // ---------------- 状态机 ----------------

    private fun runImport(svc: WechatImportService) {
        try {
            // 1. 打开支付宝（显式组件）
            if (!launchAlipay(svc)) return needManual(svc, "无法打开支付宝，请手动进入账单页")
            if (!waitForAlipayForeground(svc, 10_000)) return needManual(svc, "支付宝打开超时，请手动进入账单页")
            Thread.sleep(600)

            // 2. 视觉导航：我的 → 账单（截屏 + OCR 定位点击）
            _state.value = ImportState(Phase.NAVIGATING, "正在跳转账单页…")
            if (!navigateToBillPage(svc)) {
                return needManual(svc, "自动跳转未完成，请手动进入：支付宝-我的-账单，然后点下方按钮开始截图导入")
            }

            // 3. 等账单页稳定后开始截长图
            Thread.sleep(800)
            runCaptureLoop(svc)
        } catch (t: Throwable) {
            err("import failed", t)
            if (running) needManual(svc, "导入出错：${t.message}")
        }
    }

    private fun launchAlipay(svc: WechatImportService): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(android.content.ComponentName(ALIPAY_PACKAGE, ALIPAY_LAUNCH_ACTIVITY))
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            svc.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "launchAlipay failed", t)
            false
        }
    }

    /**
     * 等待支付宝进入前台。支付宝屏蔽了窗口内容，rootInActiveWindow 可能拿不到节点，
     * 同时用「窗口状态事件包名」与「当前窗口标题」做检测。
     */
    private fun waitForAlipayForeground(svc: WechatImportService, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline && running) {
            val evPkg = svc.lastEventPackage()
            val rootPkg = svc.rootInActiveWindow?.packageName?.toString()
            val title = currentWindowTitle(svc)
            if (evPkg == ALIPAY_PACKAGE || rootPkg == ALIPAY_PACKAGE || ALIPAY_TITLES.contains(title)) {
                log("alipay foreground: event=$evPkg root=$rootPkg title=$title")
                return true
            }
            Thread.sleep(300)
        }
        warn("alipay foreground timeout: event=${svc.lastEventPackage()} title=${currentWindowTitle(svc)}")
        return false
    }

    /** 当前活动应用窗口的标题（无障碍窗口信息，不读取窗口内容） */
    private fun currentWindowTitle(svc: WechatImportService): String? = runCatching {
        svc.windows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?.title?.toString()
    }.getOrNull()

    // ---------------- 视觉导航 ----------------

    /** 依次视觉定位并点击：我的 → 账单；确认进入账单页 */
    private fun navigateToBillPage(svc: WechatImportService): Boolean {
        log("navigateToBillPage start, running=$running")
        // 第一步「我的」：支付宝底部导航 tab（首页 / 理财 / 视频 / 消息 / 我的）
        val myTab = NavTarget("我的", preferBottom = true)
        for (step in listOf(myTab)) {
            if (!running) return false
            var tapped = false
            var attempt = 0
            while (attempt < 3 && running) {
                log("step「${step.text}」 attempt=$attempt")
                val hit = ocrFind(svc, step.text, step.preferBottom, waitToast = attempt > 0)
                if (hit != null) {
                    val tapY = (hit.cy + 20).coerceAtMost(svc.resources.displayMetrics.heightPixels - 10)
                    log("tap「${step.text}」 at (${hit.cx},$tapY) attempt=$attempt")
                    if (tapAt(svc, hit.cx, tapY)) {
                        tapped = true
                        break
                    }
                    continue
                }
                // 「我的」没找到：可能停在子页面，按返回键回首页再试
                log("「我的」 not found, press back and retry")
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(800)
                attempt++
            }
            if (!tapped) {
                // 也许已经停在「我的」页（有「账单」入口）
                if (isBillPageVisible(svc)) return true
                warn("nav step failed: ${step.text} (window title=${currentWindowTitle(svc)})")
                return false
            }
            Thread.sleep(700) // 等待页面切换动画
        }
        return openBillPage(svc)
    }

    /**
     * 打开账单页：在「我的」页点「账单」→ 重试，直到确认进入账单页。
     * 每轮只截一次屏完成全部判断。
     */
    private fun openBillPage(svc: WechatImportService): Boolean {
        var attempts = 0
        while (running && attempts < MAX_BILL_ATTEMPTS) {
            attempts++
            val probe = probeScreen(svc)
            when (probe.state) {
                ScreenState.BILL -> {
                    log("bill page confirmed (attempt=$attempts)")
                    return true
                }
                ScreenState.OTHER -> Unit
            }
            val hit = probe.billLine
            if (hit == null) {
                warn("账单 not found on screen (attempt=$attempts)")
                Thread.sleep(800)
                continue
            }
            val tapY = (hit.cy + 20).coerceAtMost(svc.resources.displayMetrics.heightPixels - 10)
            log("tap「账单」 at (${hit.cx},$tapY) attempt=$attempts")
            if (tapAt(svc, hit.cx, tapY)) {
                Thread.sleep(1_200) // 等账单页出现
            }
        }
        return false
    }

    /** 一次截屏的完整判断：当前状态 + 「账单」入口位置 */
    private data class ScreenProbe(val state: ScreenState, val billLine: RapidOcrEngine.OcrLine?)

    private fun probeScreen(svc: WechatImportService): ScreenProbe {
        currentWindowTitle(svc)?.let { if (it.contains("账单")) return ScreenProbe(ScreenState.BILL, null) }
        val w = svc.resources.displayMetrics.widthPixels
        val h = svc.resources.displayMetrics.heightPixels
        val lines = ocrLines(svc)
        val texts = lines.map { it.text.trim() }
        // 支付宝账单页专属特征：搜索框「搜索交易记录」、Tab「全部/筛选」、汇总卡片「收支分析/我的消费图鉴/本月已省」
        if (texts.any { it.contains("搜索交易记录") || it.contains("收支分析") || it.contains("我的消费图鉴") || it.contains("本月已省") }) {
            return ScreenProbe(ScreenState.BILL, null)
        }
        val billLine = pickTargetLine(lines, "账单", preferBottom = false, w, h)
        return ScreenProbe(ScreenState.OTHER, billLine)
    }

    /** 当前屏幕状态：账单页 / 其他 */
    private enum class ScreenState { BILL, OTHER }

    /** 当前画面是否已是账单页 */
    private fun isBillPageVisible(svc: WechatImportService): Boolean =
        probeScreen(svc).state == ScreenState.BILL

    /**
     * 截屏 + OCR，返回全部文字行（含坐标）。
     * [waitToast] 为 true 时（仅「我的」这类底部目标）等系统截图提示消失。
     */
    private fun ocrLines(svc: WechatImportService, waitToast: Boolean = false): List<RapidOcrEngine.OcrLine> {
        if (waitToast) {
            val since = SystemClock.uptimeMillis() - lastNavShotTime
            if (since < SHOT_GAP) Thread.sleep(SHOT_GAP - since)
            lastNavShotTime = SystemClock.uptimeMillis()
        }
        val bmp = takeScreenshotBlocking(svc) ?: return emptyList()
        Thread.sleep(300)
        val lines = runCatching { rapidOcr?.recognize(bmp) }.getOrNull().orEmpty()
        bmp.recycle()
        return lines
    }

    /** 截屏 + OCR，定位目标文字（返回文字框中心坐标） */
    private fun ocrFind(
        svc: WechatImportService,
        target: String,
        preferBottom: Boolean,
        waitToast: Boolean = false,
    ): RapidOcrEngine.OcrLine? {
        val lines = ocrLines(svc, waitToast = waitToast)
        if (lines.isEmpty()) return null
        val w = svc.resources.displayMetrics.widthPixels
        val h = svc.resources.displayMetrics.heightPixels
        val hit = pickTargetLine(lines, target, preferBottom, w, h)
        if (hit != null) {
            log("ocr found「$target」: (${hit.cx},${hit.cy}) text=[${hit.text}]")
        } else {
            val dump = lines.joinToString(" | ") { "${it.text.take(20)}@${it.cx},${it.cy}" }
            log("ocr NOT found「$target」 (lines=${lines.size}) [${dump.take(500)}]")
        }
        return hit
    }

    /**
     * 从 OCR 结果中挑出目标文字（复用微信协调器的纯函数实现，保持行为一致）：
     *  - 优先精确匹配，其次包含匹配；
     *  - 忽略状态栏等顶部边缘区域（cy < 8% 屏高）与底部导航条（cy > 96% 屏高）；
     *  - [preferBottom] 为 true 时优先取下半屏（底部 tab 如「我的」），否则优先取上半屏。
     */
    private fun pickTargetLine(
        lines: List<RapidOcrEngine.OcrLine>,
        target: String,
        preferBottom: Boolean,
        screenW: Int,
        screenH: Int,
    ): RapidOcrEngine.OcrLine? = WechatImportCoordinator.pickTargetLine(lines, target, preferBottom, screenW, screenH)

    /** 模拟点击（dispatchGesture） */
    private fun tapAt(svc: WechatImportService, x: Int, y: Int): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
            lineTo((x + 1).toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val latch = CountDownLatch(1)
        var ok = false
        try {
            svc.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) {
                        ok = true
                        latch.countDown()
                    }

                    override fun onCancelled(g: GestureDescription?) = latch.countDown()
                },
                Handler(Looper.getMainLooper()),
            )
            latch.await(1, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            warn("tap failed: ${t.message}")
        }
        return ok
    }

    // ---------------- 截屏（仅用户控制模式） ----------------

    private fun runCaptureLoop(svc: WechatImportService) {
        if (Build.VERSION.SDK_INT < 30) {
            fail(svc, "自动截屏需要 Android 11 及以上系统")
            return
        }
        val ctx = appContext ?: return fail(svc, "应用上下文丢失，请重试")
        val dir = File(ctx.filesDir, DIR_NAME).apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }

        val frames = mutableListOf<String>()
        var count = 0

        if (!canDrawOverlays(ctx) || !showCaptureOverlayReady()) {
            fail(svc, "需要「显示在其他应用上层」权限才能手动截屏，请先开启悬浮窗权限")
            return
        }
        log("overlay shown, waiting user to start")
        runUserControlledCapture(svc, dir, frames) { count = it }

        if (frames.isEmpty()) {
            fail(svc, "截屏失败（请确认无障碍服务已开启「截屏」权限）")
            return
        }

        _state.value = ImportState(Phase.OCR_PENDING, "截图完成（$count 屏），正在识别…", count, frames)
        dispatchOcr(frames)
    }

    /**
     * 用户控制模式：
     * 1) 先显示「准备就绪」面板，用户可先滑动账单页到想开始的位置；
     * 2) 点「开始」→ 从当前画面开始跟帧截屏（内容一变就保存）；
     * 3) 点「完成」→ 截止，识别。
     */
    private fun runUserControlledCapture(
        svc: WechatImportService,
        dir: File,
        frames: MutableList<String>,
        onCount: (Int) -> Unit,
    ) {
        val startLatch = CountDownLatch(1)
        overlayStartAction = { startLatch.countDown() }
        val started = startLatch.await(USER_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        log("start await=$started running=$running")
        if (!started) {
            log("user did not start, abort")
            overlayStartAction = null
            removeCaptureOverlay()
            cancel()
            return
        }
        overlayStartAction = null
        showCaptureOverlayCapturing(0)
        log("user started capture")

        val savedFps = mutableListOf<IntArray>()
        var seq = 0
        var pendingRevertTo = -1
        val deadline = SystemClock.uptimeMillis() + USER_CONTROL_TIMEOUT_MS
        log("capture loop begin, running=$running")
        while (running && frames.size < MAX_FRAMES && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(500)
            if (!running) break
            val bmp = takeScreenshotBlocking(svc) ?: break
            val cropped = cropFrame(bmp, blankOverlay = true)
            bmp.recycle()
            val fp = fingerprint(cropped)
            if (fp != null) {
                val act = classifyFrame(fp, savedFps)
                when (act.first) {
                    FrameAction.STILL -> pendingRevertTo = -1
                    FrameAction.ADVANCE -> {
                        pendingRevertTo = -1
                        val file = File(dir, "frame_%03d.jpg".format(seq))
                        seq++
                        if (!saveFrame(cropped, file)) {
                            cropped.recycle()
                            break
                        }
                        frames.add(file.absolutePath)
                        savedFps.add(fp)
                        onCount(frames.size)
                        showCaptureOverlayCapturing(frames.size)
                        _state.value = ImportState(Phase.CAPTURING, "正在截取账单长图…", frames.size)
                    }
                    FrameAction.REVERT -> {
                        val k = act.second
                        if (pendingRevertTo == k) {
                            truncateFrames(frames, savedFps, k)
                            pendingRevertTo = -1
                            log("revert confirmed: back to frame $k, now ${frames.size} frames")
                            onCount(frames.size)
                            showCaptureOverlayCapturing(frames.size)
                            _state.value = ImportState(Phase.CAPTURING, "正在截取账单长图…", frames.size)
                        } else {
                            pendingRevertTo = k
                            log("revert candidate: frame $k (waiting confirm)")
                        }
                    }
                }
            }
            cropped.recycle()
        }
        removeCaptureOverlay()
        log("user-controlled capture done: ${frames.size} frames")
    }

    private fun takeScreenshotBlocking(svc: WechatImportService): Bitmap? {
        val latch = CountDownLatch(1)
        var result: AccessibilityService.ScreenshotResult? = null
        var failCode = 0
        var error: Throwable? = null
        val mainExecutor = ContextCompat.getMainExecutor(svc)
        try {
            svc.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                        result = screenshot
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        failCode = errorCode
                        latch.countDown()
                    }
                },
            )
        } catch (t: Throwable) {
            error = t
            latch.countDown()
        }
        if (!latch.await(8, TimeUnit.SECONDS)) {
            warn("takeScreenshot timeout")
            return null
        }
        if (error != null) {
            warn("takeScreenshot error: ${error.message}")
            return null
        }
        if (result == null) {
            warn("takeScreenshot failed code=$failCode")
            return null
        }
        val hb = result!!.hardwareBuffer
        val cs = result!!.colorSpace
        val wrapped = Bitmap.wrapHardwareBuffer(hb, cs) ?: run { hb.close(); return null }
        val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
        wrapped.recycle()
        hb.close()
        return copy
    }

    /**
     * 裁掉每屏的顶部状态栏/支付宝头部与最底部手势条区域。
     * 底部只裁 6%：实测裁 14% 会在 1080x2376 屏幕丢掉约 333px，
     * 把可见的「某平台退货-寄件费」等完整账单条目排除在 OCR 输入外；6% 仍足以避开手势条。
     * [blankOverlay] 为 true 时把悬浮窗所在区域涂白，避免面板文字被 OCR 计入账单。
     */
    private fun cropFrame(bmp: Bitmap, blankOverlay: Boolean = false): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val top = (h * 0.055).toInt().coerceAtLeast(0)
        val bottom = (h * 0.06).toInt().coerceAtLeast(0)
        if (top == 0 && bottom == 0 && !blankOverlay) return bmp
        val th = (h - top - bottom).coerceAtLeast(1)
        val out = Bitmap.createBitmap(bmp, 0, top, w, th)
        if (blankOverlay) {
            val panelTop = statusBarHeight() + dp(OVERLAY_MARGIN_DP) - top
            val panelBottom = panelTop + dp(OVERLAY_H_DP)
            val y0 = (panelTop - dp(OVERLAY_MARGIN_DP)).coerceAtLeast(0)
            val y1 = (panelBottom + dp(OVERLAY_MARGIN_DP)).coerceAtMost(th)
            val paint = android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt() }
            android.graphics.Canvas(out).drawRect(0f, y0.toFloat(), w.toFloat(), y1.toFloat(), paint)
        }
        return out
    }

    private fun saveFrame(bmp: Bitmap, file: File): Boolean = runCatching {
        FileOutputStream(file).use { fos -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos) }
        true
    }.getOrDefault(false)

    /** 降采样灰度指纹（FP_W x FP_H，每点 0~255 灰度） */
    private fun fingerprint(bmp: Bitmap): IntArray? = runCatching {
        val scaled = Bitmap.createScaledBitmap(bmp, FP_W, FP_H, true)
        val px = IntArray(FP_W * FP_H)
        scaled.getPixels(px, 0, FP_W, 0, 0, FP_W, FP_H)
        scaled.recycle()
        IntArray(FP_W * FP_H) { i ->
            val c = px[i]
            (0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)).toInt()
        }
    }.getOrNull()

    private fun grayDiff(a: IntArray, b: IntArray): Int {
        var d = 0
        for (i in a.indices) d += kotlin.math.abs(a[i] - b[i])
        return d
    }

    enum class FrameAction { STILL, ADVANCE, REVERT }

    /** 分类当前帧相对已存帧序列的动作（与微信协调器一致） */
    private fun classifyFrame(fp: IntArray, savedFps: List<IntArray>): Pair<FrameAction, Int> {
        if (savedFps.isEmpty()) return FrameAction.ADVANCE to -1
        val last = savedFps.last()
        val lastDiff = grayDiff(fp, last)
        if (lastDiff < STILL_TOLERANCE) return FrameAction.STILL to -1
        var bestIdx = -1
        var bestDiff = Int.MAX_VALUE
        for (i in savedFps.indices) {
            val d = grayDiff(fp, savedFps[i])
            if (d < bestDiff) {
                bestDiff = d
                bestIdx = i
            }
        }
        if (bestIdx in 0 until savedFps.lastIndex && bestDiff < REVERT_TOLERANCE) {
            return FrameAction.REVERT to bestIdx
        }
        return FrameAction.ADVANCE to -1
    }

    private fun truncateFrames(frames: MutableList<String>, savedFps: MutableList<IntArray>, keepThrough: Int) {
        for (i in frames.size - 1 downTo keepThrough + 1) {
            runCatching { File(frames[i]).delete() }
            frames.removeAt(i)
            savedFps.removeAt(i)
        }
    }

    // ---------------- 收尾 ----------------

    private fun needManual(svc: WechatImportService, message: String) {
        running = false
        warn("needManual: $message")
        _state.value = ImportState(Phase.NEED_MANUAL, message)
    }

    private fun fail(svc: WechatImportService, message: String) {
        running = false
        err("failed: $message")
        _state.value = ImportState(Phase.FAILED, message)
    }

    /** 截图完成 → 通知 OCR（幂等：只派发一次） */
    private fun dispatchOcr(paths: List<String>) {
        if (ocrDispatched) return
        ocrDispatched = true
        Handler(Looper.getMainLooper()).post {
            val vm = MainActivity.currentViewModel
            if (vm != null) {
                vm.processCapturedImages(paths, alipay = true)
            } else {
                warn("currentViewModel is null, OCR skipped")
                running = false
                _state.value = ImportState()
            }
        }
    }

    private fun cleanupDir() {
        val ctx = appContext ?: return
        runCatching { File(ctx.filesDir, DIR_NAME).listFiles()?.forEach { it.delete() } }
    }

    // ---------------- 截屏悬浮窗 ----------------

    fun canDrawOverlays(context: Context): Boolean =
        android.provider.Settings.canDrawOverlays(context)

    fun openOverlaySettings(context: Context) {
        runCatching {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private var overlayView: android.view.View? = null
    private var overlayTitle: android.widget.TextView? = null
    private var overlayPrimaryBtn: android.widget.Button? = null

    @Volatile
    private var overlayStartAction: (() -> Unit)? = null

    private fun showCaptureOverlayReady(): Boolean = showOverlayPanel(
        title = "准备就绪 · 滑动到想截的位置",
        primaryText = "开始",
        primaryAction = { overlayStartAction?.invoke() },
        secondaryText = "取消",
        secondaryAction = { cancel() },
        titleClick = null,
    )

    private fun showCaptureOverlayCapturing(count: Int) {
        showOverlayPanel(
            title = "已截 $count 屏 · 点这里停止",
            primaryText = "完成",
            primaryAction = { stopCapture() },
            secondaryText = "取消",
            secondaryAction = { cancel() },
            titleClick = { stopCapture() },
        )
    }

    private fun showOverlayPanel(
        title: String,
        primaryText: String,
        primaryAction: () -> Unit,
        secondaryText: String,
        secondaryAction: () -> Unit,
        titleClick: (() -> Unit)?,
    ): Boolean {
        val ctx = appContext ?: return false
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager ?: return false
        val latch = CountDownLatch(1)
        var ok = false
        Handler(Looper.getMainLooper()).post {
            try {
                val needRebuild = overlayView != null && overlayPrimaryBtn?.text != primaryText
                if (overlayView != null && !needRebuild) {
                    overlayTitle?.text = title
                    ok = true
                } else {
                    if (overlayView != null) runCatching { wm.removeViewImmediate(overlayView) }
                    overlayView = null
                    overlayTitle = null
                    overlayPrimaryBtn = null
                    val pad = dp(8)
                    val titleView = android.widget.TextView(ctx).apply {
                        text = title
                        setTextColor(0xFFFFFFFF.toInt())
                        textSize = if (titleClick == null) 13f else 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setOnClickListener { titleClick?.invoke() }
                    }
                    val btnPrimary = android.widget.Button(ctx).apply {
                        text = primaryText
                        textSize = 13f
                        setOnClickListener { primaryAction() }
                    }
                    val btnSecondary = android.widget.Button(ctx).apply {
                        text = secondaryText
                        textSize = 13f
                        setOnClickListener { secondaryAction() }
                    }
                    val panel = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(pad, pad, pad, pad)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(0xE6303030.toInt())
                            cornerRadius = dp(12).toFloat()
                        }
                        addView(titleView, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(btnSecondary, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
                        addView(btnPrimary, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
                    }
                    val lp = android.view.WindowManager.LayoutParams(
                        dp(OVERLAY_W_DP),
                        dp(OVERLAY_H_DP),
                        if (Build.VERSION.SDK_INT >= 26) android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else @Suppress("DEPRECATION") android.view.WindowManager.LayoutParams.TYPE_PHONE,
                        android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        android.graphics.PixelFormat.TRANSLUCENT,
                    ).apply {
                        gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                        y = statusBarHeight() + dp(OVERLAY_MARGIN_DP)
                    }
                    wm.addView(panel, lp)
                    overlayView = panel
                    overlayTitle = titleView
                    overlayPrimaryBtn = btnPrimary
                    ok = true
                }
            } catch (t: Throwable) {
                warn("overlay addView failed: ${t.message}")
            }
            latch.countDown()
        }
        runCatching { latch.await(2, TimeUnit.SECONDS) }
        return ok
    }

    private fun statusBarHeight(): Int {
        val res = appContext?.resources ?: return 0
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) runCatching { res.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    private fun removeCaptureOverlay() {
        val ctx = appContext ?: return
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager ?: return
        Handler(Looper.getMainLooper()).post {
            val v = overlayView ?: return@post
            overlayView = null
            overlayTitle = null
            overlayPrimaryBtn = null
            runCatching { wm.removeViewImmediate(v) }
        }
    }

    private fun dp(v: Int): Int =
        (v * (appContext?.resources?.displayMetrics?.density ?: 1f)).toInt()

    private data class NavTarget(val text: String, val preferBottom: Boolean)

    private const val DIR_NAME = "alipay_import"
    private const val MAX_FRAMES = 48

    private const val FP_W = 16
    private const val FP_H = 36
    private const val STILL_TOLERANCE = 1200
    private const val REVERT_TOLERANCE = 700
    private const val OVERLAY_W_DP = 320
    private const val OVERLAY_H_DP = 56
    private const val OVERLAY_MARGIN_DP = 16
    private const val USER_CONTROL_TIMEOUT_MS = 10 * 60_000L
    private const val USER_START_TIMEOUT_MS = 5 * 60_000L
    private const val MAX_BILL_ATTEMPTS = 6
    private const val SHOT_GAP = 4000L

    /** 支付宝各页面常见的窗口标题（用于前台检测） */
    private val ALIPAY_TITLES = setOf("支付宝", "我的", "账单", "首页", "理财", "消息", "视频", "朋友", "总资产")
}
