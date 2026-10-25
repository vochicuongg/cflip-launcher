package com.cuong.cflip.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cuong.cflip.R
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.display.AppLauncher
import com.cuong.cflip.display.DisplayRepository

/**
 * Cover App Grid widget (new in 2.5.0): a standard Android widget, separate
 * from the combined CoverWidgetProvider. It shows the full launchable app
 * list as a grid (favorites are already first in the cover UI, but a widget
 * grid is alphabetical so every app is findable). Tapping a cell launches
 * that app toward the cover display when one exists, otherwise on the
 * current display. This provider is for the main home screen's widget
 * picker; cover app grids live inside CFlip's own cover UI.
 */
class AppGridWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_appgrid)
            val svc = Intent(context, AppGridRemoteViewsService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            views.setRemoteAdapter(R.id.app_grid, svc)
            views.setEmptyView(R.id.app_grid, R.id.app_grid_empty)
            // 3.5.0 click fix, decompiled from 9Flip t5.h.i():
            // the grid template MUST be MUTABLE (33554432) + UPDATE_CURRENT
            // (134217728), with requestCode = 10000 + appWidgetId.
            // CFlip used IMMUTABLE + requestCode 0, so Android 12+ dropped
            // the fill-in extras and onReceive got pkg=null and returned
            // silently — the widget showed apps but no tap did anything.
            // IMMUTABLE is correct for fixed clicks, WRONG for a template.
            val template = PendingIntent.getBroadcast(
                context, 10000 + id,
                Intent(context, AppGridWidgetProvider::class.java).setAction(ACTION_LAUNCH_APP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            views.setPendingIntentTemplate(R.id.app_grid, template)
            val refresh = PendingIntent.getBroadcast(
                context, 5000 + id,
                Intent(context, AppGridWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setOnClickPendingIntent(R.id.app_grid_refresh, refresh)
            manager.updateAppWidget(id, views)
        }
        // Force the grid to reload its data (new installs, hidden changes).
        for (id in ids) manager.notifyAppWidgetViewDataChanged(id, R.id.app_grid)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_LAUNCH_APP -> {
                // Accept both the old "pkg" key and 9Flip's "package_name",
                // so a fill-in from either side launches.
                val pkg = intent.getStringExtra(CoverWidgetProvider.EXTRA_PKG)
                    ?: intent.getStringExtra("package_name") ?: return
                val coverId = DisplayRepository(context).secondaryDisplay()?.displayId
                AppLauncher.launch(context, pkg, coverId)
            }
            ACTION_REFRESH -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    android.content.ComponentName(context, AppGridWidgetProvider::class.java))
                for (id in ids) mgr.notifyAppWidgetViewDataChanged(id, R.id.app_grid)
            }
        }
    }

    companion object {
        const val ACTION_LAUNCH_APP = "com.cuong.cflip.widget.APPGRID_LAUNCH"
        const val ACTION_REFRESH = "com.cuong.cflip.widget.APPGRID_REFRESH"
    }
}

/** RemoteViewsService backing the grid; the factory loads apps on demand. */
class AppGridRemoteViewsService : android.widget.RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
        return AppGridFactory(applicationContext, widgetId)
    }
}

private class AppGridFactory(private val context: Context, private val widgetId: Int = 0) :
    android.widget.RemoteViewsService.RemoteViewsFactory {

    private var apps: List<com.cuong.cflip.data.AppInfo> = emptyList()

    override fun onCreate() { load() }
    override fun onDataSetChanged() { load() }
    override fun onDestroy() { apps = emptyList() }
    override fun getCount(): Int = apps.size
    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in apps.indices) return null
        val app = apps[position]
        val views = RemoteViews(context.packageName, R.layout.widget_appgrid_item)
        app.icon?.let { icon ->
            val copy = icon.constantState?.newDrawable()?.mutate() ?: icon
            val bmp = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            copy.setBounds(0, 0, 96, 96)
            copy.draw(canvas)
            views.setImageViewBitmap(R.id.app_grid_item_icon, bmp)
        }
        views.setTextViewText(R.id.app_grid_item_label, app.label)
        // Fill-in, 9Flip t5.a style: app_widget_id + appWidgetId +
        // click_action + package_name, plus CFlip's own "pkg" key.
        // With a MUTABLE template above, these extras now actually arrive.
        val fill = Intent()
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra("app_widget_id", widgetId)
            .putExtra("appWidgetId", widgetId)
            .putExtra("click_action", "open_package")
            .putExtra("package_name", app.packageName)
            .putExtra(CoverWidgetProvider.EXTRA_PKG, app.packageName)
        views.setOnClickFillInIntent(R.id.app_grid_item_root, fill)
        return views
    }
    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = true

    private fun load() {
        apps = runCatching { AppRepository(context).loadLaunchableApps() }.getOrDefault(emptyList())
    }
}
