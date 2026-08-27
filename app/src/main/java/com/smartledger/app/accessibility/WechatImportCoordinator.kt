package com.smartledger.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityManager
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
 * 微信账单自动导入协调器（与 [WechatImportService] 同进程）。
 *
 * 职责：
 *  - 前置检查：微信是否安装、无障碍服务是否开启、是否具备「截屏」能力；
 *  - 状态机（在服务的工作线程上执行）：
 *      打开微信 → 视觉导航到「我-服务-钱包-账单」→ 滚动截取长图 → 交给 OCR → 删除截图；
 *  - 向 UI 暴露 [state]，供「记一笔」页展示导入进度。
 *
 * 导航方案说明：微信 8.0.58+ 屏蔽了无障碍窗口内容（防自动化，节点读取为空），
 * 因此改用「截屏 + 内置 RapidOCR 视觉定位」：截一张屏 → OCR 找到目标文字坐标 → 点击，
 * 不依赖微信暴露的节点树。需要用户为无障碍服务开启一次「截屏」权限。
 */
object WechatImportCoordinator {

    private const val TAG = "WechatImport"

    /** 导入进行中的阶段 */
    enum class Phase {
        IDLE,          // 空闲
        NAVIGATING,    // 正在打开微信并跳转账单页
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
            val f = java.io.File(ctx.filesDir, "import.log")
            f.appendText("[${System.currentTimeMillis()}] $msg\n")
        }
    }

    private fun warn(msg: String) {
        Log.w(TAG, msg)
        val ctx = appContext ?: return
        runCatching {
            val f = java.io.File(ctx.filesDir, "import.log")
            f.appendText("[${System.currentTimeMillis()}] WARN $msg\n")
        }
    }

    private fun err(msg: String, t: Throwable? = null) {
        Log.e(TAG, msg, t)
        val ctx = appContext ?: return
        runCatching {
            val f = java.io.File(ctx.filesDir, "import.log")
            f.appendText("[${System.currentTimeMillis()}] ERROR $msg ${t?.message}\n")
        }
    }

    internal fun onServiceConnected(svc: WechatImportService?) {
        service = svc
        appContext = svc?.applicationContext
    }

    // ---------------- 前置检查（UI 线程调用） ----------------

    private fun ourServiceInfo(context: Context): AccessibilityServiceInfo? {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return null
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .firstOrNull { info ->
                info.resolveInfo.serviceInfo.packageName == context.packageName &&
                    info.resolveInfo.serviceInfo.name == WechatImportService.SERVICE_CLASS
            }
    }

    /** 无障碍服务是否已开启 */
    fun isServiceEnabled(context: Context): Boolean = ourServiceInfo(context) != null

    /** 无障碍服务是否具备「截屏」能力（用户在服务详情页开启） */
    fun hasScreenshotCapability(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val info = ourServiceInfo(context) ?: return false
        return info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
    }

    /** 打开系统无障碍设置（用户找到「鲸鱼记账 → 微信账单自动导入」开启服务与截屏权限） */
    fun openAccessibilitySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---------------- 入口 ----------------

    /** 完整导入：打开微信 → 视觉导航到账单页 → 截长图 */
    fun startImport() {
        val svc = service ?: run {
            _state.value = ImportState(Phase.FAILED, "无障碍服务未连接，请稍候重试")
            return
        }
        if (running) return
        running = true
        ocrDispatched = false
        _state.value = ImportState(Phase.NAVIGATING, "正在打开微信账单…")
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
        // 立即回 App：OCR 在后台继续跑，App 里能看到「正在识别」进度与结果
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
            // 1. 打开微信（显式组件，绕开 Android 11+ 包可见性限制）
            if (!launchWeChat(svc)) return needManual(svc, "无法打开微信，请手动进入账单页")
            if (!waitForWechatForeground(svc, 10_000)) return needManual(svc, "微信打开超时，请手动进入账单页")
            Thread.sleep(600)

            // 2. 视觉导航：我 → 服务 → 钱包 → 账单（截屏 + OCR 定位点击）
            _state.value = ImportState(Phase.NAVIGATING, "正在跳转账单页…")
            if (!navigateToBillPage(svc)) {
                return needManual(svc, "自动跳转未完成，请手动进入：微信-我-服务-钱包-账单，然后点下方按钮开始截图导入")
            }

            // 3. 等账单页稳定后开始截长图
            Thread.sleep(800)
            runCaptureLoop(svc)
        } catch (t: Throwable) {
            err("import failed", t)
            if (running) needManual(svc, "导入出错：${t.message}")
        }
    }

    private fun launchWeChat(svc: WechatImportService): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(
                    android.content.ComponentName(
                        WechatImportService.WECHAT_PACKAGE,
                        "com.tencent.mm.ui.LauncherUI",
                    ),
                )
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
            svc.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "launchWeChat failed", t)
            false
        }
    }

    /**
     * 等待微信进入前台。微信 8.0.58+ 屏蔽了窗口内容，rootInActiveWindow 可能拿不到节点，
     * 所以同时用「窗口状态事件包名」与「当前窗口标题」做检测（两者都不依赖内容读取）。
     */
    private fun waitForWechatForeground(svc: WechatImportService, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline && running) {
            val evPkg = svc.lastEventPackage()
            val rootPkg = svc.rootInActiveWindow?.packageName?.toString()
            val title = currentWindowTitle(svc)
            if (evPkg == WechatImportService.WECHAT_PACKAGE ||
                rootPkg == WechatImportService.WECHAT_PACKAGE ||
                WECHAT_TITLES.contains(title)
            ) {
                Log.i(TAG, "wechat foreground: event=$evPkg root=$rootPkg title=$title")
                log("wechat foreground: event=$evPkg root=$rootPkg title=$title")
                return true
            }
            Thread.sleep(300)
        }
        warn("wechat foreground timeout: event=${svc.lastEventPackage()} title=${currentWindowTitle(svc)}")
        return false
    }

    /** 当前活动应用窗口的标题（无障碍窗口信息，不读取窗口内容） */
    private fun currentWindowTitle(svc: WechatImportService): String? = runCatching {
        svc.windows
            .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?.title?.toString()
    }.getOrNull()

    // ---------------- 视觉导航 ----------------

    /** 依次视觉定位并点击：我 → 服务 → 钱包 → 账单（账单步骤含「支付环境有风险」警告处理） */
    private fun navigateToBillPage(svc: WechatImportService): Boolean {
        log("navigateToBillPage start, running=$running")
        // 直接开始找「我」：第一次截图没有前置截图提示，无需等待；
        // 若已在账单页（用户之前手动进入），「我」步骤的兜底检查会直接放行
        val steps = listOf(
            NavTarget("我", preferBottom = true),
            NavTarget("服务", preferBottom = false),
            NavTarget("钱包", preferBottom = false),
        )
        for (step in steps) {
            if (!running) return false
            var tapped = false
            var attempt = 0
            while (attempt < 3 && running) {
                log("step「${step.text}」 attempt=$attempt")
                // 重试时上一张截图的提示可能还在屏幕上：底部目标等待其消失
                val hit = ocrFind(svc, step.text, step.preferBottom, waitToast = attempt > 0)
                if (hit != null) {
                    // 文字中心略偏上（行内文字垂直居中），点击点下移 20px 更易命中整行
                    val tapY = (hit.cy + 20).coerceAtMost(svc.resources.displayMetrics.heightPixels - 10)
                    log("tap「${step.text}」 at (${hit.cx},$tapY) attempt=$attempt")
                    if (tapAt(svc, hit.cx, tapY)) {
                        tapped = true
                        break
                    }
                    continue
                }
                // 目标没找到：可能被「支付环境有风险」类警告横幅挡住 → 上滑收掉再试
                if (dismissWarningIfPresent(svc)) {
                    tapped = true
                    break
                }
                // 微信可能停在子页面（无底部导航）：按返回键回到主页再试（仅第一步「我」）
                if (step.text == "我") {
                    log("「我」 not found, press back and retry")
                    svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    Thread.sleep(800)
                }
                attempt++
            }
            if (!tapped) {
                // 也许已经停在更深页面（如钱包页里有「账单」字样被前一步点到）
                if (step.text != "账单" && isBillPageVisible(svc)) return true
                warn("nav step failed: ${step.text} (window title=${currentWindowTitle(svc)})")
                return false
            }
            Thread.sleep(700) // 等待页面切换动画
        }
        return openBillPage(svc)
    }

    /**
     * 打开账单页：点「账单」→ 若弹出「支付环境有风险」警告（类似灵动岛通知）则上滑收掉 →
     * 重试，直到确认进入账单页。每轮只截一次屏完成全部判断。
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
                ScreenState.WARNING -> {
                    log("warning banner present, swiping up (attempt=$attempts)")
                    swipeFromTo(svc, 0.5f, 0.20f, 0.5f, 0.05f, 350)
                    Thread.sleep(1_200)
                    continue
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
                Thread.sleep(1_200) // 等账单页/警告页出现
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
        if (texts.any { it.contains("全部账单") || it.contains("收支统计") || it.contains("查找交易") }) {
            return ScreenProbe(ScreenState.BILL, null)
        }
        val topBound = (h * 0.35).toInt()
        if (lines.any { l -> l.cy <= topBound && WARNING_KEYWORDS.any { l.text.trim().contains(it) } }) {
            return ScreenProbe(ScreenState.WARNING, null)
        }
        val billLine = pickTargetLine(lines, "账单", preferBottom = false, w, h)
        return ScreenProbe(ScreenState.OTHER, billLine)
    }

    /** 当前屏幕状态：账单页 / 警告横幅 / 其他 */
    private enum class ScreenState { BILL, WARNING, OTHER }

    /** 检测「支付环境有风险」类警告横幅并上滑收掉；返回是否检测到并处理了 */
    private fun dismissWarningIfPresent(svc: WechatImportService): Boolean {
        val lines = ocrLines(svc)
        val topBound = (svc.resources.displayMetrics.heightPixels * 0.35).toInt()
        val warnLine = lines.firstOrNull { l ->
            l.cy <= topBound && WARNING_KEYWORDS.any { l.text.trim().contains(it) }
        } ?: return false
        log("warning detected: [${warnLine.text}] at (${warnLine.cx},${warnLine.cy})")
        swipeFromTo(svc, 0.5f, 0.20f, 0.5f, 0.05f, 350)
        Thread.sleep(1_200)
        return true
    }

    /**
     * 截屏 + OCR，返回全部文字行（含坐标）。
     * [waitToast] 为 true 时（仅「我」这类底部目标）等系统截图提示消失——
     * 截图 toast 会盖住屏幕底部，可能挡住底部标签；其余目标都在上部，不受影响，无需等待。
     */
    private fun ocrLines(svc: WechatImportService, waitToast: Boolean = false): List<RapidOcrEngine.OcrLine> {
        if (waitToast) {
            val since = SystemClock.uptimeMillis() - lastNavShotTime
            if (since < SHOT_GAP) Thread.sleep(SHOT_GAP - since)
            lastNavShotTime = SystemClock.uptimeMillis()
        }
        val bmp = takeScreenshotBlocking(svc) ?: return emptyList()
        // 等页面稳定（若有切换动画）
        Thread.sleep(300)
        val lines = runCatching { rapidOcr?.recognize(bmp) }.getOrNull().orEmpty()
        bmp.recycle()
        return lines
    }

    /** 截屏 + OCR，定位目标文字（返回文字框中心坐标）。[waitToast] 仅底部目标重试时需要 */
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
     * 从 OCR 结果中挑出目标文字（纯函数，便于单测）：
     *  - 优先精确匹配，其次包含匹配；
     *  - 忽略状态栏等顶部边缘区域（cy < 8% 屏高）与底部导航条（cy > 96% 屏高），避免点空；
     *  - [preferBottom] 为 true 时优先取下半屏（底部 tab 如「我」），否则优先取上半屏（列表行）；
     *  - 同类候选取最靠上的一个。
     */
    fun pickTargetLine(
        lines: List<RapidOcrEngine.OcrLine>,
        target: String,
        preferBottom: Boolean,
        screenW: Int,
        screenH: Int,
    ): RapidOcrEngine.OcrLine? {
        val minY = (screenH * 0.055).toInt() // 仅排除纯状态栏区域（时钟/电池等噪声）
        // 底部 tab（如「我」）就在屏幕最底边，不能设 maxY 上限（displayMetrics 高度可能与截屏略有出入）；
        // 普通列表行排除底部导航条区域即可。
        val maxY = if (preferBottom) Int.MAX_VALUE else (screenH * 0.96).toInt()
        val valid = lines.filter { it.cy in minY..maxY }
        if (valid.isEmpty()) return null
        val exact = valid.filter { it.text.trim() == target }
        val pool = if (exact.isNotEmpty()) exact else valid.filter { it.text.trim().contains(target) }
        if (pool.isEmpty()) return null
        val boundY = (screenH * if (preferBottom) 0.55 else 0.7).toInt()
        val preferred = pool.filter { if (preferBottom) it.cy >= boundY else it.cy <= boundY }
        val use = if (preferred.isNotEmpty()) preferred else pool
        return use.minByOrNull { it.cy }
    }

    /**
     * 当前画面是否已是账单页。
     * 注意不能用「账单」二字判断——钱包页右上角也有「账单」链接，会误判。
     * 用账单页专属特征：全部账单（筛选栏）、收支统计、查找交易。
     */
    private fun isBillPageVisible(svc: WechatImportService): Boolean =
        probeScreen(svc).state == ScreenState.BILL

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

        // 需要悬浮窗权限：面板上手动「开始」→ 滑动跟帧 →「完成」截止
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
        // 等待用户点「开始」（面板已显示）；用户点「取消」或超时则放弃
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

        // 已存帧的灰度指纹（与 frames 平行）：用于「往回滑 → 匹配到之前位置 → 缩短」的回退检测
        val savedFps = mutableListOf<IntArray>()
        var seq = 0 // 帧文件名递增序号（回退后不重复）
        // 回退确认：正常下滑时账单页内容可能与某个历史帧偶发相似（白底灰字主导），
        // 单次匹配就删帧会误删。必须连续两次指向同一历史帧才真正回退。
        var pendingRevertTo = -1
        val deadline = SystemClock.uptimeMillis() + USER_CONTROL_TIMEOUT_MS
        log("capture loop begin, running=$running")
        while (running && frames.size < MAX_FRAMES && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(500) // 轮询间隔
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
                            // 连续两次匹配同一历史帧 → 确认回退
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
        // 转成软件位图并释放硬件缓冲，避免长期占用 GPU 内存
        val copy = wrapped.copy(Bitmap.Config.ARGB_8888, false)
        wrapped.recycle()
        hb.close()
        return copy
    }

    /**
     * 裁掉每屏的顶部状态栏/微信头部与最底部手势条区域。
     * 底部只裁 6%：14% 在 1080x2376 屏幕会丢掉约 333px，足以排除仍完整可见的账单条目；
     * 6% 保留绝大多数账单区域，同时避开系统手势条。截屏提示文字仍会由顶部悬浮窗涂白与解析器过滤处理。
     * [blankOverlay] 为 true 时（用户控制模式）把悬浮窗所在区域涂白，
     * 避免面板文字（「已截 N 屏」等）被 OCR 计入账单。
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
            // 悬浮窗固定尺寸：屏幕顶部居中（与 showOverlayPanel 保持一致），在裁剪后的坐标里涂白，
            // 避免面板文字（「已截 N 屏 · 点这里停止」等）被 OCR 计入账单。
            // 涂白整条顶部横带（全宽 + 上下各留一档余量）：面板位置/尺寸在个别机型上可能轻微
            // 偏移，只涂面板矩形容易漏边，把整条带涂白更稳，且不影响下方账单条目（条目在更下方）。
            val panelTop = statusBarHeight() + dp(OVERLAY_MARGIN_DP) - top
            val panelBottom = panelTop + dp(OVERLAY_H_DP)
            val y0 = (panelTop - dp(OVERLAY_MARGIN_DP)).coerceAtLeast(0)
            val y1 = (panelBottom + dp(OVERLAY_MARGIN_DP)).coerceAtMost(th)
            val paint = android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt() }
            android.graphics.Canvas(out).drawRect(0f, y0.toFloat(), w.toFloat(), y1.toFloat(), paint)
        }
        return out
    }

    /** 保存截帧为 JPEG（PNG 压缩大图太慢，是截屏循环的主要瓶颈） */
    private fun saveFrame(bmp: Bitmap, file: File): Boolean = runCatching {
        FileOutputStream(file).use { fos -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, fos) }
        true
    }.getOrDefault(false)

    /** 降采样灰度指纹（FP_W x FP_H，每点 0~255 灰度）：内容完全静止时前后两屏一致 */
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

    /** 两个灰度指纹的累计绝对差（越小越相似） */
    internal fun grayDiff(a: IntArray, b: IntArray): Int {
        var d = 0
        for (i in a.indices) d += kotlin.math.abs(a[i] - b[i])
        return d
    }

    /**
     * 估计 b 相对 a 的垂直位移（采样行数）：b[r] ≈ a[r + dy]。
     * dy > 0 → 内容整体上移（往下滚、看更早的账单）；dy < 0 → 内容下移（往回滚、回退）。
     * 只在慢速滚动（两帧有足够重叠）时可靠，调用方需先排除「大变化」。
     */
    internal fun verticalOffset(a: IntArray, b: IntArray): Int {
        var bestDy = 0
        var bestDiff = Int.MAX_VALUE
        for (dy in -VERT_SCAN..VERT_SCAN) {
            var diff = 0
            var cnt = 0
            for (r in 0 until FP_H) {
                val ar = r + dy
                if (ar < 0 || ar >= FP_H) continue
                for (c in 0 until FP_W) {
                    diff += kotlin.math.abs(b[r * FP_W + c] - a[ar * FP_W + c])
                    cnt++
                }
            }
            if (cnt > 0 && diff < bestDiff) {
                bestDiff = diff
                bestDy = dy
            }
        }
        return bestDy
    }

    /** 帧分类：静止 / 前进追加 / 回退删帧 */
    enum class FrameAction { STILL, ADVANCE, REVERT }

    /**
     * 分类当前帧相对已存帧序列的动作（复刻系统长截图的「往回滑就缩短」）：
     *  - STILL：与最后一帧几乎相同 → 跳过；
     *  - REVERT：与某个更早的历史帧几乎相同（画面回到之前截过的位置）→ 回退到该帧（删掉其后的帧）；
     *  - ADVANCE：其余情况 → 追加新帧。
     *
     * 注意：刻意不做「方向回退」（内容下移就删帧）——微信账单页白色背景占主导，
     * 垂直位移估计不可靠，正常下滑也会被误判成往回滚而删帧（实测 60 笔范围被误删）。
     * 回退只认「画面几乎完全回到某历史帧」这一种可靠信号，且由主循环做两次确认。
     */
    internal fun classifyFrame(fp: IntArray, savedFps: List<IntArray>): Pair<FrameAction, Int> {
        if (savedFps.isEmpty()) return FrameAction.ADVANCE to -1
        val last = savedFps.last()
        val lastDiff = grayDiff(fp, last)

        // 1. 静止
        if (lastDiff < STILL_TOLERANCE) return FrameAction.STILL to -1

        // 2. 精确回退：与某个更早的历史帧几乎相同（严格阈值，只认画面回到原位置）
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

        // 其余一律前进追加
        return FrameAction.ADVANCE to -1
    }

    /** 回退：删除 frames/savedFps 中下标 keepThrough 之后的帧（保留 0..keepThrough） */
    private fun truncateFrames(frames: MutableList<String>, savedFps: MutableList<IntArray>, keepThrough: Int) {
        for (i in frames.size - 1 downTo keepThrough + 1) {
            runCatching { File(frames[i]).delete() }
            frames.removeAt(i)
            savedFps.removeAt(i)
        }
    }

    /** 按屏幕比例滑动（x1f,y1f → x2f,y2f，0~1 为屏宽/屏高比例），用于上滑收起警告横幅 */
    private fun swipeFromTo(svc: WechatImportService, x1f: Float, y1f: Float, x2f: Float, y2f: Float, durationMs: Long) {
        val dm = svc.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val path = Path().apply {
            moveTo(w * x1f, h * y1f)
            lineTo(w * x2f, h * y2f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val latch = CountDownLatch(1)
        try {
            svc.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) = latch.countDown()
                    override fun onCancelled(g: GestureDescription?) = latch.countDown()
                },
                Handler(Looper.getMainLooper()), // 回调放主线程，避免阻塞工作线程
            )
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            // 手势失败不致命：下一轮仍会截屏比较
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
                vm.processCapturedImages(paths)
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

    // ---------------- 截屏悬浮窗（类似系统长截图的选择交互） ----------------

    /** 是否已授权「显示在其他应用上层」（悬浮窗需要） */
    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    /** 打开悬浮窗权限设置页 */
    fun openOverlaySettings(context: Context) {
        runCatching {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    private var overlayView: android.view.View? = null
    private var overlayTitle: android.widget.TextView? = null
    private var overlayPrimaryBtn: android.widget.Button? = null

    /** 用户在「开始」面板上点击开始截屏的回调（由 runUserControlledCapture 设置） */
    @Volatile
    private var overlayStartAction: (() -> Unit)? = null

    /**
     * 显示「准备就绪」面板：[开始] [取消]——用户可先滑动账单页到想开始的位置，再点开始。
     * 返回是否显示成功（无悬浮窗权限时 addView 失败 → 引导用户开权限）。
     */
    private fun showCaptureOverlayReady(): Boolean = showOverlayPanel(
        title = "准备就绪 · 滑动到想截的位置",
        primaryText = "开始",
        primaryAction = { overlayStartAction?.invoke() },
        secondaryText = "取消",
        secondaryAction = { cancel() },
        titleClick = null,
    )

    /** 显示「截屏中」面板：[完成] [取消]；点面板标题区也可停止（按钮可能被系统截图提示遮挡） */
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

    /** 构建并显示半透明悬浮面板（固定尺寸、屏幕顶部居中、不抢焦点）；返回是否显示成功 */
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
                // 主按钮文本变化（开始→完成）时必须重建面板，否则按钮不会切换
                val needRebuild = overlayView != null && overlayPrimaryBtn?.text != primaryText
                if (overlayView != null && !needRebuild) {
                    // 面板已存在且按钮一致：只更新标题（截屏中的计数）
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
                    // 固定尺寸（与 cropFrame 涂白区域一致）：宽 OVERLAY_W_DP，高 OVERLAY_H_DP。
                    // 放在屏幕顶部：系统截图提示/预览条出现在底部，会挡住底部按钮。
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

    /** 系统状态栏高度（像素），悬浮窗定位用 */
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

    private const val DIR_NAME = "wechat_import"
    private const val MAX_FRAMES = 48

    /** 帧指纹降采样尺寸（宽 x 高，采样点） */
    private const val FP_W = 16
    private const val FP_H = 36

    /** 垂直位移搜索范围（采样行数，±）：仅 [verticalOffset] 使用（方向检测参考实现） */
    private const val VERT_SCAN = 16

    /** 静止判定：与最后一帧灰度累计差低于此值视为同一画面 */
    private const val STILL_TOLERANCE = 1200

    /**
     * 精确回退判定：与某个更早历史帧累计差低于此值视为「画面几乎完全回到该帧位置」。
     * 设得很严格（576 个采样点平均每点 < 1.2 灰度级）：微信账单页白底灰字占主导，
     * 正常下滑一两条时与历史帧的灰度差也可能上千，阈值太宽会把正常下滑误判成回退而删帧。
     */
    private const val REVERT_TOLERANCE = 700

    /** 截屏控制悬浮窗的固定尺寸与位置（cropFrame 涂白区域必须与此一致） */
    private const val OVERLAY_W_DP = 320
    private const val OVERLAY_H_DP = 56
    private const val OVERLAY_MARGIN_DP = 16

    /** 用户控制模式的最大时长（轮询 500ms，10 分钟约 1200 次） */
    private const val USER_CONTROL_TIMEOUT_MS = 10 * 60_000L

    /** 等待用户点「开始」的最大时长 */
    private const val USER_START_TIMEOUT_MS = 5 * 60_000L

    /** 打开账单页的最大尝试次数（点账单 + 处理警告横幅的循环） */
    private const val MAX_BILL_ATTEMPTS = 6

    /** 两次导航截屏的最小间隔（毫秒）：等系统截图提示消失 */
    private const val SHOT_GAP = 4000L

    /** 「支付环境有风险」类警告横幅的 OCR 关键词（类似灵动岛通知，上滑可收掉） */
    private val WARNING_KEYWORDS = listOf("支付环境", "去处理", "风险")

    /** 微信各页面常见的窗口标题（用于前台/页面检测） */
    private val WECHAT_TITLES = setOf("微信", "通讯录", "发现", "我", "服务", "钱包", "账单", "收藏", "卡包", "设置", "支付")
}
