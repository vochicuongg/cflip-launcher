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
 * current display. This is a provider only — the host that places it on the
 * cover is the system / MultiStar Launcher Widget, CFlip does not host it.
 */
class AppGridWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_appgrid)
            val svc = Intent(context, AppGridRemoteViewsService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            views.setRemoteAdapter(R.id.app_grid, svc)
            views.setEmptyView(R.id.app_grid, R.id.app_grid_empty)
            val template = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, AppGridWidgetProvider::class.java).setAction(ACTION_LAUNCH_APP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            views.setPendingIntentTemplate(R.id.app_grid, template)
            val refresh = PendingIntent.getBroadcast(
                context, 1,
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
                val pkg = intent.getStringExtra(CoverWidgetProvider.EXTRA_PKG) ?: return
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
        return AppGridFactory(applicationContext)
    }
}

private class AppGridFactory(private val context: Context) :
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
        val fill = Intent().putExtra(CoverWidgetProvider.EXTRA_PKG, app.packageName)
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
