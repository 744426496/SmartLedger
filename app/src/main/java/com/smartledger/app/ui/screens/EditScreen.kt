package com.smartledger.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.smartledger.app.data.Categories
import com.smartledger.app.data.TxType
import com.smartledger.app.ui.AppViewModel
import com.smartledger.app.ui.components.CategorySelector
import com.smartledger.app.ui.theme.Dimens
import com.smartledger.app.util.Format
import kotlinx.coroutines.launch

/**
 * 编辑已导入/录入的一笔账单：修改类型、金额、商家、分类、时间、备注后保存。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditScreen(
    transactionId: Long,
    viewModel: AppViewModel,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val transactions by viewModel.transactions.collectAsState()
    val customCategories by viewModel.customCategories.collectAsState()
    val categoryOrder by viewModel.categoryOrder.collectAsState()
    val transaction = transactions.find { it.id == transactionId }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("编辑账目") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        if (transaction == null) {
            Box(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "未找到该笔账目",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        var type by rememberSaveable { mutableStateOf(transaction.type) }
        var amountText by rememberSaveable { mutableStateOf(Format.money(transaction.amount).replace(",", "")) }
        var merchant by rememberSaveable { mutableStateOf(transaction.merchant) }
        var category by rememberSaveable { mutableStateOf(transaction.category) }
        var note by rememberSaveable { mutableStateOf(transaction.note) }
        var timestamp by rememberSaveable { mutableStateOf(transaction.timestamp) }
        var showDatePicker by rememberSaveable { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Dimens.pagePadding),
        ) {
            // 类型
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

            Spacer(Modifier.height(16.dp))

            // 金额
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

            // 商家
            OutlinedTextField(
                value = merchant,
                onValueChange = { merchant = it },
                label = { Text("商家 / 收款方（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            // 分类
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

            // 时间
            Text("时间", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = Format.dateTime(timestamp),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = { timestamp = System.currentTimeMillis() }) {
                    Text("现在")
                }
                TextButton(onClick = { showDatePicker = true }) {
                    Text("改日期")
                }
            }

            Spacer(Modifier.height(12.dp))

            // 备注
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("备注（可选）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(24.dp))

            // 保存
            val amount = amountText.replace(",", "").toDoubleOrNull()
            Button(
                onClick = {
                    if (amount != null && amount > 0) {
                        scope.launch {
                            viewModel.updateTransaction(
                                transaction.copy(
                                    type = type,
                                    amount = amount,
                                    category = category,
                                    merchant = merchant.trim(),
                                    timestamp = timestamp,
                                    note = note.trim(),
                                )
                            )
                            scope.launch { snackbarHostState.showSnackbar("已保存") }
                            onDone()
                        }
                    }
                },
                enabled = amount != null && amount > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                Text("保存修改", style = MaterialTheme.typography.titleMedium)
            }
        }

        // 日期选择
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
    }
}
