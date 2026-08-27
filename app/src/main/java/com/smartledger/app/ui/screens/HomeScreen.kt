package com.smartledger.app.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartledger.app.data.TxType
import com.smartledger.app.ui.AppViewModel
import com.smartledger.app.ui.components.SummaryCards
import com.smartledger.app.ui.components.TransactionRow
import com.smartledger.app.ui.theme.Dimens
import com.smartledger.app.util.Format
import java.time.LocalDate
import java.time.ZoneId

/** 首页：本月收支卡片 + 搜索/筛选 + 全部流水 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: AppViewModel,
    onAdd: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transactions by viewModel.transactions.collectAsState()
    val categoryOrder by viewModel.categoryOrder.collectAsState()

    val zone = ZoneId.systemDefault()
    val monthStart = LocalDate.now().withDayOfMonth(1)
        .atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEnd = LocalDate.now().plusMonths(1).withDayOfMonth(1)
        .atStartOfDay(zone).toInstant().toEpochMilli()

    val monthTransactions = remember(transactions, monthStart, monthEnd) {
        transactions.filter { it.timestamp in monthStart until monthEnd }
    }
    val monthExpense = remember(monthTransactions) {
        monthTransactions.filter { it.type == TxType.EXPENSE }.sumOf { it.amount }
    }
    val monthIncome = remember(monthTransactions) {
        monthTransactions.filter { it.type == TxType.INCOME }.sumOf { it.amount }
    }

    // ---------- 搜索 / 筛选状态 ----------
    var searchText by rememberSaveable { mutableStateOf("") }
    var filterCategory by rememberSaveable { mutableStateOf<String?>(null) }   // null = 全部分类
    var dateMode by rememberSaveable { mutableStateOf(0) }                     // 0 不限 / 1 单日 / 2 时间段
    var dayStart by rememberSaveable { mutableStateOf(-1L) }                   // 本地当天 00:00（-1 未选）
    var rangeStart by rememberSaveable { mutableStateOf(-1L) }
    var rangeEnd by rememberSaveable { mutableStateOf(-1L) }
    var showFilterPanel by rememberSaveable { mutableStateOf(false) }
    var datePickerTarget by remember { mutableStateOf<String?>(null) }         // day / start / end

    val filterCategories = remember(categoryOrder) {
        (categoryOrder[TxType.EXPENSE].orEmpty() + categoryOrder[TxType.INCOME].orEmpty()).distinct()
    }

    val filtered = remember(transactions, searchText, filterCategory, dateMode, dayStart, rangeStart, rangeEnd) {
        val query = searchText.trim()
        transactions.filter { tx ->
            val matchSearch = query.isEmpty() ||
                tx.merchant.contains(query, ignoreCase = true) ||
                tx.note.contains(query, ignoreCase = true)
            val matchCategory = filterCategory == null || tx.category == filterCategory
            val matchDate = when (dateMode) {
                1 -> dayStart >= 0 && tx.timestamp in dayStart until dayStart + Format.DAY_MS
                2 -> if (rangeStart >= 0 && rangeEnd >= 0) {
                    tx.timestamp in rangeStart until rangeEnd + Format.DAY_MS
                } else true
                else -> true
            }
            matchSearch && matchCategory && matchDate
        }
    }

    val hasFilter = searchText.isNotBlank() || filterCategory != null || dateMode != 0

    // ---------- 总金额 / 预算（结余） ----------
    val manualBalance by viewModel.manualBalance.collectAsState()
    val manualBudget by viewModel.manualBudget.collectAsState()

    /** 基准金额（含设置时间戳）→ 当前显示值：
     * 设置了金额则 = 金额 + 设置时刻**之后进入系统**（导入/添加）的账单收支；
     * 未设置则 = 全部账单收支。
     * 判断依据是 createdAt（账单进入系统的时间）而非交易时间戳——用户设置后导入的
     * 历史账单（交易时间早于设置）也应计入。 */
    fun balanceValue(base: Pair<Double, Long>?): Double {
        if (base == null) {
            // 未设置金额：结余 = 全部账单收支
            return transactions.sumOf { if (it.type == TxType.INCOME) it.amount else -it.amount }
        }
        // 已设置金额：结余 = 金额 + 设置之后进入系统的账单收支
        return base.first + transactions.filter { it.createdAt > base.second }
            .sumOf { if (it.type == TxType.INCOME) it.amount else -it.amount }
    }
    val totalBalance = remember(transactions, manualBalance) { balanceValue(manualBalance) }
    val budgetBalance = remember(transactions, manualBudget) { balanceValue(manualBudget) }

    var showBalanceDialog by rememberSaveable { mutableStateOf(false) }
    var balanceInput by rememberSaveable { mutableStateOf("") }
    var showBudgetDialog by rememberSaveable { mutableStateOf(false) }
    var budgetInput by rememberSaveable { mutableStateOf("") }

    // 多选删除状态
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateOf(setOf<Long>()) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimens.pagePadding,
            end = Dimens.pagePadding,
            top = Dimens.lg,
            bottom = Dimens.xxl,
        ),
        verticalArrangement = Arrangement.spacedBy(Dimens.sectionSpacing),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = Format.greeting(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("M月d日 EEEE", java.util.Locale.CHINA)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ---------- 结余 hero 卡：大金额 + 预算一行（点两处分别编辑） ----------
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(Dimens.cardPadding)) {
                    // 当前结余（总金额）：主数字，点击编辑
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                balanceInput = if (manualBalance != null) {
                                    Format.money(manualBalance!!.first).replace(",", "")
                                } else ""
                                showBalanceDialog = true
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "当前结余",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "¥${Format.money(totalBalance)}",
                                style = MaterialTheme.typography.displayMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "修改总金额",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Spacer(Modifier.height(Dimens.md))
                    // 预算：次要一行，点击编辑
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                budgetInput = if (manualBudget != null) {
                                    Format.money(manualBudget!!.first).replace(",", "")
                                } else ""
                                showBudgetDialog = true
                            }
                            .padding(vertical = Dimens.xs, horizontal = Dimens.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(Dimens.sm))
                        Text(
                            text = "本月预算",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "¥${Format.money(budgetBalance)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(Dimens.sm))
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = "修改预算",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }

        // ---------- 本月账单概览（中性卡，与 hero 卡形成层次对比） ----------
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(Dimens.cardPadding)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${LocalDate.now().monthValue} 月账单",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(Modifier.height(Dimens.md))
                    SummaryCards(expense = monthExpense, income = monthIncome)
                }
            }
        }

        // ---------- 搜索 + 筛选 ----------
        item {
            Column {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索账单标题 / 备注") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (searchText.isNotEmpty()) {
                            IconButton(onClick = { searchText = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "清除搜索")
                            }
                        }
                    },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = showFilterPanel,
                        onClick = { showFilterPanel = !showFilterPanel },
                        label = { Text(if (hasFilter) "筛选（已启用）" else "筛选") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.FilterList,
                                contentDescription = null,
                                modifier = Modifier.width(16.dp),
                            )
                        },
                    )
                    if (hasFilter) {
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            searchText = ""
                            filterCategory = null
                            dateMode = 0
                            dayStart = -1L
                            rangeStart = -1L
                            rangeEnd = -1L
                        }) { Text("清除筛选") }
                    }
                }
            }
        }

        if (showFilterPanel) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("按分类筛选", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = filterCategory == null,
                                onClick = { filterCategory = null },
                                label = { Text("全部分类") },
                            )
                            filterCategories.forEach { name ->
                                FilterChip(
                                    selected = filterCategory == name,
                                    onClick = { filterCategory = name },
                                    label = { Text(name) },
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("按日期筛选", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(selected = dateMode == 0, onClick = { dateMode = 0 }, label = { Text("不限") })
                            FilterChip(selected = dateMode == 1, onClick = { dateMode = 1 }, label = { Text("单日") })
                            FilterChip(selected = dateMode == 2, onClick = { dateMode = 2 }, label = { Text("时间段") })
                        }
                        Spacer(Modifier.height(8.dp))
                        when (dateMode) {
                            1 -> OutlinedButton(onClick = { datePickerTarget = "day" }) {
                                Text(if (dayStart >= 0) Format.date(dayStart) else "选择日期")
                            }
                            2 -> Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { datePickerTarget = "start" }) {
                                    Text(if (rangeStart >= 0) Format.date(rangeStart) else "开始日期")
                                }
                                Spacer(Modifier.width(8.dp))
                                Text("~", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { datePickerTarget = "end" }) {
                                    Text(if (rangeEnd >= 0) Format.date(rangeEnd) else "结束日期")
                                }
                            }
                        }
                    }
                }
            }
        }

        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (hasFilter) "筛选结果（${filtered.size}）" else "全部记录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    if (selectionMode) {
                        TextButton(onClick = {
                            selectionMode = false
                            selectedIds.value = emptySet()
                        }) { Text("取消") }
                    } else {
                        TextButton(onClick = {
                            selectionMode = true
                            selectedIds.value = emptySet()
                        }) { Text("多选") }
                    }
                }
                if (selectionMode) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = {
                            selectedIds.value = filtered.map { it.id }.toSet()
                        }) { Text("全选") }
                        TextButton(onClick = {
                            selectedIds.value = emptySet()
                        }) { Text("全不选") }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = {
                                viewModel.deleteTransactions(filtered.filter { it.id in selectedIds.value })
                                selectionMode = false
                                selectedIds.value = emptySet()
                            },
                            enabled = selectedIds.value.isNotEmpty(),
                        ) {
                            Text("删除 ${selectedIds.value.size} 条")
                        }
                    }
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 60.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ReceiptLong,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.height(56.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (transactions.isEmpty()) "还没有记录，点「记一笔」拍照入账吧"
                            else "没有符合筛选条件的记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(filtered, key = { it.id }) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    selectionMode = selectionMode,
                    selected = transaction.id in selectedIds.value,
                    onToggleSelect = {
                        selectedIds.value = if (transaction.id in selectedIds.value) {
                            selectedIds.value - transaction.id
                        } else {
                            selectedIds.value + transaction.id
                        }
                    },
                    onDelete = if (selectionMode) null else ({ viewModel.deleteTransaction(transaction) }),
                    onEdit = if (selectionMode) null else ({ onEditTransaction(transaction.id) }),
                )
            }
        }
    }

    // ---------- 日期选择对话框 ----------
    datePickerTarget?.let { target ->
        val initial = when (target) {
            "day" -> if (dayStart >= 0) Format.utcMidnight(dayStart) else null
            "start" -> if (rangeStart >= 0) Format.utcMidnight(rangeStart) else null
            else -> if (rangeEnd >= 0) Format.utcMidnight(rangeEnd) else null
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { datePickerTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { selected ->
                        val localStart = Format.localDayStart(selected)
                        when (target) {
                            "day" -> dayStart = localStart
                            "start" -> rangeStart = localStart
                            "end" -> rangeEnd = localStart
                        }
                    }
                    datePickerTarget = null
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { datePickerTarget = null }) { Text("取消") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }
    // ---------- 修改总金额对话框 ----------
    if (showBalanceDialog) {
        BalanceEditDialog(
            title = "设置当前资金",
            hint = "目前拥有的资金（元）",
            note = "设置后结余以此为准：其后的账单收入/支出在资金基础上自动增减；" +
                "设置之前的账单不再影响结余。",
            input = balanceInput,
            onInputChange = { balanceInput = it },
            onConfirm = { amount ->
                viewModel.setManualBalance(amount)
                showBalanceDialog = false
            },
            onDismiss = { showBalanceDialog = false },
        )
    }
    // ---------- 修改预算对话框 ----------
    if (showBudgetDialog) {
        BalanceEditDialog(
            title = "设置预算",
            hint = "预算金额（元）",
            note = "设置后预算以此为准：其后的账单收入/支出在预算基础上自动增减；" +
                "设置之前的账单不再影响预算。",
            input = budgetInput,
            onInputChange = { budgetInput = it },
            onConfirm = { amount ->
                viewModel.setManualBudget(amount)
                showBudgetDialog = false
            },
            onDismiss = { showBudgetDialog = false },
        )
    }
}

/** 首页「总金额 / 预算」共用的金额设置对话框 */
@Composable
private fun BalanceEditDialog(
    title: String,
    hint: String,
    note: String,
    input: String,
    onInputChange: (String) -> Unit,
    onConfirm: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { v -> onInputChange(v.filter { c -> c.isDigit() || c == '.' }) },
                    label = { Text(hint) },
                    prefix = { Text("¥") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val amount = input.replace(",", "").toDoubleOrNull()
                    if (amount != null && amount >= 0) {
                        onConfirm(amount)
                    }
                },
                enabled = (input.replace(",", "").toDoubleOrNull() ?: -1.0) >= 0,
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
