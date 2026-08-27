package com.smartledger.app.data

import android.content.Context
import com.smartledger.app.widget.WidgetPeriod

/** 小组件设置的本地持久化 */
class WidgetStore(context: Context) {
    private val prefs = context.getSharedPreferences("smartledger_settings", Context.MODE_PRIVATE)

    fun loadPeriod(): WidgetPeriod =
        WidgetPeriod.fromKey(prefs.getString("widget_period", WidgetPeriod.MONTH.key).orEmpty())

    fun savePeriod(period: WidgetPeriod) {
        prefs.edit().putString("widget_period", period.key).apply()
    }
}
