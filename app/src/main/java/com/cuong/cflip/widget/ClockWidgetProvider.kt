package com.cuong.cflip.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cuong.cflip.MainActivity
import com.cuong.cflip.R
import com.cuong.cflip.service.NotificationState
import com.cuong.cflip.ui.readBatteryPercent

/**
 * Cover Clock & Battery widget (new in 2.5.0): standard Android widget with a
 * self-updating TextClock, date, battery percent and notification count.
 * Tapping it opens CFlip's cover UI at the Home tab. Battery is read from
 * BatteryManager (same helper as the cover UI); -1 means unknown.
 */
class ClockWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pct = readBatteryPercent(context)
        val batteryText = if (pct >= 0) "Pin $pct%" else "Pin —"
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_clock)
            views.setTextViewText(R.id.clock_battery, batteryText)
            views.setTextViewText(
                R.id.clock_notif,
                "🔔 ${NotificationState.active.value.size} thông báo",
            )
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java)
                    .putExtra("cflip_show_cover", true)
                    .putExtra("cflip_cover_tab", "HOME"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.clock_root, open)
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                android.content.ComponentName(context, ClockWidgetProvider::class.java))
            if (ids.isNotEmpty()) ClockWidgetProvider().onUpdate(context, mgr, ids)
        }
    }
}
