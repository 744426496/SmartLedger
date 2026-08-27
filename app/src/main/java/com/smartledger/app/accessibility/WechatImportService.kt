package com.smartledger.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 微信账单自动导入无障碍服务。
 *
 * 由系统绑定常驻（顺带保证 App 进程存活，支撑后台 OCR 导入）。
 * 平时不监听任何事件、不读取任何数据；只有在本应用内点击「微信」入口
 * 发起导入请求时，才在后台线程执行：打开微信 → 自动跳转「我-服务-钱包-账单」
 * → 滚动截取账单长图，交给 OCR 识别。
 *
 * 截图文件只写入本应用私有目录，识别完成后立即删除，不上传。
 */
class WechatImportService : AccessibilityService() {

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val SERVICE_CLASS = "com.smartledger.app.accessibility.WechatImportService"

        /** 看门狗轮询间隔：App 打开过之后，周期性确认自己的任务还在最近任务里 */
        private const val WATCHDOG_INTERVAL_MS = 1500L

        /** 连续多少次轮询都看不到任务才撤销（防瞬时抖动误撤） */
        private const val WATCHDOG_EMPTY_TICKS_TO_REVOKE = 2

        @Volatile
        private var instance: WechatImportService? = null

        /** App 是否在本进程内打开过（MainActivity.onStart 置位）。看门狗只在本进程打开过 App 后激活 */
        @Volatile
        private var appWasOpened = false

        /**
         * App 前台启动过（MainActivity.onStart 调用）。
         * 打开看门狗：此后周期性检查自己的任务是否还在；任务全被划掉（appTasks 为空）
         * 即撤销无障碍授权。这样即使 ColorOS 划卡不派发 onTaskRemoved、且进程保活，
         * 也能在几秒内兜底撤销。进程未启动过时看门狗不激活，避免开机时误撤。
         * 注意竞态：onStart 可能早于服务连接（instance 为空），此时置位 appWasOpened，
         * 由 onServiceConnected 补启动。
         */
        fun registerActiveTask() {
            appWasOpened = true
            instance?.startWatchdog()
        }

        /**
         * 主动撤销本服务的无障碍授权（App 被从最近任务划掉时由 Activity 调用）。
         * 从最近任务划掉时 ColorOS 可能保活进程、服务不解绑，onUnbind/onDestroy 不一定触发，
         * 必须由 Activity.onTaskRemoved 主动撤销；进程已被杀时 instance 为空则无需处理
         * （系统解绑时 onUnbind/onDestroy 兜底已撤销）。
         */
        fun revokeAccessibility() {
            instance?.revokeSelf()
        }
    }

    private val workerThread = HandlerThread("wechat-import").apply { start() }

    /** 状态机执行的专用线程（避免阻塞主线程 / 无障碍回调线程） */
    internal val worker: Handler = Handler(workerThread.looper)

    /** 最近一次窗口状态事件的包名（微信 8.0.58+ 屏蔽了窗口内容读取，但事件仍带包名，可用于前台检测） */
    @Volatile
    private var lastEventPackage: String? = null

    /** 是否已请求撤销本服务的无障碍授权（防重入） */
    @Volatile
    private var selfDisabled = false

    /** 看门狗：App 打开过之后周期性确认任务还在，任务全没了就撤销授权 */
    @Volatile
    private var watchdogActive = false
    private var watchdogEmptyTicks = 0
    private var watchdogLastLoggedCount = -1
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogTick = object : Runnable {
        override fun run() {
            checkWatchdog()
            watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // 补启动看门狗：MainActivity.onStart 可能早于服务连接（instance 当时为空），
        // 若 App 已打开过则在服务连上后补激活
        if (appWasOpened) startWatchdog()
        WechatImportCoordinator.onServiceConnected(this)
        AlipayImportCoordinator.onServiceConnected(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 服务被系统解绑（App 后台被清理/用户关闭服务/进程被回收）时，
        // 自动撤销本服务的无障碍授权，避免权限在后台长期挂起（安全考虑）。
        revokeSelf()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        // 兜底：销毁路径也请求撤销（onUnbind 不一定每次都走到）
        revokeSelf()
        stopWatchdog()
        watchdogActive = false
        WechatImportCoordinator.onServiceConnected(null)
        WechatImportCoordinator.cancel()
        AlipayImportCoordinator.onServiceConnected(null)
        AlipayImportCoordinator.cancel()
        instance = null
        workerThread.quitSafely()
        super.onDestroy()
    }

    /** 请求系统移除本服务的无障碍授权（只执行一次） */
    private fun revokeSelf() {
        if (selfDisabled) return
        selfDisabled = true
        android.util.Log.i("TaskCheck", "WechatImportService.revokeSelf → disableSelf()")
        runCatching { disableSelf() }
    }

    /** 启动看门狗轮询（幂等） */
    private fun startWatchdog() {
        watchdogActive = true
        watchdogEmptyTicks = 0
        watchdogHandler.removeCallbacks(watchdogTick)
        watchdogHandler.post(watchdogTick)
        android.util.Log.i("TaskCheck", "watchdog started")
    }

    private fun stopWatchdog() {
        watchdogHandler.removeCallbacks(watchdogTick)
    }

    /** 看门狗单次检查：任务全被划掉（appTasks 为空）连续多次 → 撤销授权 */
    private fun checkWatchdog() {
        if (!watchdogActive) return
        runCatching {
            val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val tasks = am?.appTasks.orEmpty()
            // 任务数变化时记录一次（验证 getAppTasks 是否已排除被划掉的任务；也便于真机排查）
            if (tasks.size != watchdogLastLoggedCount) {
                watchdogLastLoggedCount = tasks.size
                android.util.Log.i("TaskCheck", "watchdog: appTasks=${tasks.size} ids=${tasks.map { it.taskInfo.taskId }}")
            }
            if (tasks.isEmpty()) {
                watchdogEmptyTicks++
                android.util.Log.i("TaskCheck", "watchdog: appTasks empty ($watchdogEmptyTicks/$WATCHDOG_EMPTY_TICKS_TO_REVOKE)")
                if (watchdogEmptyTicks >= WATCHDOG_EMPTY_TICKS_TO_REVOKE) {
                    android.util.Log.i("TaskCheck", "watchdog: task gone, revoking accessibility")
                    revokeSelf()
                }
            } else {
                watchdogEmptyTicks = 0
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.let { lastEventPackage = it.toString() }
    }

    override fun onInterrupt() = Unit

    internal fun lastEventPackage(): String? = lastEventPackage

    internal fun postToWorker(runnable: Runnable) = worker.post(runnable)

    internal fun removeWorkerCallbacks() = worker.removeCallbacksAndMessages(null)
}
