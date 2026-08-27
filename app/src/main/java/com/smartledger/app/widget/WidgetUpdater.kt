package com.smartledger.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.widget.RemoteViews
import com.smartledger.app.MainActivity
import com.smartledger.app.R
import com.smartledger.app.data.AppDatabase
import com.smartledger.app.data.TxType
import com.smartledger.app.data.WidgetStore
import com.smartledger.app.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 刷新桌面小组件：按周期统计支出/收入、绘制近7日柱状图，并支持点击打开应用 */
object WidgetUpdater {

    // 小组件刷新独立于任一 Activity/ViewModel 生命周期；用受监督 scope 替代 GlobalScope，
    // 单次数据库/桌面服务异常不会取消后续刷新，也避免 Delicate API 的无边界协程。
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun update(context: Context, period: WidgetPeriod? = null) {
        val ctx = context.applicationContext
        val p = period ?: WidgetStore(ctx).loadPeriod()
        updateScope.launch {
            runCatching {
                val (start, end, label) = p.range()
                val dao = AppDatabase.get(ctx).transactionDao()
                val expense = dao.sumByTypeBetween(TxType.EXPENSE, start, end)
                val income = dao.sumByTypeBetween(TxType.INCOME, start, end)

                // 跟随系统深色模式：切换背景、文字与图表配色
                val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

                val views = RemoteViews(ctx.packageName, R.layout.widget_ledger)
                views.setInt(
                    R.id.widget_root, "setBackgroundResource",
                    if (night) R.drawable.widget_background_dark else R.drawable.widget_background,
                )
                views.setTextViewText(R.id.widget_title, "${label}总览")
                views.setTextColor(R.id.widget_title, if (night) 0xFFE2E2E2.toInt() else 0xFF333333.toInt())
                val today = LocalDate.now()
                views.setTextViewText(R.id.widget_date, "${today.monthValue}月${today.dayOfMonth}日")
                views.setTextColor(R.id.widget_date, 0xFF9E9E9E.toInt())
                views.setTextViewText(R.id.widget_expense, "支出 ${Format.money(expense)}")
                views.setTextViewText(R.id.widget_income, "收入 ${Format.money(income)}")
                views.setTextColor(R.id.widget_chart_label, if (night) 0xFF9E9E9E.toInt() else 0xFF888888.toInt())
                views.setImageViewBitmap(R.id.widget_chart, buildWeeklyChart(dao, night))

                // 点击整个小组件打开应用
                val intent = Intent(ctx, MainActivity::class.java)
                val pi = PendingIntent.getActivity(
                    ctx,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.widget_root, pi)

                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, LedgerWidgetProvider::class.java))
                ids.forEach { mgr.updateAppWidget(it, views) }
            }
        }
    }

    /** 近7日每日支出柱状图 */
    private suspend fun buildWeeklyChart(dao: com.smartledger.app.data.TransactionDao, dark: Boolean): Bitmap {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val days = (6 downTo 0).map { today.minusDays(it.toLong()) }
        val start = days.first().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = days.last().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // 一次读取后按本地日期分桶：原实现在 7 天循环里反复扫描同一批账目，
        // 这里结果相同，复杂度由约 7n 收敛为 n + 7。
        val expenseByDay = dao.getBetween(start, end)
            .asSequence()
            .filter { it.type == TxType.EXPENSE }
            .groupingBy { Instant.ofEpochMilli(it.timestamp).atZone(zone).toLocalDate() }
            .fold(0.0) { sum, transaction -> sum + transaction.amount }
        val values = days.map { day -> (expenseByDay[day] ?: 0.0).toFloat() }
        return drawBarChart(values, days, dark)
    }

    /**
     * 绘制近7日柱状图：柱子上方标注当日支出金额（0 不标），柱子下方标注日期（M/d）。
     * 720x240、fitCenter 等比缩放，文字不会被拉伸变形；日期加粗加深保证可读。
     */
    private fun drawBarChart(values: List<Float>, days: List<LocalDate>, dark: Boolean): Bitmap {
        val w = 720
        val h = 240
        val topGap = 46f   // 顶部金额文字区
        val bottomGap = 54f // 底部日期文字区
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        val max = values.maxOrNull()?.takeIf { it > 0f } ?: 1f
        val n = values.size.coerceAtLeast(1)
        val slot = w / n.toFloat()
        val barW = slot * 0.5f
        val baseline = h - bottomGap
        val chartArea = baseline - topGap

        // 基线 / 空柱：深色模式用更深的灰
        val baselineColor = if (dark) Color.rgb(0x44, 0x44, 0x44) else Color.rgb(0xE0, 0xE0, 0xE0)
        val emptyColor = if (dark) Color.rgb(0x3A, 0x3A, 0x3A) else Color.rgb(0xEF, 0xEF, 0xEF)
        val amountColor = if (dark) Color.rgb(0xF0, 0xF0, 0xF0) else Color.rgb(0x30, 0x30, 0x30)
        val dateColor = if (dark) Color.rgb(0xC0, 0xC0, 0xC0) else Color.rgb(0x6E, 0x6E, 0x6E)

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = baselineColor
            strokeWidth = 3f
        }
        canvas.drawLine(0f, baseline, w.toFloat(), baseline, basePaint)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        for ((i, v) in values.withIndex()) {
            val centerX = i * slot + slot / 2f
            val bh = if (v > 0f) (v / max) * chartArea else 0f
            val left = i * slot + (slot - barW) / 2f
            val top = baseline - bh
            val right = left + barW
            paint.color = if (v <= 0f) emptyColor else Color.rgb(0xE5, 0x39, 0x35)
            canvas.drawRoundRect(left, top, right, baseline, 10f, 10f, paint)

            // 柱子下方：日期（M/d）加粗
            textPaint.color = dateColor
            textPaint.textSize = 30f
            canvas.drawText("${days[i].monthValue}/${days[i].dayOfMonth}", centerX, baseline + 40f, textPaint)

            // 柱子上方：金额（0 不标；柱太高时改写在柱内）
            if (v > 0f) {
                textPaint.color = amountColor
                textPaint.textSize = 27f
                val label = shortMoney(v)
                if (top >= topGap + 22f) {
                    canvas.drawText(label, centerX, top - 10f, textPaint)
                } else if (bh >= 30f) {
                    canvas.drawText(label, centerX, top + 20f, textPaint)
                }
            }
        }
        return bitmap
    }

    /** 金额紧凑显示：>=1 万 → "1.2万"，否则整数（去小数与千分位） */
    private fun shortMoney(v: Float): String = when {
        v >= 10000f -> "%.1f万".format(v / 10000f).let { if (it.endsWith(".0万")) "${it.dropLast(3)}万" else it }
        v >= 10f -> "%.0f".format(v)
        else -> if (v == v.toInt().toFloat()) "%.0f".format(v) else "%.1f".format(v)
    }
}
