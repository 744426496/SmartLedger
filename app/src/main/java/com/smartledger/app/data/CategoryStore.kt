package com.smartledger.app.data

import android.content.Context

/**
 * 自定义分类的本地持久化（SharedPreferences）。
 * 每类（支出/收入）保存为一行文本，按换行分隔，保留添加顺序。
 */
class CategoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("smartledger_custom_categories", Context.MODE_PRIVATE)

    fun load(type: String): List<String> =
        prefs.getString(key(type), "").orEmpty()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun save(type: String, names: List<String>) {
        prefs.edit().putString(key(type), names.joinToString("\n")).apply()
    }

    /** 分类图标覆盖：name -> iconKey */
    fun loadIcons(): Map<String, String> =
        prefs.getString("icons", "").orEmpty()
            .split("\n")
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0 || i == line.lastIndex) null
                else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()

    fun saveIcons(map: Map<String, String>) {
        prefs.edit().putString("icons", map.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
    }

    /** 完整分类显示顺序（内置 + 自定义，用户可排序）；未自定义过时返回 null */
    fun loadOrder(type: String): List<String>? =
        prefs.getString(orderKey(type), "").orEmpty()
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .takeIf { it.isNotEmpty() }

    fun saveOrder(type: String, names: List<String>) {
        prefs.edit().putString(orderKey(type), names.joinToString("\n")).apply()
    }

    private fun key(type: String): String = if (type == TxType.INCOME) "income" else "expense"

    private fun orderKey(type: String): String =
        if (type == TxType.INCOME) "order_income" else "order_expense"
}
