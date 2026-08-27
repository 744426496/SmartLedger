package com.smartledger.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartledger.app.BuildConfig
import com.smartledger.app.MainActivity
import com.smartledger.app.accessibility.AlipayImportCoordinator
import com.smartledger.app.accessibility.WechatImportCoordinator
import com.smartledger.app.data.AppDatabase
import com.smartledger.app.data.BalanceStore
import com.smartledger.app.data.Categories
import com.smartledger.app.data.CategoryPrefs
import com.smartledger.app.data.CategoryStore
import com.smartledger.app.data.ThemeStore
import com.smartledger.app.data.TxSource
import com.smartledger.app.data.TxType
import com.smartledger.app.data.TransactionEntity
import com.smartledger.app.data.TransactionRepository
import com.smartledger.app.data.WidgetStore
import com.smartledger.app.ocr.ExtractedInfo
import com.smartledger.app.ocr.LineItem
import com.smartledger.app.ocr.OcrEngine
import com.smartledger.app.ocr.ReceiptParser
import com.smartledger.app.ui.theme.ThemeMode
import com.smartledger.app.widget.WidgetPeriod
import com.smartledger.app.widget.WidgetUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** OCR 处理状态机 */
sealed interface OcrState {
    data object Idle : OcrState
    data class Processing(val done: Int, val total: Int) : OcrState
    data class Result(
        val info: ExtractedInfo,
        val items: List<LineItem> = emptyList(),
        val duplicateMatches: Map<Int, List<TransactionEntity>> = emptyMap(),
    ) : OcrState
}

