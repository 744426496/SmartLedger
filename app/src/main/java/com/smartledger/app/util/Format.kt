package com.smartledger.app.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object Format {

    /** 一天的毫秒数（筛选/统计的时间范围计算用） */
    const val DAY_MS = 86_400_000L

    /** 金额：1234.5 -> "1,234.50"（用 DecimalFormat 风格的 String.format，而非 DateTimeFormatter） */
    fun money(value: Double): String =
        String.format(Locale.CHINA, "%,.2f", value)

    /** 带符号金额：支出 "-12.50" / 收入 "+3,000.00" */
    fun signedMoney(value: Double, isExpense: Boolean): String =
        (if (isExpense) "-" else "+") + money(value)

    fun dateTime(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

    fun date(epochMillis: Long): String =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    /** DatePicker 选中的值（UTC 零点）→ 本地当天 00:00 毫秒 */
    fun localDayStart(utcMidnight: Long): Long =
        Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 本地当天 00:00 毫秒 → DatePicker 期望的 UTC 零点 */
    fun utcMidnight(localStart: Long): Long =
        Instant.ofEpochMilli(localStart).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** DatePicker 选中的日期（UTC 零点）+ 原时间戳的小时分钟 → 新时间戳 */
    fun combineDate(selectedUtcMillis: Long, original: Long): Long {
        val date = Instant.ofEpochMilli(selectedUtcMillis)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
        val originalTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(original), ZoneId.systemDefault())
        return date.atTime(originalTime.hour, originalTime.minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }

    /** 时段问候语 */
    fun greeting(): String {
        val hour = LocalDateTime.now().hour
        return when {
            hour < 6 -> "夜深了"
            hour < 12 -> "早上好"
            hour < 14 -> "中午好"
            hour < 18 -> "下午好"
            else -> "晚上好"
        }
    }
}
