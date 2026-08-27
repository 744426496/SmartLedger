package com.smartledger.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartledger.app.data.Categories
import com.smartledger.app.data.TxType
import com.smartledger.app.ui.AppViewModel
import com.smartledger.app.ui.components.BarDatum
import com.smartledger.app.ui.components.CategoryBarRow
import com.smartledger.app.ui.components.CategoryDonut
import com.smartledger.app.ui.components.ExpenseBarChart
import com.smartledger.app.ui.components.SummaryCards
import com.smartledger.app.ui.components.TransactionRow
import com.smartledger.app.ui.theme.Dimens
import com.smartledger.app.util.Format
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private enum class OverviewPeriod(val label: String) {
    DAY("日"), WEEK("周"), MONTH("月"),
}

private data class Bucket(val label: String, val start: Long, val end: Long)

/**
 * 总览：日 / 周 / 月 三个维度看消费趋势。
 * 柱状图点击选择某一天/周/月，下方联动显示分类占比与明细。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val transactions by viewModel.transactions.collectAsState()

    var period by rememberSaveable { mutableStateOf(OverviewPeriod.MONTH) }
    var selectedIndex by rememberSaveable { mutableIntStateOf(-1) }

    val buckets = remember(period) { computeBuckets(period) }
    val expenseByBucket = remember(transactions, buckets) {
        buckets.map { bucket ->
            transactions
                .filter { it.type == TxType.EXPENSE && it.timestamp in bucket.start until bucket.end }
                .sumOf { it.amount }
        }
    }
    val currentIndex = if (selectedIndex in buckets.indices) selectedIndex else buckets.lastIndex
    val currentBucket = buckets[currentIndex]

    val currentTransactions = remember(transactions, currentBucket) {
        transactions
            .filter { it.timestamp in currentBucket.start until currentBucket.end }
            .sortedByDescending { it.timestamp }
    }
    val currentExpense = remember(currentTransactions) {
        currentTransactions.filter { it.type == TxType.EXPENSE }.sumOf { it.amount }
    }
    val currentIncome = remember(currentTransactions) {
        currentTransactions.filter { it.type == TxType.INCOME }.sumOf { it.amount }
    }

    // 当前时间段分类占比
    val expenseByCategory = remember(currentTransactions) {
        currentTransactions
            .filter { it.type == TxType.EXPENSE }
            .groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
    }

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
            Text(
                "总览",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "日 / 周 / 月消费趋势",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 周期切换
        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                OverviewPeriod.entries.forEachIndexed { index, p ->
                    SegmentedButton(
                        selected = period == p,
                        onClick = { period = p },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = OverviewPeriod.entries.size),
                    ) { Text(p.label) }
                }
            }
        }

        // 汇总卡片
        item {
            SummaryCards(expense = currentExpense, income = currentIncome)
        }

        // 趋势柱状图
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(Dimens.cardPadding)) {
                    Text(
                        text = "${period.label}支出趋势",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "点击柱子查看对应${period.label}的明细",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    ExpenseBarChart(
                        data = buckets.mapIndexed { index, bucket ->
                            BarDatum(
                                label = bucket.label,
                                value = expenseByBucket[index],
                                selected = index == currentIndex,
                            )
                        },
                        onBarClick = { index -> selectedIndex = index },
                    )
                }
            }
        }

        // 分类占比
        if (expenseByCategory.isNotEmpty() && currentExpense > 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                ) {
                    Column(modifier = Modifier.padding(Dimens.cardPadding)) {
                        Text(
                            text = "${currentBucket.label}支出分类",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryDonut(
                                items = expenseByCategory.map { (name, amount) ->
                                    Categories.of(name) to amount
                                },
                                size = 140.dp,
                                centerTitle = "总支出",
                                centerValue = Format.money(currentExpense),
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                expenseByCategory.take(4).forEach { (name, amount) ->
                                    CategoryBarRow(
                                        category = Categories.of(name),
                                        amount = amount,
                                        percent = (amount / currentExpense).toFloat(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 明细
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${currentBucket.label}明细",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "共 ${currentTransactions.size} 笔",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (currentTransactions.isEmpty()) {
            item {
                Text(
                    text = "该时间段暂无记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        } else {
            items(currentTransactions, key = { it.id }) { transaction ->
                TransactionRow(
                    transaction = transaction,
                    onDelete = { viewModel.deleteTransaction(transaction) },
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

/** 按周期生成时间桶：[start, end) */
private fun computeBuckets(period: OverviewPeriod): List<Bucket> {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val formatter = DateTimeFormatter.ofPattern("MM-dd")

    fun startOf(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    return when (period) {
        OverviewPeriod.DAY -> (13 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            Bucket(
                label = if (back == 0) "今天" else date.format(formatter),
                start = startOf(date),
                end = startOf(date.plusDays(1)),
            )
        }

        OverviewPeriod.WEEK -> {
            val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            (7 downTo 0).map { back ->
                val monday = thisMonday.minusWeeks(back.toLong())
                Bucket(
                    label = if (back == 0) "本周" else "${monday.format(formatter)}周",
                    start = startOf(monday),
                    end = startOf(monday.plusWeeks(1)),
                )
            }
        }

        OverviewPeriod.MONTH -> {
            val thisMonth = YearMonth.from(today)
            (11 downTo 0).map { back ->
                val month = thisMonth.minusMonths(back.toLong())
                Bucket(
                    label = if (back == 0) "本月" else "${month.monthValue}月",
                    start = startOf(month.atDay(1)),
                    end = startOf(month.plusMonths(1).atDay(1)),
                )
            }
        }
    }
}
