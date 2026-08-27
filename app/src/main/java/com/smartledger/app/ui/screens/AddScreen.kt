package com.smartledger.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.smartledger.app.accessibility.AlipayImportCoordinator
import com.smartledger.app.accessibility.WechatImportCoordinator
import com.smartledger.app.data.Categories
import com.smartledger.app.data.TxSource
import com.smartledger.app.data.TxType
import com.smartledger.app.ocr.LineItem
import com.smartledger.app.ui.AppViewModel
import com.smartledger.app.ui.DuplicateAction
import com.smartledger.app.ui.OcrState
import com.smartledger.app.ui.components.CategoryDropdown
import com.smartledger.app.ui.components.CategorySelector
import com.smartledger.app.ui.theme.Dimens
import com.smartledger.app.util.Format
import kotlinx.coroutines.launch

/** 多笔记账中的一条可编辑明细 */
private data class EditableItem(
    val selected: Boolean = true,
    val name: String = "",
    val amountText: String = "",
    val category: String = "其他支出",
    val type: String = TxType.EXPENSE,
    val timestamp: Long? = null,
    val refunded: Boolean = false,
    val closed: Boolean = false,       // 交易关闭（支付宝扣款失败，默认不勾选）
    val internal: Boolean = false,     // 内部转移（余额提现/银行卡转入，总资产不变，默认不勾选）
    val isDuplicate: Boolean = false,
)

