package com.smartledger.app.data

import android.content.Context
import com.smartledger.app.ui.theme.ThemeMode

/** 主题设置的本地持久化 */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences("smartledger_settings", Context.MODE_PRIVATE)

    fun load(): ThemeMode =
        runCatching { ThemeMode.valueOf(prefs.getString("theme", ThemeMode.GREEN.name).orEmpty()) }
            .getOrDefault(ThemeMode.GREEN)

    fun save(mode: ThemeMode) {
        prefs.edit().putString("theme", mode.name).apply()
    }
}
