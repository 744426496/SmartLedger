package com.smartledger.app.data

import android.content.Context

/**
 * 用户手动设置的基准金额（「总金额」与「预算」两个分栏）。
 *
 * 规则（用户设置的优先级最高，两个分栏逻辑相同）：
 *  - 设置了金额 → 该栏 = 金额 + 设置时间之后导入账单的收支（收入加、支出减）；
 *  - 未设置金额 → 该栏 = 全部账单收支（收入 - 支出）。
 * 设置时刻之前的账单不再影响该栏。
 */
class BalanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("smartledger_balance", Context.MODE_PRIVATE)

    /** 总金额：(金额, 设置时间戳)；null = 从未设置 */
    fun load(): Pair<Double, Long>? {
        val s = prefs.getString("balance_base", null) ?: return null
        val amount = s.toDoubleOrNull() ?: return null
        return amount to prefs.getLong("balance_base_ts", 0L)
    }

    fun save(amount: Double, timestamp: Long) {
        prefs.edit()
            .putString("balance_base", amount.toString())
            .putLong("balance_base_ts", timestamp)
            .apply()
    }

    /** 预算：(金额, 设置时间戳)；null = 从未设置 */
    fun loadBudget(): Pair<Double, Long>? {
        val s = prefs.getString("budget_base", null) ?: return null
        val amount = s.toDoubleOrNull() ?: return null
        return amount to prefs.getLong("budget_base_ts", 0L)
    }

    fun saveBudget(amount: Double, timestamp: Long) {
        prefs.edit()
            .putString("budget_base", amount.toString())
            .putLong("budget_base_ts", timestamp)
            .apply()
    }
}
