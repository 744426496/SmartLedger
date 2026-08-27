package com.smartledger.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context

/** 鲸鱼记账桌面小组件（日/周/月总览） */
class LedgerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetUpdater.update(context)
    }

    override fun onEnabled(context: Context) {
        WidgetUpdater.update(context)
    }
}
