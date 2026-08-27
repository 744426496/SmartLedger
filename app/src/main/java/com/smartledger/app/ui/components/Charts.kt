package com.smartledger.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smartledger.app.data.Category
import com.smartledger.app.ui.theme.Dimens
import com.smartledger.app.ui.theme.ExpenseColor
import com.smartledger.app.ui.theme.IncomeColor
import com.smartledger.app.util.Format

/** 柱状图数据点 */
data class BarDatum(
    val label: String,
    val value: Double,
    val selected: Boolean = false,
)

/**
 * 自绘柱状图（无第三方图表依赖），支持点击选择某根柱子。
 */
@Composable
fun ExpenseBarChart(
    data: List<BarDatum>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 190.dp,
    onBarClick: (Int) -> Unit = {},
) {
    val textMeasurer = rememberTextMeasurer()
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = onSurfaceVariant)
    val valueStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = primary)
    val maxValue = (data.maxOfOrNull { it.value } ?: 0.0).coerceAtLeast(1.0)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(chartHeight)
            .pointerInput(data) {
                detectTapGestures { offset ->
                    if (data.isEmpty()) return@detectTapGestures
                    val slot = size.width / data.size
                    val index = (offset.x / slot).toInt().coerceIn(0, data.size - 1)
                    onBarClick(index)
                }
            }
    ) {
        if (data.isEmpty()) return@Canvas

        val gap = 12.dp.toPx()
        val slotWidth = size.width / data.size
        val barWidth = (slotWidth - gap).coerceAtLeast(4f)
        val topPadding = 26.dp.toPx()
        val bottomPadding = 22.dp.toPx()
        val chartArea = size.height - topPadding - bottomPadding

        // 标签过多时跳着显示，避免日期重叠
        val maxLabelWidth = data.maxOf { textMeasurer.measure(it.label, labelStyle).size.width }
        val labelStep = if (slotWidth > 0f) {
            ((maxLabelWidth / slotWidth).toInt() + 1).coerceAtLeast(1)
        } else 1

        data.forEachIndexed { index, datum ->
            val centerX = slotWidth * index + slotWidth / 2
            val barHeight =
                if (datum.value > 0) {
                    (datum.value / maxValue * chartArea.toDouble()).toFloat().coerceAtLeast(3f)
                } else {
                    0f
                }
            val top = size.height - bottomPadding - barHeight
            val barColor = when {
                datum.selected -> ExpenseColor
                datum.value > 0 -> primary
                else -> Color(0xFFDDDDDD)
            }

            // 柱体
            drawRoundRect(
                color = barColor.copy(alpha = if (datum.selected) 1f else 0.85f),
                topLeft = Offset(centerX - barWidth / 2, top),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(6f, 6f),
            )

            // 数值标签
            if (datum.value > 0) {
                val textLayout = textMeasurer.measure(
                    text = if (datum.value >= 1000) "${(datum.value / 1000).toInt()}k" else "${datum.value.toInt()}",
                    style = valueStyle,
                )
                drawText(
                    textLayoutResult = textLayout,
                    topLeft = Offset(centerX - textLayout.size.width / 2, top - textLayout.size.height - 4.dp.toPx()),
                )
            }

            // 底部标签（间隔显示，避免重叠）
            if (index % labelStep == 0) {
                val labelLayout = textMeasurer.measure(datum.label, labelStyle)
                drawText(
                    textLayoutResult = labelLayout,
                    topLeft = Offset(centerX - labelLayout.size.width / 2, size.height - labelLayout.size.height),
                )
            }
        }

        // 基准线
        drawLine(
            color = outlineVariant,
            start = Offset(0f, size.height - bottomPadding),
            end = Offset(size.width, size.height - bottomPadding),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/**
 * 分类环形图（donut），中心显示总金额。
 */
@Composable
fun CategoryDonut(
    items: List<Pair<Category, Double>>,
    modifier: Modifier = Modifier,
    size: Dp = 150.dp,
    centerTitle: String = "总支出",
    centerValue: String = "",
) {
    val textMeasurer = rememberTextMeasurer()
    val total = items.sumOf { it.second }
    val onSurface = MaterialTheme.colorScheme.onSurface

    Canvas(modifier.size(size)) {
        val strokeWidth = 26.dp.toPx()
        val arcSize = Size(size.toPx() - strokeWidth, size.toPx() - strokeWidth)
        val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)

        if (total <= 0 || items.isEmpty()) {
            drawArc(
                color = Color(0xFFE0E0E0),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
            )
        } else {
            var startAngle = -90f
            items.forEach { (category, amount) ->
                val sweep = (amount / total * 360.0).toFloat()
                drawArc(
                    color = category.color,
                    startAngle = startAngle,
                    sweepAngle = sweep - 1.5f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                )
                startAngle += sweep
            }
        }

        // 中心文字
        val titleLayout = textMeasurer.measure(
            centerTitle,
            TextStyle(fontSize = 11.sp, color = onSurface.copy(alpha = 0.6f)),
        )
        val valueLayout = textMeasurer.measure(
            centerValue,
            TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = onSurface),
        )
        drawText(
            textLayoutResult = titleLayout,
            topLeft = Offset(center.x - titleLayout.size.width / 2, center.y - titleLayout.size.height - 2.dp.toPx()),
        )
        drawText(
            textLayoutResult = valueLayout,
            topLeft = Offset(center.x - valueLayout.size.width / 2, center.y + 2.dp.toPx()),
        )
    }
}

/** 单行分类占比条：图标 + 名称 + 进度条 + 金额 */
@Composable
fun CategoryBarRow(
    category: Category,
    amount: Double,
    percent: Float,
    modifier: Modifier = Modifier,
) {
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(34.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(34.dp)) {
                drawCircle(color = category.color.copy(alpha = 0.18f))
            }
            Icon(
                imageVector = category.icon,
                contentDescription = category.name,
                tint = category.color,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(category.name, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
            ) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    drawRoundRect(
                        color = surfaceVariant,
                        size = size,
                        cornerRadius = CornerRadius(3f, 3f),
                    )
                    if (percent > 0f) {
                        drawRoundRect(
                            color = category.color,
                            size = Size(size.width * percent, size.height),
                            cornerRadius = CornerRadius(3f, 3f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = Format.money(amount),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 顶部汇总：支出 / 收入 / 结余，三栏等宽，金额加粗、标签弱化 */
@Composable
fun SummaryCards(
    expense: Double,
    income: Double,
    modifier: Modifier = Modifier,
) {
    val balance = income - expense
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.sm),
    ) {
        SummaryCell("支出", expense, ExpenseColor, Modifier.weight(1f))
        SummaryCell("收入", income, IncomeColor, Modifier.weight(1f))
        SummaryCell("结余", balance, MaterialTheme.colorScheme.primary, Modifier.weight(1f))
    }
}

@Composable
private fun SummaryCell(
    title: String,
    value: Double,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(vertical = 2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = Format.money(value),
            style = MaterialTheme.typography.titleSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}