/** 保存识别结果时，遇到与已导入交易重复的条目如何处置 */
enum class DuplicateAction { KEEP_ALL, SKIP_DUPLICATES, REPLACE_DUPLICATES }

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.get(application)
    private val repository = TransactionRepository(database.transactionDao())
    private val ocrEngine = OcrEngine(application)
    private val categoryStore = CategoryStore(application)
    private val themeStore = ThemeStore(application)
    private val widgetStore = WidgetStore(application)
    private val balanceStore = BalanceStore(application)

    /** 自定义分类（type -> 名称列表），用户可自行增删 */
    private val _customCategories = MutableStateFlow<Map<String, List<String>>>(
        mapOf(
            TxType.EXPENSE to categoryStore.load(TxType.EXPENSE),
            TxType.INCOME to categoryStore.load(TxType.INCOME),
        )
    )
    val customCategories: StateFlow<Map<String, List<String>>> = _customCategories.asStateFlow()

    /** 分类完整显示顺序（内置 + 自定义，用户可排序）；缺省为内置顺序 + 添加顺序 */
    private val _categoryOrder = MutableStateFlow<Map<String, List<String>>>(
        mapOf(
            TxType.EXPENSE to (categoryStore.loadOrder(TxType.EXPENSE)
                ?: (Categories.names(TxType.EXPENSE) + categoryStore.load(TxType.EXPENSE))),
            TxType.INCOME to (categoryStore.loadOrder(TxType.INCOME)
                ?: (Categories.names(TxType.INCOME) + categoryStore.load(TxType.INCOME))),
        )
    )
    val categoryOrder: StateFlow<Map<String, List<String>>> = _categoryOrder.asStateFlow()

    /** 分类图标覆盖（name -> iconKey），设置页实时显示 */
    private val _iconOverrides = MutableStateFlow<Map<String, String>>(categoryStore.loadIcons())
    val iconOverrides: StateFlow<Map<String, String>> = _iconOverrides.asStateFlow()

    init {
        CategoryPrefs.iconOverrides = _iconOverrides.value
    }

    /** 主题模式（预设色 / 莫奈取色） */
    private val _themeMode = MutableStateFlow(themeStore.load())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    /** 桌面小组件总览周期（日/周/月） */
    private val _widgetPeriod = MutableStateFlow(widgetStore.loadPeriod())
    val widgetPeriod: StateFlow<WidgetPeriod> = _widgetPeriod.asStateFlow()

    /** 用户手动设置的基准资金（金额, 设置时间戳）；null = 未设置，结余按账单收支计算 */
    private val _manualBalance = MutableStateFlow<Pair<Double, Long>?>(balanceStore.load())
    val manualBalance: StateFlow<Pair<Double, Long>?> = _manualBalance.asStateFlow()

    /** 用户手动设置的基准预算（金额, 设置时间戳）；null = 未设置，按账单收支计算 */
    private val _manualBudget = MutableStateFlow<Pair<Double, Long>?>(balanceStore.loadBudget())
    val manualBudget: StateFlow<Pair<Double, Long>?> = _manualBudget.asStateFlow()

    /** 全部流水（按时间倒序），首页/总览实时刷新 */
    val transactions: StateFlow<List<TransactionEntity>> =
        repository.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ocrState = MutableStateFlow<OcrState>(OcrState.Idle)
    val ocrState: StateFlow<OcrState> = _ocrState

    /** 对多张图片逐一 OCR 并合并解析结果（单笔 + 多行明细） */
    fun processImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _ocrState.value = runOcrBatch(uris.size) { index -> ocrEngine.recognize(uris[index]) }
        }
    }

    /**
     * 处理微信账单自动导入截取的临时长图（本地文件）：
     * 逐屏 OCR 合并 → 跨屏去重 → 重复检测 → 发布结果；
     * 识别完成后立即删除临时截图，并把 App 带回前台显示结果。
     */
    fun processCapturedImages(paths: List<String>, alipay: Boolean = false) {
        if (paths.isEmpty()) return
        viewModelScope.launch {
            try {
                _ocrState.value = runOcrBatch(paths.size, alipay = alipay) { index -> ocrEngine.recognizeFile(paths[index]) }
            } finally {
                // 解析器/存储发生意外时也必须释放截帧并结束对应导入状态，
                // 否则可能遗留悬浮窗、临时图片或一直显示「正在识别」。
                deleteCapturedFiles(paths)
                if (alipay) AlipayImportCoordinator.onOcrFinished() else WechatImportCoordinator.onOcrFinished()
                bringBackToAdd()
            }
        }
    }

    /** 通用 OCR 批处理：逐张识别 + 解析 + 退款成对 + 跨图去重 + 与已导入账目判重 */
    private suspend fun runOcrBatch(
        total: Int,
        alipay: Boolean = false,
        recognize: suspend (Int) -> String,
    ): OcrState.Result {
        _ocrState.value = OcrState.Processing(0, total)
        val infos = mutableListOf<ExtractedInfo>()
        val allItems = mutableListOf<LineItem>()
        for (index in 0 until total) {
            val text = recognize(index)
            // 仅 DEBUG 构建时打印 OCR 摘要（不出元数据 N 笔/类型），全文落盘已移除——
            // 截图文本含真实商家/金额，不应持久化在设备上。
            if (BuildConfig.DEBUG) {
                android.util.Log.d("SmartLedgerOCR", "ocr ${index + 1}/$total len=${text.length} head=${text.take(200)}")
            }
            val info = ReceiptParser.parse(text)
            val items = ReceiptParser.parseLineItems(text, info.type, alipay = alipay)
            android.util.Log.d("SmartLedgerOCR", "parseLineItems count = ${items.size}, type = ${info.type}")
            items.forEach {
                android.util.Log.d("SmartLedgerOCR", "ITEM name=[${it.name}] amount=${it.amount} category=${it.category} type=${it.type}")
            }
            infos.add(info)
            allItems.addAll(items)
            _ocrState.value = OcrState.Processing(index + 1, total)
        }

        // 先丢弃无时间戳的误读残留，再退款成对 + 跨屏去重 + 去「滑过头滚进更早月份」+ 重复检测。
        // 顺序：dropUntimedGhosts 必须在 expandRefunds 之前（退款成对会删掉同名真身）；
        // dropOvershoot 必须在 dedupeOverlapping 之后（去重时无时间戳条目会被当作同一笔合并，先过滤会误删）。
        // dropOvershoot 只做「跨月过滤」：目标月=最新账目所在月，丢弃更早月份；同月（含月初月尾）全部保留，
        // 不再按「最后一帧日期」做同月截断（那会误删合法的月末账目，导致「识别少了」）。
        val cleaned = ReceiptParser.dropUntimedGhosts(allItems)
        val expanded = expandRefunds(cleaned)
        val deduped = ReceiptParser.dedupeOverlapping(expanded, alipay = alipay)
        val withinSettle = ReceiptParser.dropOvershoot(deduped)
        val matches = findDuplicates(withinSettle, repository.getAll())
        return OcrState.Result(merge(infos), withinSettle, matches)
    }

    /** 删除微信自动导入的临时截图（导入完成后调用） */
    private fun deleteCapturedFiles(paths: List<String>) {
        paths.forEach { path -> runCatching { java.io.File(path).delete() } }
    }

    /** OCR 完成后把 App 带回前台并打开「记一笔」页显示识别结果 */
    private fun bringBackToAdd() {
        val app = getApplication<Application>()
        runCatching {
            val intent = Intent(app, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(MainActivity.EXTRA_OPEN_ADD, true)
            }
            app.startActivity(intent)
        }
    }

    fun clearOcr() {
        _ocrState.value = OcrState.Idle
    }

    /** 多张图片结果合并：取最可信的金额/商家/归类，取最早时间 */
    private fun merge(infos: List<ExtractedInfo>): ExtractedInfo {
        if (infos.isEmpty()) return ExtractedInfo()
        val first = infos.first()
        val amount = infos.firstOrNull { it.amount != null }?.amount
        val merchant = infos.firstOrNull { it.merchant != null }?.merchant
        val category = infos.firstOrNull { it.category != null }?.category
        val timestamp = infos.mapNotNull { it.timestamp }.minOrNull()
        val type = if (infos.any { it.type == TxType.INCOME }) TxType.INCOME else first.type
        return ExtractedInfo(
            type = type,
            amount = amount,
            merchant = merchant,
            category = category,
            timestamp = timestamp,
            rawText = infos.joinToString("\n---- 下一张 ----\n") { it.rawText },
        )
    }

    fun saveTransaction(
        type: String,
        amount: Double,
        category: String,
        merchant: String,
        timestamp: Long,
        note: String,
        imageUri: String?,
        source: String,
        onSaved: () -> Unit = {},
    ) {
        viewModelScope.launch {
            repository.insert(
                TransactionEntity(
                    type = type,
                    amount = amount,
                    category = category,
                    merchant = merchant,
                    timestamp = timestamp,
                    createdAt = System.currentTimeMillis(),
                    note = note,
                    imageUri = imageUri,
                    source = source,
                )
            )
            onSaved()
            WidgetUpdater.update(getApplication<Application>())
        }
    }

    fun deleteTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            repository.delete(transaction)
            WidgetUpdater.update(getApplication<Application>())
        }
    }

    /**
     * 批量保存多笔明细（一张图多笔记账）。
     *
     * [duplicateAction] 决定遇到与已导入交易重复的条目时如何处理：
     *  - SKIP_DUPLICATES：跳过重复条目，只记录不重复的部分；
     *  - REPLACE_DUPLICATES：先删除匹配到的已导入交易，再插入本次全部条目；
     *  - KEEP_ALL：不判重，全部插入。
     * [onSaved] 回调返回实际保存的条数（跳过重复时可能少于入参数）。
     */
    fun saveTransactions(
        items: List<LineItem>,
        fallbackTimestamp: Long,
        imageUri: String?,
        source: String,
        duplicateAction: DuplicateAction = DuplicateAction.KEEP_ALL,
        onSaved: (Int) -> Unit = {},
    ) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val existing = repository.getAll()
            val toInsert = when (duplicateAction) {
                DuplicateAction.REPLACE_DUPLICATES -> {
                    val toDelete = items.flatMap { item -> existing.filter { isDuplicate(item, it) } }.distinct()
                    if (toDelete.isNotEmpty()) repository.deleteAll(toDelete)
                    items
                }
                DuplicateAction.SKIP_DUPLICATES ->
                    items.filter { item -> existing.none { isDuplicate(item, it) } }
                DuplicateAction.KEEP_ALL -> items
            }
            // 多笔导入使用 Room 的批量插入，避免每笔一次数据库事务/调度往返。
            // 保留每条独立 createdAt 的原语义，避免改变总金额/预算的入账时间判定。
            repository.insertAll(
                toInsert.map { item ->
                    TransactionEntity(
                        type = item.type,
                        amount = item.amount,
                        category = item.category,
                        merchant = item.name,
                        timestamp = item.timestamp ?: fallbackTimestamp,
                        createdAt = System.currentTimeMillis(),
                        note = if (item.refunded) "已全额退款" else "",
                        imageUri = imageUri,
                        source = source,
                    )
                },
            )
            onSaved(toInsert.size)
            WidgetUpdater.update(getApplication<Application>())
        }
    }

    /** 找出 [items] 中与已导入交易重复的条目：下标 -> 匹配到的已导入交易 */
    private fun findDuplicates(
        items: List<LineItem>,
        existing: List<TransactionEntity>,
    ): Map<Int, List<TransactionEntity>> {
        val result = mutableMapOf<Int, List<TransactionEntity>>()
        items.forEachIndexed { index, item ->
            val matches = existing.filter { isDuplicate(item, it) }
            if (matches.isNotEmpty()) result[index] = matches
        }
        return result
    }

    /** 是否重复（按用户要求简化）：金额相同 + 支付时间同年同月同日同分同秒（精确到秒）
     * 即判重复，不比较类型/商家名。见 [ReceiptParser.isSameBill]。 */
    private fun isDuplicate(item: LineItem, t: TransactionEntity): Boolean =
        ReceiptParser.isSameBill(item.amount, item.timestamp, t.amount, t.timestamp)

    /**
     * 退款抵扣：对每条「已全额退款」的支出，去掉 OCR 识别出的、商家名对不上的退款收入，
     * 改补一条「退款·原商家」的等额收入，让「支出 + 退款」成对出现、净额为 0。
     */
    private fun expandRefunds(items: List<LineItem>): List<LineItem> {
        val refunded = items.filter { it.refunded && it.type == TxType.EXPENSE }
        if (refunded.isEmpty()) return items

        // 已全额退款支出的金额集合：OCR 识别出的同金额收入即为其退款流水，将被替换
        val refundAmounts = refunded.map { it.amount }

        val out = mutableListOf<LineItem>()
        for (item in items) {
            if (item.type == TxType.INCOME && refundAmounts.any { abs(it - item.amount) < 0.005 }) {
                continue // 跳过原退款收入，避免与下面生成的「退款·原商家」重复
            }
            out.add(item)
        }
        for (item in refunded) {
            out.add(
                LineItem(
                    name = "退款·${item.name}",
                    amount = item.amount,
                    category = "其他收入",
                    type = TxType.INCOME,
                    timestamp = item.timestamp,
                )
            )
        }
        // 按时间重新排序，让「退款·原商家」紧贴被退款的那笔支出，成对显示
        return out.sortedByDescending { it.timestamp ?: 0L }
    }

    /** 批量删除选中的账目 */
    fun deleteTransactions(transactions: List<TransactionEntity>) {
        if (transactions.isEmpty()) return
        viewModelScope.launch {
            repository.deleteAll(transactions)
            WidgetUpdater.update(getApplication<Application>())
        }
    }

    fun updateTransaction(transaction: TransactionEntity) {
        viewModelScope.launch {
            repository.update(transaction)
            WidgetUpdater.update(getApplication<Application>())
        }
    }

    /** 新增自定义分类（忽略空名、内置名与重复名）；可选同时设置图标 */
    fun addCustomCategory(type: String, name: String, iconKey: String? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        if (trimmed in Categories.names(type)) return
        val current = _customCategories.value[type].orEmpty()
        if (trimmed in current) return
        val updated = current + trimmed
        _customCategories.value = _customCategories.value + (type to updated)
        categoryStore.save(type, updated)

        // 追加到分类顺序末尾
        val order = _categoryOrder.value[type].orEmpty() + trimmed
        _categoryOrder.value = _categoryOrder.value + (type to order)
        categoryStore.saveOrder(type, order)

        if (iconKey != null) setCategoryIcon(trimmed, iconKey)
    }

    /** 删除一个自定义分类 */
    fun removeCustomCategory(type: String, name: String) {
        val current = _customCategories.value[type].orEmpty()
        if (name !in current) return
        val updated = current - name
        _customCategories.value = _customCategories.value + (type to updated)
        categoryStore.save(type, updated)

        val order = _categoryOrder.value[type].orEmpty() - name
        _categoryOrder.value = _categoryOrder.value + (type to order)
        categoryStore.saveOrder(type, order)
    }

    /** 移动某分类到新下标（排序用，from/to 均需在范围内） */
    fun moveCategory(type: String, fromIndex: Int, toIndex: Int) {
        val list = _categoryOrder.value[type].orEmpty().toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices || fromIndex == toIndex) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        _categoryOrder.value = _categoryOrder.value + (type to list)
        categoryStore.saveOrder(type, list)
    }

    /** 重命名自定义分类（同步迁移图标与既有账目） */
    fun renameCustomCategory(type: String, oldName: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == oldName) return
        if (trimmed in Categories.names(type)) return
        val current = _customCategories.value[type].orEmpty()
        if (trimmed in current) return
        val idx = current.indexOf(oldName)
        if (idx < 0) return
        val updatedList = current.toMutableList().also { it[idx] = trimmed }
        _customCategories.value = _customCategories.value + (type to updatedList)
        categoryStore.save(type, updatedList)

        // 顺序里同步重命名
        val order = _categoryOrder.value[type].orEmpty().map { if (it == oldName) trimmed else it }
        _categoryOrder.value = _categoryOrder.value + (type to order)
        categoryStore.saveOrder(type, order)

        val icons = _iconOverrides.value
        if (oldName in icons) {
            val newIcons = icons - oldName + (trimmed to icons.getValue(oldName))
            _iconOverrides.value = newIcons
            CategoryPrefs.iconOverrides = newIcons
            categoryStore.saveIcons(newIcons)
        }
        viewModelScope.launch { repository.updateCategoryName(oldName, trimmed) }
    }

    /** 设置某个分类的图标（内置与自定义均可） */
    fun setCategoryIcon(name: String, iconKey: String) {
        val updated = _iconOverrides.value + (name to iconKey)
        _iconOverrides.value = updated
        CategoryPrefs.iconOverrides = updated
        categoryStore.saveIcons(updated)
    }

    /** 切换主题 */
    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        themeStore.save(mode)
    }

    /** 切换桌面小组件总览周期 */
    fun setWidgetPeriod(period: WidgetPeriod) {
        _widgetPeriod.value = period
        widgetStore.savePeriod(period)
        WidgetUpdater.update(getApplication<Application>(), period)
    }

    /**
     * 设置用户当前资金（总资产）。
     * 用户设置优先级最高：设置时刻之前的账单不再影响结余，
     * 之后导入的账单在资金基础上正常加减。
     */
    fun setManualBalance(amount: Double) {
        val ts = System.currentTimeMillis()
        _manualBalance.value = amount to ts
        balanceStore.save(amount, ts)
    }

    /**
     * 设置用户当前预算。
     * 与总金额逻辑相同：设置时刻之前的账单不再影响预算，
     * 之后导入的账单在预算基础上正常加减。
     */
    fun setManualBudget(amount: Double) {
        val ts = System.currentTimeMillis()
        _manualBudget.value = amount to ts
        balanceStore.saveBudget(amount, ts)
    }

    /** 备份所有账目到指定 uri（JSON），返回备份条数 */
    suspend fun backupTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val transactions = repository.getAll()
        val array = JSONArray()
        transactions.forEach { t ->
            array.put(
                JSONObject().apply {
                    put("type", t.type)
                    put("amount", t.amount)
                    put("category", t.category)
                    put("merchant", t.merchant)
                    put("timestamp", t.timestamp)
                    put("createdAt", t.createdAt)
                    put("note", t.note)
                    put("imageUri", t.imageUri ?: JSONObject.NULL)
                    put("source", t.source)
                }
            )
        }
        val json = array.toString(2)
        val out = getApplication<Application>().contentResolver.openOutputStream(uri)
            ?: throw java.io.IOException("无法打开输出文件")
        out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        transactions.size
    }

    /** 从备份文件恢复：清空当前账目后写入备份内容，返回恢复条数 */
    suspend fun restoreFrom(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = getApplication<Application>().contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        } ?: throw java.io.IOException("无法读取备份文件")
        val array = JSONArray(text)
        val transactions = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            TransactionEntity(
                type = obj.optString("type", TxType.EXPENSE),
                amount = obj.optDouble("amount", 0.0),
                category = obj.optString("category", "其他支出"),
                merchant = obj.optString("merchant", ""),
                timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                // 老备份可能没有 createdAt：回退为 timestamp（迁移后默认 0 的旧数据保持 0）
                createdAt = obj.optLong("createdAt", obj.optLong("timestamp", 0L)),
                note = obj.optString("note", ""),
                imageUri = if (obj.isNull("imageUri")) null else obj.optString("imageUri"),
                source = obj.optString("source", TxSource.MANUAL),
            )
        }
        repository.clearAll()
        repository.insertAll(transactions)
        WidgetUpdater.update(getApplication<Application>())
        transactions.size
    }
}