/**
 * 记一笔：上传单张/多张图片 → OCR 自动识别 → 人工复核修正 → 保存。
 * 一张图识别出多个项目时进入「多笔模式」，可逐条勾选/编辑后批量保存。
 * 也可以完全不传图片，纯手动录入。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddScreen(
    viewModel: AppViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // ---------- 状态 ----------
    var images by rememberSaveable(
        stateSaver = listSaver<List<Uri>, String>(
            save = { list -> list.map { it.toString() } },
            restore = { list -> list.map { Uri.parse(it) } },
        ),
    ) { mutableStateOf(emptyList<Uri>()) }

    var type by rememberSaveable { mutableStateOf(TxType.EXPENSE) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var merchant by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("餐饮") }
    var note by rememberSaveable { mutableStateOf("") }
    var timestamp by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var multiMode by rememberSaveable { mutableStateOf(false) }
    var lineItems by remember { mutableStateOf(listOf<EditableItem>()) }
    var duplicateAction by remember { mutableStateOf(DuplicateAction.SKIP_DUPLICATES) }

    // 多笔识别结果的筛选状态（逻辑与首页一致：搜索 / 分类 / 日期，另加「重复状态」）
    var itemSearchText by rememberSaveable { mutableStateOf("") }
    var itemFilterCategory by rememberSaveable { mutableStateOf<String?>(null) }  // null = 全部分类
    var itemDupFilter by rememberSaveable { mutableStateOf<Boolean?>(null) }      // null=全部 / true=仅重复 / false=仅不重复
    var itemDateMode by rememberSaveable { mutableStateOf(0) }                    // 0 不限 / 1 单日 / 2 时间段
    var itemDayStart by rememberSaveable { mutableStateOf(-1L) }
    var itemRangeStart by rememberSaveable { mutableStateOf(-1L) }
    var itemRangeEnd by rememberSaveable { mutableStateOf(-1L) }
    var showItemFilterPanel by rememberSaveable { mutableStateOf(false) }
    var itemDatePickerTarget by remember { mutableStateOf<String?>(null) }        // day / start / end

    // 微信自动导入：权限引导对话框
    var showEnableServiceDialog by rememberSaveable { mutableStateOf(false) }
    var showScreenshotCapDialog by rememberSaveable { mutableStateOf(false) }
    var showOverlayDialog by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 微信账单自动导入进度
    val importState by WechatImportCoordinator.state.collectAsState()

    // 支付宝账单自动导入进度
    val alipayImportState by AlipayImportCoordinator.state.collectAsState()

    /** 点击「微信」入口：检查微信/无障碍/截屏/悬浮窗权限后发起自动导入 */
    fun startWechatImport() {
        val phase = WechatImportCoordinator.state.value.phase
        if (phase != WechatImportCoordinator.Phase.IDLE) {
            scope.launch { snackbarHostState.showSnackbar("正在导入中，请稍候") }
            return
        }
        if (runCatching { context.packageManager.getPackageInfo("com.tencent.mm", 0) }.isFailure) {
            scope.launch { snackbarHostState.showSnackbar("未检测到微信，请先安装微信") }
            return
        }
        if (!WechatImportCoordinator.isServiceEnabled(context)) {
            showEnableServiceDialog = true
            return
        }
        if (!WechatImportCoordinator.hasScreenshotCapability(context)) {
            showScreenshotCapDialog = true
            return
        }
        // 悬浮窗权限是截屏交互的前提（面板上手动开始/完成），没有就先引导开启
        if (!WechatImportCoordinator.canDrawOverlays(context)) {
            showOverlayDialog = true
            return
        }
        WechatImportCoordinator.startImport()
    }

    /** 点击「支付宝」入口：检查支付宝/无障碍/截屏/悬浮窗权限后发起自动导入 */
    fun startAlipayImport() {
        val phase = AlipayImportCoordinator.state.value.phase
        if (phase != AlipayImportCoordinator.Phase.IDLE) {
            scope.launch { snackbarHostState.showSnackbar("正在导入中，请稍候") }
            return
        }
        if (runCatching { context.packageManager.getPackageInfo("com.eg.android.AlipayGphone", 0) }.isFailure) {
            scope.launch { snackbarHostState.showSnackbar("未检测到支付宝，请先安装支付宝") }
            return
        }
        if (!AlipayImportCoordinator.isServiceEnabled(context)) {
            showEnableServiceDialog = true
            return
        }
        if (!AlipayImportCoordinator.hasScreenshotCapability(context)) {
            showScreenshotCapDialog = true
            return
        }
        if (!AlipayImportCoordinator.canDrawOverlays(context)) {
            showOverlayDialog = true
            return
        }
        AlipayImportCoordinator.startImport()
    }

    // ---------- 选图（系统 Photo Picker，可多选，免存储权限） ----------
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(9),
    ) { uris ->
        if (uris.isNotEmpty()) {
            images = images + uris
            viewModel.processImages(images)
        }
    }

    // ---------- OCR 结果自动回填表单 ----------
    val ocrState by viewModel.ocrState.collectAsState()
    val customCategories by viewModel.customCategories.collectAsState()
    val categoryOrder by viewModel.categoryOrder.collectAsState()
    LaunchedEffect(ocrState) {
        val result = ocrState as? OcrState.Result ?: return@LaunchedEffect
        val info = result.info
        type = info.type
        if (!info.merchant.isNullOrBlank()) merchant = info.merchant
        if (info.timestamp != null) timestamp = info.timestamp

        if (result.items.size >= 2) {
            // 一张图识别出多个项目 → 多笔模式
            multiMode = true
            duplicateAction = DuplicateAction.SKIP_DUPLICATES
            lineItems = result.items.mapIndexed { index, it ->
                EditableItem(
                    // 交易关闭（扣款失败）、内部转移（总资产不变）、0 元条目默认不勾选保存
                    selected = !it.closed && !it.internal && it.amount > 0,
                    name = it.name,
                    amountText = Format.money(it.amount).replace(",", ""),
                    category = it.category,
                    type = it.type,
                    timestamp = it.timestamp,
                    refunded = it.refunded,
                    closed = it.closed,
                    internal = it.internal,
                    isDuplicate = index in result.duplicateMatches,
                )
            }
        } else {
            // 单笔模式（保持原有回填）
            multiMode = false
            lineItems = emptyList()
            if (info.amount != null) amountText = Format.money(info.amount).replace(",", "")
            if (!info.category.isNullOrBlank()) category = info.category
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("记一笔") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dimens.pagePadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.sectionSpacing),
        ) {
            // ============ 凭证上传 ============
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            ) {
                Column(modifier = Modifier.padding(Dimens.cardPadding)) {
                    Text("上传凭证", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "拍摄或选择小票/账单截图，自动识别金额、商家、时间并归类。支持多张。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Dimens.md))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(images, key = { it.toString() }) { uri ->
                            Box {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = "凭证图片",
                                    modifier = Modifier
                                        .size(84.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                )
                                IconButton(
                                    onClick = {
                                        images = images - uri
                                        viewModel.clearOcr()
                                        if (images.isNotEmpty()) {
                                            viewModel.processImages(images)
                                        } else {
                                            amountText = ""
                                            merchant = ""
                                        }
                                    },
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(22.dp)
                                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "移除",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                        item {
                            OutlinedButton(
                                onClick = {
                                    pickLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                },
                                modifier = Modifier
                                    .size(84.dp)
                                    .padding(0.dp),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Filled.Image, contentDescription = null)
                                    Spacer(Modifier.height(4.dp))
                                    Text("选图", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        item {
                            // 微信账单自动导入入口：跳微信账单页 → 自动截长图 → 识别
                            OutlinedButton(
                                onClick = { startWechatImport() },
                                enabled = importState.phase == WechatImportCoordinator.Phase.IDLE,
                                modifier = Modifier
                                    .size(84.dp)
                                    .padding(0.dp),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Chat,
                                        contentDescription = null,
                                        tint = Color(0xFF07C160),
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text("微信", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        item {
                            // 支付宝账单自动导入入口：跳支付宝「我的-账单」→ 自动截长图 → 识别
                            OutlinedButton(
                                onClick = { startAlipayImport() },
                                enabled = alipayImportState.phase == AlipayImportCoordinator.Phase.IDLE,
                                modifier = Modifier
                                    .size(84.dp)
                                    .padding(0.dp),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        Icons.Filled.Image,
                                        contentDescription = null,
                                        tint = Color(0xFF1677FF),
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text("支付宝", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }

                    // ---------- 微信自动导入进度 / 手动兜底 ----------
                    when (importState.phase) {
                        WechatImportCoordinator.Phase.NAVIGATING -> {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(importState.message, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { WechatImportCoordinator.cancel() }) { Text("取消") }
                            }
                        }

                        WechatImportCoordinator.Phase.CAPTURING -> {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "正在截取账单长图… 已截 ${importState.frameCount} 屏（屏幕上点「完成」可结束）",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { WechatImportCoordinator.cancel() }) { Text("取消") }
                            }
                        }

                        WechatImportCoordinator.Phase.OCR_PENDING -> {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(importState.message, style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        WechatImportCoordinator.Phase.NEED_MANUAL -> {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                importState.message,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { WechatImportCoordinator.startCaptureOnly() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("我已进入账单页，开始截图导入")
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { WechatImportCoordinator.cancel() }) { Text("取消") }
                            }
                        }

                        WechatImportCoordinator.Phase.FAILED -> {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                importState.message,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        WechatImportCoordinator.Phase.IDLE -> Unit
                    }

                    // ---------- 支付宝自动导入进度 / 手动兜底 ----------
                    when (alipayImportState.phase) {
                        AlipayImportCoordinator.Phase.NAVIGATING -> {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(alipayImportState.message, style = MaterialTheme.typography.labelMedium)
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { AlipayImportCoordinator.cancel() }) { Text("取消") }
                            }
                        }

                        AlipayImportCoordinator.Phase.CAPTURING -> {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "正在截取账单长图… 已截 ${alipayImportState.frameCount} 屏（屏幕上点「完成」可结束）",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { AlipayImportCoordinator.cancel() }) { Text("取消") }
                            }
                        }

                        AlipayImportCoordinator.Phase.OCR_PENDING -> {
                            Spacer(Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(alipayImportState.message, style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        AlipayImportCoordinator.Phase.NEED_MANUAL -> {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                alipayImportState.message,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Button(
                                    onClick = { AlipayImportCoordinator.startCaptureOnly() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("我已进入账单页，开始截图导入")
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = { AlipayImportCoordinator.cancel() }) { Text("取消") }
                            }
                        }

                        AlipayImportCoordinator.Phase.FAILED -> {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                alipayImportState.message,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        AlipayImportCoordinator.Phase.IDLE -> Unit
                    }

                    // ---------- OCR 进度 / 结果 ----------
                    when (val state = ocrState) {
                        is OcrState.Processing -> {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(
                                progress = { state.done.toFloat() / state.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "正在识别 ${state.done}/${state.total} ...",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }

                        is OcrState.Result -> {
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "识别完成，已自动填入下方表单，请核对",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        OcrState.Idle -> Unit
                    }
                }
            }

            // ============ 类型 ============
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = type == TxType.EXPENSE,
                    onClick = {
                        type = TxType.EXPENSE
                        category = Categories.EXPENSE.first().name
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("支出") }
                SegmentedButton(
                    selected = type == TxType.INCOME,
                    onClick = {
                        type = TxType.INCOME
                        category = Categories.INCOME.first().name
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("收入") }
            }

            if (multiMode) {
                // ============ 多笔明细编辑 ============
                val selectedItems = lineItems.filter { it.selected }
                val selectedTotal = selectedItems.sumOf { it.amountText.toDoubleOrNull() ?: 0.0 }
                val duplicateCount = lineItems.count { it.isDuplicate }
                val refundPairCount = lineItems.count { it.refunded }
                val closedCount = lineItems.count { it.closed }
                val internalCount = lineItems.count { it.internal }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("识别到 ${lineItems.size} 笔明细", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "勾选需要入账的项目，可修改名称、金额与分类",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (refundPairCount > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "含 $refundPairCount 笔已全额退款：已在其下成对生成「退款·」收入（净额 0）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (closedCount > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "含 $closedCount 笔「交易关闭」（扣款失败，未实际支出）：已默认不勾选，保存时不会记录这 $closedCount 笔",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (internalCount > 0) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "含 $internalCount 笔内部转移（余额提现/银行卡定时转入等，总资产不变）：已默认不勾选",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (duplicateCount > 0) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "发现 $duplicateCount 条与已导入账单重复（下方已标记）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = duplicateAction == DuplicateAction.SKIP_DUPLICATES,
                                    onClick = { duplicateAction = DuplicateAction.SKIP_DUPLICATES },
                                    label = { Text("跳过重复") },
                                )
                                FilterChip(
                                    selected = duplicateAction == DuplicateAction.REPLACE_DUPLICATES,
                                    onClick = { duplicateAction = DuplicateAction.REPLACE_DUPLICATES },
                                    label = { Text("替换重复") },
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))

                        // ---------- 多笔筛选（搜索 / 分类 / 重复状态 / 日期） ----------
                        val itemCategories = remember(categoryOrder) {
                            (categoryOrder[TxType.EXPENSE].orEmpty() + categoryOrder[TxType.INCOME].orEmpty()).distinct()
                        }
                        val filteredItems = remember(
                            lineItems, itemSearchText, itemFilterCategory, itemDupFilter,
                            itemDateMode, itemDayStart, itemRangeStart, itemRangeEnd,
                        ) {
                            val query = itemSearchText.trim()
                            lineItems.mapIndexed { idx, it -> idx to it }.filter { (_, it) ->
                                val matchSearch = query.isEmpty() || it.name.contains(query, ignoreCase = true)
                                val matchCategory = itemFilterCategory == null || it.category == itemFilterCategory
                                val matchDup = itemDupFilter == null || it.isDuplicate == itemDupFilter
                                val matchDate = when (itemDateMode) {
                                    1 -> it.timestamp != null && itemDayStart >= 0 &&
                                        it.timestamp in itemDayStart until itemDayStart + Format.DAY_MS
                                    2 -> if (itemRangeStart >= 0 && itemRangeEnd >= 0 && it.timestamp != null) {
                                        it.timestamp in itemRangeStart until itemRangeEnd + Format.DAY_MS
                                    } else true
                                    else -> true
                                }
                                matchSearch && matchCategory && matchDup && matchDate
                            }
                        }
                        val hasItemFilter = itemSearchText.isNotBlank() || itemFilterCategory != null ||
                            itemDupFilter != null || itemDateMode != 0

                        OutlinedTextField(
                            value = itemSearchText,
                            onValueChange = { itemSearchText = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索项目名称") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (itemSearchText.isNotEmpty()) {
                                    IconButton(onClick = { itemSearchText = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                                    }
                                }
                            },
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(
                                selected = showItemFilterPanel,
                                onClick = { showItemFilterPanel = !showItemFilterPanel },
                                label = { Text(if (hasItemFilter) "筛选（已启用）" else "筛选") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.FilterList,
                                        contentDescription = null,
                                        modifier = Modifier.width(16.dp),
                                    )
                                },
                            )
                            if (hasItemFilter) {
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    itemSearchText = ""
                                    itemFilterCategory = null
                                    itemDupFilter = null
                                    itemDateMode = 0
                                    itemDayStart = -1L
                                    itemRangeStart = -1L
                                    itemRangeEnd = -1L
                                }) { Text("清除筛选") }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "显示 ${filteredItems.size}/${lineItems.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        if (showItemFilterPanel) {
                            Spacer(Modifier.height(8.dp))
                            Column {
                                Text(
                                    "按分类筛选",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    FilterChip(
                                        selected = itemFilterCategory == null,
                                        onClick = { itemFilterCategory = null },
                                        label = { Text("全部分类") },
                                    )
                                    itemCategories.forEach { name ->
                                        FilterChip(
                                            selected = itemFilterCategory == name,
                                            onClick = { itemFilterCategory = name },
                                            label = { Text(name) },
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "按重复状态",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = itemDupFilter == null,
                                        onClick = { itemDupFilter = null },
                                        label = { Text("全部") },
                                    )
                                    FilterChip(
                                        selected = itemDupFilter == true,
                                        onClick = { itemDupFilter = true },
                                        label = { Text("仅重复") },
                                    )
                                    FilterChip(
                                        selected = itemDupFilter == false,
                                        onClick = { itemDupFilter = false },
                                        label = { Text("仅不重复") },
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "按日期筛选",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = itemDateMode == 0, onClick = { itemDateMode = 0 }, label = { Text("不限") })
                                    FilterChip(selected = itemDateMode == 1, onClick = { itemDateMode = 1 }, label = { Text("单日") })
                                    FilterChip(selected = itemDateMode == 2, onClick = { itemDateMode = 2 }, label = { Text("时间段") })
                                }
                                Spacer(Modifier.height(8.dp))
                                when (itemDateMode) {
                                    1 -> OutlinedButton(onClick = { itemDatePickerTarget = "day" }) {
                                        Text(if (itemDayStart >= 0) Format.date(itemDayStart) else "选择日期")
                                    }
                                    2 -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedButton(onClick = { itemDatePickerTarget = "start" }) {
                                            Text(if (itemRangeStart >= 0) Format.date(itemRangeStart) else "开始日期")
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text("~", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(8.dp))
                                        OutlinedButton(onClick = { itemDatePickerTarget = "end" }) {
                                            Text(if (itemRangeEnd >= 0) Format.date(itemRangeEnd) else "结束日期")
                                        }
                                    }
                                }
                            }
                        }

                        if (filteredItems.isEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "没有符合筛选条件的项目",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        filteredItems.forEach { (originalIndex, item) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = item.selected,
                                    onCheckedChange = { checked ->
                                        lineItems = lineItems.toMutableList().also { list ->
                                            list[originalIndex] = list[originalIndex].copy(selected = checked)
                                        }
                                    },
                                )
                                Spacer(Modifier.width(4.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    item.timestamp?.let { ts ->
                                        Text(
                                            text = Format.dateTime(ts),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    if (item.refunded || item.closed || item.internal || item.isDuplicate) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            if (item.refunded) {
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            MaterialTheme.colorScheme.surfaceVariant,
                                                            RoundedCornerShape(6.dp),
                                                        )
                                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                                ) {
                                                    Text(
                                                        "已全额退款",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.error,
                                                    )
                                                }
                                            }
                                            if (item.closed) {
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            MaterialTheme.colorScheme.errorContainer,
                                                            RoundedCornerShape(6.dp),
                                                        )
                                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                                ) {
                                                    Text(
                                                        "交易关闭 · 扣款失败，不会保存",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                                    )
                                                }
                                            }
                                            if (item.internal) {
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            MaterialTheme.colorScheme.surfaceVariant,
                                                            RoundedCornerShape(6.dp),
                                                        )
                                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                                ) {
                                                    Text(
                                                        "内部转移 · 总资产不变",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            }
                                            if (item.isDuplicate) {
                                                Box(
                                                    modifier = Modifier
                                                        .background(
                                                            MaterialTheme.colorScheme.errorContainer,
                                                            RoundedCornerShape(6.dp),
                                                        )
                                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                                ) {
                                                    Text(
                                                        "与已导入重复",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    OutlinedTextField(
                                        value = item.name,
                                        onValueChange = { v ->
                                            lineItems = lineItems.toMutableList().also { list ->
                                                list[originalIndex] = list[originalIndex].copy(name = v)
                                            }
                                        },
                                        label = { Text("项目名称") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = item.amountText,
                                            onValueChange = { v ->
                                                val filtered = v.filter { c -> c.isDigit() || c == '.' }
                                                lineItems = lineItems.toMutableList().also { list ->
                                                    list[originalIndex] = list[originalIndex].copy(amountText = filtered)
                                                }
                                            },
                                            label = { Text("金额") },
                                            prefix = { Text("¥") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                            modifier = Modifier.width(130.dp),
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        CategoryDropdown(
                                            category = item.category,
                                            type = item.type,
                                            names = categoryOrder[item.type].orEmpty(),
                                            customCategories = customCategories[item.type].orEmpty(),
                                            onSelect = { name ->
                                                lineItems = lineItems.toMutableList().also { list ->
                                                    list[originalIndex] = list[originalIndex].copy(category = name)
                                                }
                                            },
                                            onAddCustom = { name, iconKey ->
                                                viewModel.addCustomCategory(item.type, name, iconKey)
                                            },
                                            onRemoveCustom = { viewModel.removeCustomCategory(item.type, it) },
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已选 ${selectedItems.size} 笔，合计",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                Format.money(selectedTotal),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val toSave = selectedItems.mapNotNull { it ->
                                    val amt = it.amountText.toDoubleOrNull()
                                    if (amt != null && amt > 0) {
                                        LineItem(
                                            name = it.name,
                                            amount = amt,
                                            category = it.category,
                                            type = it.type,
                                            timestamp = it.timestamp,
                                            refunded = it.refunded,
                                        )
                                    } else null
                                }
                                if (toSave.isNotEmpty()) {
                                    val source = if (images.isNotEmpty()) TxSource.OCR else TxSource.MANUAL
                                    val action = if (duplicateCount > 0) duplicateAction else DuplicateAction.KEEP_ALL
                                    viewModel.saveTransactions(
                                        items = toSave,
                                        fallbackTimestamp = timestamp,
                                        imageUri = images.firstOrNull()?.toString(),
                                        source = source,
                                        duplicateAction = action,
                                    ) { saved ->
                                        scope.launch { snackbarHostState.showSnackbar("已保存 $saved 笔") }
                                        images = emptyList()
                                        merchant = ""
                                        note = ""
                                        multiMode = false
                                        lineItems = emptyList()
                                        timestamp = System.currentTimeMillis()
                                        viewModel.clearOcr()
                                    }
                                }
                            },
                            enabled = selectedItems.isNotEmpty(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                        ) {
                            Text("保存 ${selectedItems.size} 笔", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ============ 时间（整单默认，未识别到日期的条目使用） ============
                Text("整单时间（未识别到时间的条目将使用）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = Format.dateTime(timestamp),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = { timestamp = System.currentTimeMillis() }) { Text("现在") }
                    OutlinedButton(onClick = { timestamp = System.currentTimeMillis() - 24L * 3600_000 }) { Text("昨天") }
                    TextButton(onClick = { showDatePicker = true }) { Text("改日期") }
                }
            } else {

            // ============ 金额 ============
            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text("金额（元）") },
                prefix = { Text("¥") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            // ============ 商家 ============
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text("商家 / 收款方（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            // ============ 分类 ============
            Text("分类", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            CategorySelector(
                type = type,
                selected = category,
                names = categoryOrder[type].orEmpty(),
                customCategories = customCategories[type].orEmpty(),
                onSelect = { category = it },
                onAddCustom = { name, iconKey -> viewModel.addCustomCategory(type, name, iconKey) },
                onRemoveCustom = { viewModel.removeCustomCategory(type, it) },
            )

            Spacer(Modifier.height(12.dp))

            // ============ 时间 ============
            Text("时间", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = Format.dateTime(timestamp),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { timestamp = System.currentTimeMillis() }) {
                    Text("现在")
                }
                OutlinedButton(onClick = { timestamp = System.currentTimeMillis() - 24L * 3600_000 }) {
                    Text("昨天")
                }
                TextButton(onClick = { showDatePicker = true }) {
                    Text("改日期")
                }
            }

            Spacer(Modifier.height(12.dp))

            // ============ 备注 ============
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            // ============ 保存 ============
            val amount = amountText.replace(",", "").toDoubleOrNull()
            Button(
                onClick = {
                    if (amount != null && amount > 0) {
                        val source = if (images.isNotEmpty()) TxSource.OCR else TxSource.MANUAL
                        viewModel.saveTransaction(
                            type = type,
                            amount = amount,
                            category = category,
                            merchant = merchant.trim(),
                            timestamp = timestamp,
                            note = note.trim(),
                            imageUri = images.firstOrNull()?.toString(),
                            source = source,
                        ) {
                            scope.launch { snackbarHostState.showSnackbar("已保存") }
                            images = emptyList()
                            amountText = ""
                            merchant = ""
                            note = ""
                            type = TxType.EXPENSE
                            category = Categories.EXPENSE.first().name
                            timestamp = System.currentTimeMillis()
                            viewModel.clearOcr()
                        }
                    }
                },
                enabled = amount != null && amount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text("保存", style = MaterialTheme.typography.titleMedium)
            }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ---------- 日期选择 ----------
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = Format.utcMidnight(timestamp),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { selected ->
                        timestamp = Format.combineDate(selected, timestamp)
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // ---------- 多笔筛选：日期选择 ----------
    itemDatePickerTarget?.let { target ->
        val initial = when (target) {
            "day" -> if (itemDayStart >= 0) Format.utcMidnight(itemDayStart) else null
            "start" -> if (itemRangeStart >= 0) Format.utcMidnight(itemRangeStart) else null
            else -> if (itemRangeEnd >= 0) Format.utcMidnight(itemRangeEnd) else null
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { itemDatePickerTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { selected ->
                        // DatePicker 返回 UTC 零点，转成本地当天 00:00
                        val localStart = Format.localDayStart(selected)
                        when (target) {
                            "day" -> itemDayStart = localStart
                            "start" -> itemRangeStart = localStart
                            "end" -> itemRangeEnd = localStart
                        }
                    }
                    itemDatePickerTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { itemDatePickerTarget = null }) { Text("取消") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // ---------- 微信/支付宝导入：开启无障碍服务引导 ----------
    if (showEnableServiceDialog) {
        AlertDialog(
            onDismissRequest = { showEnableServiceDialog = false },
            title = { Text("开启无障碍服务") },
            text = {
                Text(
                    "「微信/支付宝账单自动导入」需要系统无障碍服务：\n\n" +
                        "1. 自动打开微信/支付宝并跳转到账单页\n" +
                        "2. 自动滚动截取账单长图并识别入账\n" +
                        "3. 截图仅用于识别，导入完成后立即删除\n\n" +
                        "开启后回到本页，再次点击「微信」或「支付宝」即可导入。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showEnableServiceDialog = false
                    WechatImportCoordinator.openAccessibilitySettings(context)
                }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { showEnableServiceDialog = false }) { Text("取消") }
            },
        )
    }

    // ---------- 微信/支付宝导入：开启「截屏」权限引导 ----------
    if (showScreenshotCapDialog) {
        AlertDialog(
            onDismissRequest = { showScreenshotCapDialog = false },
            title = { Text("还需开启「截屏」权限") },
            text = {
                Text(
                    "无障碍服务已开启，但系统还未允许它截屏。\n\n" +
                        "请在「无障碍 → 微信/支付宝账单自动导入」详情页中，把「截屏」开关打开，\n" +
                        "然后回到本页再次点击「微信」或「支付宝」。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showScreenshotCapDialog = false
                    WechatImportCoordinator.openAccessibilitySettings(context)
                }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { showScreenshotCapDialog = false }) { Text("取消") }
            },
        )
    }

    // ---------- 微信/支付宝导入：开启悬浮窗权限引导（截屏交互的前提） ----------
    if (showOverlayDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayDialog = false },
            title = { Text("开启悬浮窗权限") },
            text = {
                Text(
                    "账单截屏需要「显示在其他应用上层」权限：\n\n" +
                        "进入账单页后，屏幕顶部会出现控制面板——先点「开始」再滑动账单选择截屏范围，点「完成」截止识别。\n\n" +
                        "请到设置中为鲸鱼记账开启悬浮窗权限，然后回到本页再次点击「微信」或「支付宝」。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showOverlayDialog = false
                    WechatImportCoordinator.openOverlaySettings(context)
                }) { Text("去开启") }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayDialog = false }) { Text("取消") }
            },
        )
    }
}

