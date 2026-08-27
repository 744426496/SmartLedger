package com.smartledger.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.smartledger.app.ui.AppViewModel
import com.smartledger.app.ui.screens.AddScreen
import com.smartledger.app.ui.screens.EditScreen
import com.smartledger.app.ui.screens.HomeScreen
import com.smartledger.app.ui.screens.OverviewScreen
import com.smartledger.app.ui.screens.SettingsScreen
import com.smartledger.app.ui.theme.SmartLedgerTheme
import kotlinx.coroutines.flow.MutableStateFlow

/** 跨组件导航事件（微信导入完成后回「记一笔」页用） */
object NavEvents {
    val openAdd = MutableStateFlow(false)
}

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_ADD = "open_add"

        /** 当前 Activity 的 ViewModel 引用（供无障碍导入完成后直接触发 OCR） */
        @Volatile
        var currentViewModel: AppViewModel? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_OPEN_ADD, false)) NavEvents.openAdd.value = true
        enableEdgeToEdge()
        setContent {
            val viewModel: AppViewModel = viewModel()
            MainActivity.currentViewModel = viewModel
            val themeMode by viewModel.themeMode.collectAsState()
            SmartLedgerTheme(themeMode = themeMode) {
                AppRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_ADD, false)) NavEvents.openAdd.value = true
    }

    /**
     * 用户从最近任务划掉本 App 时，标准 Android 会【同步】回调 Activity.onTaskRemoved(Intent)（API 21+）。
     *
     * 注意：本机离线 SDK 的 android.jar 被裁剪，编译期 Activity 里没有这个方法，
     * 因此这里不能写 override、也不能调 super（默认实现本就是空）。但 ART 虚拟机按
     * 「方法名 + 签名」做虚分派，运行时本方法仍会被框架命中，等效于重写——这是
     * 裁剪版 SDK 下唯一能收到「划卡」同步回调的办法。
     *
     * 实测（OnePlus 13/ColorOS）：划卡时系统 2ms 内杀进程 / 或根本不派发本回调，
     * 主要兜底是 WechatImportService 的常驻看门狗（registerActiveTask）；本方法保留
     * 给标准 Android 设备（派发正常时在进程被杀前同步撤销）。
     */
    fun onTaskRemoved(rootIntent: Intent?) {
        android.util.Log.i("TaskCheck", "onTaskRemoved fired → revoking accessibility")
        com.smartledger.app.accessibility.WechatImportService.revokeAccessibility()
    }

    /**
     * 兜底检测：onStop 后延时检查任务列表，任务全被划掉（appTasks 为空）即撤销。
     * 不能直接用 onStop 撤销（切到别的 App 也会 onStop，不能撤销）；
     * 也不能只依赖服务 onUnbind/onDestroy（ColorOS 划卡后可能保活进程、服务不解绑）。
     * 注意：真实划卡时 App 往往已因打开最近任务而 onStop 过，本检测只跑一次会错过
     * 任务移除——主要兜底是 WechatImportService 里的常驻看门狗（registerActiveTask）。
     */
    override fun onStop() {
        super.onStop()
        val checkTask = Runnable {
            runCatching {
                val am = getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                val tasks = am?.appTasks.orEmpty()
                if (tasks.isEmpty()) {
                    android.util.Log.i("TaskCheck", "onStop check: appTasks empty, revoking accessibility")
                    com.smartledger.app.accessibility.WechatImportService.revokeAccessibility()
                } else {
                    android.util.Log.i("TaskCheck", "onStop check: appTasks=${tasks.size}, keep accessibility")
                }
            }
        }
        checkHandler.removeCallbacks(checkTask)
        checkHandler.postDelayed(checkTask, 1500)
    }

    override fun onStart() {
        super.onStart()
        // 回到前台说明没被划掉，取消未执行的检查
        checkHandler.removeCallbacksAndMessages(null)
        // 通知无障碍服务"App 已打开过"，激活任务看门狗（划卡保活进程时兜底撤销授权）
        com.smartledger.app.accessibility.WechatImportService.registerActiveTask()
    }

    private val checkHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onDestroy() {
        // 注意：绝不能在这里 removeCallbacksAndMessages 取消 onStop 挂起的任务检测——
        // 划卡时 onStop → onDestroy 紧挨着发生，一取消检测就永远不执行（v1.0.22 的根因）。
        // 检测本身幂等（任务还在就不撤销、onTaskRemoved 已同步撤销，晚到只是重复 no-op）。
        MainActivity.currentViewModel = null
        super.onDestroy()
    }
}

private data class TabItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    TabItem("home", "首页", Icons.Filled.Home),
    TabItem("add", "记一笔", Icons.AutoMirrored.Filled.ReceiptLong),
    TabItem("overview", "总览", Icons.Filled.PieChart),
)

@Composable
private fun AppRoot(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // 微信账单导入完成后回「记一笔」页
    LaunchedEffect(Unit) {
        NavEvents.openAdd.collect { open ->
            if (open) {
                navController.navigate("add") { launchSingleTop = true }
                NavEvents.openAdd.value = false
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            navController.navigate(tab.route) {
                                // 弹出到首页之上，保证"首页"一定能切换回来
                                popUpTo(navController.graph.findStartDestination().id) {
                                    inclusive = false
                                }
                                launchSingleTop = true
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentRoute == "home") {
                FloatingActionButton(onClick = {
                    navController.navigate("add") { launchSingleTop = true }
                }) {
                    Icon(Icons.Filled.Add, contentDescription = "记一笔")
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding),
        ) {
            composable("home") {
                HomeScreen(
                    viewModel = viewModel,
                    onAdd = { navController.navigate("add") { launchSingleTop = true } },
                    onEditTransaction = { id -> navController.navigate("edit/$id") { launchSingleTop = true } },
                    onSettings = { navController.navigate("settings") { launchSingleTop = true } },
                )
            }
            composable("add") {
                AddScreen(viewModel = viewModel, onDone = { navController.popBackStack() })
            }
            composable("edit/{id}") { backStackEntry ->
                val id = backStackEntry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                EditScreen(transactionId = id, viewModel = viewModel, onDone = { navController.popBackStack() })
            }
            composable("settings") {
                SettingsScreen(viewModel = viewModel, onDone = { navController.popBackStack() })
            }
            composable("overview") {
                OverviewScreen(viewModel = viewModel)
            }
        }
    }
}
