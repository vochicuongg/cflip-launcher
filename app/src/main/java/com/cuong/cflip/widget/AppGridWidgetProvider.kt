package com.cuong.cflip.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.RemoteViews
import com.cuong.cflip.R
import com.cuong.cflip.data.CustomGroupStore
import com.cuong.cflip.data.AppInfo
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.display.AppLauncher
import com.cuong.cflip.display.DisplayRepository

/**
 * Cover App Grid widget (new in 2.5.0): a standard Android widget, separate
 * from the combined CoverWidgetProvider. It shows the launchable app list
 * as a grid (favorites are already first in the cover UI, but a widget
 * grid is alphabetical so every app is findable). Tapping a cell launches
 * that app toward the cover display when one exists, otherwise on the
 * current display. This provider is for the main home screen's widget
 * picker; cover app grids live inside CFlip's own cover UI.
 *
 * vc151 GROUPS (user, 29/10): the widget now groups apps with the SAME
 * rules as the Quick Panel card (AppGroups: SOCIAL / GAME / MEDIA /
 * WORK, same order, same well-known social packages, no "Other"
 * group). Each group is one folder tile in the grid, SystemUI-style:
 * a 3x3 preview of its first 9 member icons (A-Z) above the group
 * name. Tapping a folder drills into the group (its member apps +
 * a back cell); tapping back returns to the top view (folder tiles
 * + ungrouped apps). The open group is kept in SharedPreferences,
 * so every widget instance shows the same view; deleting the last
 * widget resets it to the top view.
 *
 * vc152 CUSTOM GROUPS (user, 29/10): folder tiles now come from
 * CustomGroupStore.sections() — user-built groups first, then
 * the auto groups for the remaining apps. A folder tile carries
 * a stable section id ("custom:<uuid>" or "auto:<GROUP>") and
 * a title, so the open-group state survives renames and the
 * header always resolves its title from live sections.
 *
 * vc153 (user, 29/10): no Back grid cell anymore — back lives in
 * the header as an icon-only button sitting exactly where the
 * refresh button sits (it replaces refresh while a group is
 * open); custom-group member order is the user's arranged order;
 * hidden default groups are skipped by sections().
 */
class AppGridWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) updateWidget(context, manager, id)
        // Force the grid to reload its data (new installs, hidden changes).
        for (id in ids) manager.notifyAppWidgetViewDataChanged(id, R.id.app_grid)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_LAUNCH_APP -> {
                // One template action, distinguished by the fill-in's
                // click_action extra (9Flip t5.a style): the template
                // PendingIntent is MUTABLE, so fill-in extras arrive.
                when (intent.getStringExtra("click_action") ?: "open_package") {
                    "open_group" -> {
                        val name = intent.getStringExtra("group") ?: return
                        setOpenGroup(context, name)
                        refreshAll(context)
                    }
                    "close_group" -> {
                        setOpenGroup(context, "")
                        refreshAll(context)
                    }
                    else -> {
                        // Accept both the old "pkg" key and 9Flip's "package_name",
                        // so a fill-in from either side launches.
                        val pkg = intent.getStringExtra(CoverWidgetProvider.EXTRA_PKG)
                            ?: intent.getStringExtra("package_name") ?: return
                        val coverId = DisplayRepository(context).secondaryDisplay()?.displayId
                        AppLauncher.launch(context, pkg, coverId)
                    }
                }
            }
            ACTION_REFRESH -> refreshAll(context)
            ACTION_BACK -> {
                setOpenGroup(context, "")
                refreshAll(context)
            }
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Last widget removed: a fresh add starts at the top view.
        setOpenGroup(context, "")
    }

    companion object {
        const val ACTION_LAUNCH_APP = "com.cuong.cflip.widget.APPGRID_LAUNCH"
        const val ACTION_REFRESH = "com.cuong.cflip.widget.APPGRID_REFRESH"
        const val ACTION_BACK = "com.cuong.cflip.widget.APPGRID_BACK"
        private const val PREFS = "appgrid_widget"
        private const val KEY_OPEN_GROUP = "open_group"

        fun openGroupName(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_OPEN_GROUP, "") ?: ""

        fun setOpenGroup(context: Context, name: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_OPEN_GROUP, name).apply()
        }

        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                ComponentName(context, AppGridWidgetProvider::class.java))
            for (id in ids) {
                updateWidget(context, mgr, id)
                mgr.notifyAppWidgetViewDataChanged(id, R.id.app_grid)
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_appgrid)
            // Header follows the drill-down: top view keeps the old
            // title, an open group shows its name, resolved from LIVE
            // sections so a rename is reflected immediately. A stale
            // open id (group deleted) falls back to the top view.
            val openId = openGroupName(context)
            val openSection = if (openId.isNotEmpty()) {
                val apps = runCatching { AppRepository(context).loadLaunchableApps() }
                    .getOrDefault(emptyList())
                val (sections, _) = CustomGroupStore.sections(
                    apps, CustomGroupStore.load(context),
                    CustomGroupStore.loadHiddenAuto(context),
                    CustomGroupStore.loadOverrides(context),
                    CustomGroupStore.loadAutoOrder(context))
                sections.firstOrNull { it.id == openId }
                    .also { if (it == null) setOpenGroup(context, "") }
            } else {
                null
            }
            val title = if (openSection != null) {
                "CFlip — ${openSection.title}"
            } else {
                "CFlip — Ứng dụng"
            }
            views.setTextViewText(R.id.app_grid_title, title)
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
            if (openSection != null) {
                // vc153 BACK IN HEADER: while a group is open, the
                // header button becomes BACK — icon only, no "Tất cả"
                // text — sitting exactly where refresh sits. Top view
                // keeps the refresh button as before.
                views.setTextViewText(R.id.app_grid_refresh, "←")
                val back = PendingIntent.getBroadcast(
                    context, 6000 + id,
                    Intent(context, AppGridWidgetProvider::class.java).setAction(ACTION_BACK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.app_grid_refresh, back)
            } else {
                views.setTextViewText(R.id.app_grid_refresh, "↻")
                views.setOnClickPendingIntent(R.id.app_grid_refresh, refresh)
            }
            manager.updateAppWidget(id, views)
        }
    }
}

