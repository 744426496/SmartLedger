package com.smartledger.app.widget

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** 小组件总览周期：日 / 周 / 月 */
enum class WidgetPeriod(val key: String, val label: String) {
    DAY("day", "今日"),
    WEEK("week", "本周"),
    MONTH("month", "本月");

    /** 返回该周期的 [start, end) 时间范围（epoch 毫秒）与标题 */
    fun range(): Triple<Long, Long, String> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val (start, end) = when (this) {
            DAY -> today to today.plusDays(1)
            WEEK -> {
                val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                monday to monday.plusWeeks(1)
            }
            MONTH -> today.withDayOfMonth(1) to today.plusMonths(1).withDayOfMonth(1)
        }
        return Triple(
            start.atStartOfDay(zone).toInstant().toEpochMilli(),
            end.atStartOfDay(zone).toInstant().toEpochMilli(),
            label,
        )
    }

    companion object {
        fun fromKey(key: String): WidgetPeriod = entries.firstOrNull { it.key == key } ?: MONTH
    }
}