/** RemoteViewsService backing the grid; the factory loads apps on demand. */
class AppGridRemoteViewsService : android.widget.RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
        return AppGridFactory(applicationContext, widgetId)
    }
}

private sealed class GridItem {
    data class App(val app: AppInfo) : GridItem()
    data class Group(val id: String, val title: String, val members: List<AppInfo>) : GridItem()
}

private class AppGridFactory(private val context: Context, private val widgetId: Int = 0) :
    android.widget.RemoteViewsService.RemoteViewsFactory {

    private var items: List<GridItem> = emptyList()

    // The 9 mini-icon slots of a folder tile, row by row.
    private val groupIconIds = intArrayOf(
        R.id.app_grid_group_icon_0, R.id.app_grid_group_icon_1, R.id.app_grid_group_icon_2,
        R.id.app_grid_group_icon_3, R.id.app_grid_group_icon_4, R.id.app_grid_group_icon_5,
        R.id.app_grid_group_icon_6, R.id.app_grid_group_icon_7, R.id.app_grid_group_icon_8,
    )

    override fun onCreate() { load() }
    override fun onDataSetChanged() { load() }
    override fun onDestroy() { items = emptyList() }
    override fun getCount(): Int = items.size

    override fun getViewAt(position: Int): RemoteViews? {
        if (position !in items.indices) return null
        return when (val item = items[position]) {
            is GridItem.App -> appViews(item.app)
            is GridItem.Group -> groupViews(item.id, item.title, item.members)
        }
    }

    // 2 view types: app cell, folder tile.
    override fun getViewTypeCount(): Int = 2
    override fun getItemId(position: Int): Long = when (val item = items[position]) {
        is GridItem.App -> item.app.packageName.hashCode().toLong()
        is GridItem.Group -> item.id.hashCode().toLong()
    }
    override fun hasStableIds(): Boolean = true
    override fun getLoadingView(): RemoteViews? = null

    private fun appViews(app: AppInfo): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_appgrid_item)
        app.icon?.let { icon ->
            views.setImageViewBitmap(R.id.app_grid_item_icon, iconBitmap(icon, 96))
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

    private fun groupViews(id: String, title: String, members: List<AppInfo>): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_appgrid_group)
        // SystemUI-style folder preview: first 9 members (A-Z) in a
        // 3x3 mini-grid; missing slots stay INVISIBLE so the 3x3
        // shape holds for small groups.
        val preview = members.take(9)
        for (i in 0 until 9) {
            val id = groupIconIds[i]
            if (i < preview.size) {
                preview[i].icon?.let { icon ->
                    views.setImageViewBitmap(id, iconBitmap(icon, 32))
                }
            } else {
                views.setViewVisibility(id, View.INVISIBLE)
            }
        }
        views.setTextViewText(R.id.app_grid_group_label, title)
        val fill = Intent()
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra("app_widget_id", widgetId)
            .putExtra("appWidgetId", widgetId)
            .putExtra("click_action", "open_group")
            .putExtra("group", id)
        views.setOnClickFillInIntent(R.id.app_grid_group_root, fill)
        return views
    }

    private fun iconBitmap(icon: Drawable, size: Int): Bitmap {
        val copy = icon.constantState?.newDrawable()?.mutate() ?: icon
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        copy.setBounds(0, 0, size, size)
        copy.draw(canvas)
        return bmp
    }

    private fun load() {
        val apps = runCatching { AppRepository(context).loadLaunchableApps() }
            .getOrDefault(emptyList())
        // vc152: custom groups first, then auto groups for the rest,
        // then ungrouped apps — the same merge as the panel card.
        // vc153: no Back cell anymore — back lives in the header.
        val (sections, ungrouped) = CustomGroupStore.sections(
            apps, CustomGroupStore.load(context),
            CustomGroupStore.loadHiddenAuto(context),
            CustomGroupStore.loadOverrides(context),
            CustomGroupStore.loadAutoOrder(context))
        val openId = AppGridWidgetProvider.openGroupName(context)
        val openSection = sections.firstOrNull { it.id == openId }
        items = if (openSection != null) {
            // Inside a folder: just its members, in the user's order.
            openSection.members.map { GridItem.App(it) }
        } else {
            // Top view: folder tiles + ungrouped apps (A-Z).
            // A stale open id (group deleted/emptied) resets to top view.
            if (openId.isNotEmpty()) AppGridWidgetProvider.setOpenGroup(context, "")
            sections.map { GridItem.Group(it.id, it.title, it.members) } +
                ungrouped.map { GridItem.App(it) }
        }
    }
}
