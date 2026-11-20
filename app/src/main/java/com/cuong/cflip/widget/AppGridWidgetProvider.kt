package com.cuong.cflip.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.TypedValue
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
 * a title, so the open-group state survives renames. (Widget-lag
 * fix, 2026-11-20: the header now reads the title stored at open
 * time — refreshed by the factory on every load — instead of
 * resolving it from live sections inside updateWidget.)
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
        // HOTFIX (2026-11-20): the widget-lag fix removed this notify
        // (and debounced refreshAll's), copying 9 Flip's delayed
        // refresh — but Samsung's cover host is NOT the stock host:
        // without an immediate notify after an app update it never
        // re-queries the factory, so the grid kept only its restored
        // folder tiles and every app cell (top view AND inside
        // groups) stayed empty on the vc258+lagfix build. Restored
        // verbatim; the lag win is kept through the caches instead
        // (WidgetIconCache, lazy icons, prepared-items cache), which
        // make the reload this triggers cheap.
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
                        // Widget-lag fix: the fill-in now also carries
                        // the group title, so the header never needs a
                        // full app load just to label itself.
                        setOpenGroup(context, name, intent.getStringExtra("group_title") ?: "")
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
        private const val KEY_OPEN_TITLE = "open_title"

        fun openGroupName(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_OPEN_GROUP, "") ?: ""

        fun openGroupTitle(context: Context): String =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_OPEN_TITLE, "") ?: ""

        fun setOpenGroup(context: Context, name: String, title: String = "") {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_OPEN_GROUP, name)
                .putString(KEY_OPEN_TITLE, title).apply()
        }

        fun refreshAll(context: Context) {
            // HOTFIX (2026-11-20): immediate notify restored — the
            // 250 ms debounce from the widget-lag fix is removed.
            // vc158's design relies on this notify to make the
            // factory reload for open/back/refresh (the adapter data
            // is unchanged in those flows), and on Samsung's cover
            // host the delayed notify raced the adapter re-set in
            // updateWidget, leaving the group view empty.
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
            // title, an open group shows its name. Widget-lag fix
            // (2026-11-20): the title is the one stored when the group
            // was opened (the folder fill-in carries it, and the
            // factory re-stores the live title on every load, so a
            // rename still lands on the next load). This method used
            // to run a FULL loadLaunchableApps() + sections() just to
            // resolve the title — a second complete app query inside
            // every widget update, on the broadcast thread. A stale
            // open id is now reset by the factory's load() instead.
            val openId = openGroupName(context)
            val isOpen = openId.isNotEmpty()
            val title = if (isOpen) {
                openGroupTitle(context).ifEmpty { "Ứng dụng" }
            } else {
                "Ứng dụng"
            }
            views.setTextViewText(R.id.app_grid_title, title)
            // vc255 GRID SETTINGS: the widget used to ignore them
            // entirely — 4 columns hardcoded in the layout, 44dp
            // icons, no sort. Now the column count comes from the
            // settings (setNumColumns on the GridView) and the
            // settings ride in the adapter data URI next to the
            // group signature, so a settings change binds a fresh
            // factory carrying the new values (same vc158 trick).
            val st = com.cuong.cflip.data.SettingsStore(context).load()
            val cols = when (st.columns) { 0 -> 4; in 3..6 -> st.columns; else -> 4 }
            views.setInt(R.id.app_grid, "setNumColumns", cols)
            val minWdp = manager.getAppWidgetOptions(id)
                .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
                .let { if (it > 0) it else 320 }
            val cellWdp = ((minWdp - 24 - 10 * (cols - 1)) / cols).coerceAtLeast(48)
            val iconDp = st.iconSizeDp.coerceIn(24, minOf(cellWdp - 8, 64))
            val svc = Intent(context, AppGridRemoteViewsService::class.java)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            // vc158 IMMEDIATE WIDGET ICONS (user, 29/10): the adapter
            // intent carries the live group signature as its data
            // URI. Android identifies a RemoteViewsFactory by its
            // intent (filterEquals ignores extras but NOT data) —
            // so any group edit changes the signature, the system
            // binds a FRESH factory, and onCreate loads the new
            // icons immediately, instead of relying on the host to
            // honor notifyAppWidgetViewDataChanged after re-setting
            // an identical adapter. Unchanged signature = same
            // factory (cheap); refreshAll's notify still reloads it
            // for open/back/refresh, where the data is unchanged.
            val sig = CustomGroupStore.signature(
                CustomGroupStore.load(context),
                CustomGroupStore.loadHiddenAuto(context),
                CustomGroupStore.loadOverrides(context),
                CustomGroupStore.loadAutoOrder(context),
                CustomGroupStore.loadUnifiedOrder(context),
                CustomGroupStore.loadDeletedSections(context))
            svc.data = Uri.Builder()
                .scheme("cflip").authority("appgrid")
                .appendPath(id.toString()).appendPath(sig)
                .appendPath(cols.toString()).appendPath(iconDp.toString())
                .appendPath(st.sortMode.toString())
                .appendPath(if (st.showLabels) "1" else "0").build()
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
            if (isOpen) {
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
    // vc255: grid settings, re-read from the store on every load()
    // (the provider also puts them in the adapter URI so a settings
    // change binds a fresh factory — belt and suspenders).
    private var iconDp: Int = 44
    private var sortMode: Int = 0
    private var showLabels: Boolean = true

    // vc255: RemoteViews sizes are pixels on the HOST display (the
    // cover), not the app's main display — convert with the cover
    // density when there is one, else the app density.
    private val hostDensity: Float by lazy {
        runCatching {
            val disp = DisplayRepository(context).secondaryDisplay()
            if (disp != null) context.createDisplayContext(disp).resources.displayMetrics.density
            else context.resources.displayMetrics.density
        }.getOrDefault(context.resources.displayMetrics.density)
    }
    private fun dpPx(dp: Int) = (dp * hostDensity).toInt()

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

    // HOTFIX (2026-11-20): back to null. The widget-lag fix returned
    // an INVISIBLE item layout here (copied from 9 Flip / Cover
    // Power), but on Samsung's cover host — the same host that
    // refused plain Views in vc249 — app cells ended up represented
    // by that invisible placeholder and never swapped for the real
    // cell views, so no app cell was visible anywhere in the grid.
    // null is what every working CFlip build shipped with.
    override fun getLoadingView(): RemoteViews? = null

    // Widget-lag fix: icon bitmaps come from the shared
    // WidgetIconCache — rendered once per package+size, then pure
    // memory reads. The Drawable is loaded lazily here (only for
    // cells the host actually asks for) because load() no longer
    // loads every app's icon up front.
    private fun iconBitmapFor(app: AppInfo, sizePx: Int): android.graphics.Bitmap? {
        WidgetIconCache.get(app.packageName, sizePx)?.let { return it }
        val drawable = app.icon ?: runCatching {
            context.packageManager.getApplicationIcon(app.packageName)
        }.getOrNull() ?: return null
        return WidgetIconCache.render(app.packageName, drawable, sizePx)
    }

    private fun appViews(app: AppInfo): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_appgrid_item)
        iconBitmapFor(app, dpPx(iconDp) * 2)?.let { bmp ->
            // vc255: bitmap at 2x the view size for sharpness.
            views.setImageViewBitmap(R.id.app_grid_item_icon, bmp)
        }
        // vc255 ICON SIZE: the icon view was a fixed 44dp and the
        // bitmap a fixed 96px, so the slider did nothing here.
        // setViewLayoutWidth/Height (API 31+, minSdk 33) resize
        // the view in the host; the label follows showLabels.
        // vc258 FIX (vc255 broke the build): setViewLayoutWidth /
        // Height take THREE args (viewId, size, unit) — the size is
        // a Float in the given unit, there is no (viewId, px)
        // overload. The px value computed above is passed with
        // COMPLEX_UNIT_PX so the size is exactly the computed px.
        views.setViewLayoutWidth(R.id.app_grid_item_icon, dpPx(iconDp).toFloat(), TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutHeight(R.id.app_grid_item_icon, dpPx(iconDp).toFloat(), TypedValue.COMPLEX_UNIT_PX)
        if (showLabels) {
            views.setTextViewText(R.id.app_grid_item_label, app.label)
            views.setViewVisibility(R.id.app_grid_item_label, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.app_grid_item_label, View.GONE)
        }
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
        // vc255: the folder preview box follows the icon size like
        // an app cell (the 9 mini icons scale with it via weight).
        views.setViewLayoutWidth(R.id.app_grid_group_icon_box, dpPx(iconDp).toFloat(), TypedValue.COMPLEX_UNIT_PX)
        views.setViewLayoutHeight(R.id.app_grid_group_icon_box, dpPx(iconDp).toFloat(), TypedValue.COMPLEX_UNIT_PX)
        // SystemUI-style folder preview: first 9 members (A-Z) in a
        // 3x3 mini-grid; missing slots stay INVISIBLE so the 3x3
        // shape holds for small groups.
        val preview = members.take(9)
        for (i in 0 until 9) {
            val id = groupIconIds[i]
            if (i < preview.size) {
                iconBitmapFor(preview[i], 32)?.let { bmp ->
                    views.setImageViewBitmap(id, bmp)
                }
            } else {
                views.setViewVisibility(id, View.INVISIBLE)
            }
        }
        if (showLabels) {
            views.setTextViewText(R.id.app_grid_group_label, title)
            views.setViewVisibility(R.id.app_grid_group_label, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.app_grid_group_label, View.GONE)
        }
        val fill = Intent()
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra("app_widget_id", widgetId)
            .putExtra("appWidgetId", widgetId)
            .putExtra("click_action", "open_group")
            .putExtra("group", id)
            // Widget-lag fix: title rides along so opening the group
            // never needs a full app load to label the header.
            .putExtra("group_title", title)
        views.setOnClickFillInIntent(R.id.app_grid_group_root, fill)
        return views
    }

    private fun load() {
        // Widget-lag fix (2026-11-20): prepared-items cache. The
        // host rebinds this factory constantly (fold/unfold, options
        // changes, notify storms); each rebind used to re-run the
        // whole pipeline below — UsageStats query, group sections,
        // sorting — even when nothing had changed. The prepared
        // items are now reused while the configuration signature is
        // unchanged. This mirrors 9 Flip's design, whose factory
        // only reads a prebuilt render snapshot (t5.a.f()) instead
        // of recomputing it.
        // Scroll-reload fix (2026-11-20): the TTL was 15 s, so any
        // scroll/rebind after 15 s re-ran the full pipeline and the
        // user saw the grid "reload the apps" while scrolling.
        // Invalidation is signature-driven (package changes bump
        // AppRepository.appListVersion, group edits change the
        // group signature, and the launch-history hash below covers
        // the recently-used order), so the TTL is now just a 10 min
        // safety net, not the mechanism.
        val st0 = com.cuong.cflip.data.SettingsStore(context).load()
        val customGroups = CustomGroupStore.load(context)
        val hiddenAuto = CustomGroupStore.loadHiddenAuto(context)
        val overrides = CustomGroupStore.loadOverrides(context)
        val autoOrder = CustomGroupStore.loadAutoOrder(context)
        val unifiedOrder = CustomGroupStore.loadUnifiedOrder(context)
        val deletedSections = CustomGroupStore.loadDeletedSections(context)
        val openId0 = AppGridWidgetProvider.openGroupName(context)
        val cacheKey = listOf(
            AppRepository.appListVersion().toString(),
            CustomGroupStore.signature(
                customGroups, hiddenAuto, overrides,
                autoOrder, unifiedOrder, deletedSections),
            com.cuong.cflip.data.HiddenAppsStore(context).getHidden().hashCode().toString(),
            // Launch order affects only the recent sort, but keeping
            // its hash in the key makes a launch invalidate the
            // prepared items immediately instead of at TTL expiry.
            com.cuong.cflip.data.LaunchHistoryStore(context).lastLaunched().hashCode().toString(),
            openId0,
            st0.sortMode.toString(), st0.iconSizeDp.toString(),
            st0.showLabels.toString(), st0.favoritesFirst.toString(),
        ).joinToString("|")
        val nowMs = System.currentTimeMillis()
        synchronized(preparedLock) {
            if (preparedKey == cacheKey && nowMs - preparedAtMs < PREPARED_TTL_MS) {
                items = preparedItems
                iconDp = preparedIconDp
                showLabels = preparedShowLabels
                sortMode = st0.sortMode
                return
            }
        }
        // Icons are NOT loaded here anymore (includeIcons = false):
        // a cold query used to loadIcon() all ~200 apps on the
        // factory's binder thread before the first cell could be
        // served. Icons load lazily per visible cell via
        // WidgetIconCache (see iconBitmapFor).
        val apps = runCatching {
            AppRepository(context).loadLaunchableApps(includeIcons = false)
        }.getOrDefault(emptyList())
        // vc255 SORT + ICON + LABELS: the factory used to ignore
        // sortMode entirely (groups in section order, ungrouped
        // apps always A-Z) and hardcode a 44dp icon with labels
        // always on. Now it reads the store on every load: the
        // sort applies to the app lists — ungrouped apps and the
        // members inside each folder / open group — while the
        // folder tiles themselves keep their section order.
        // Recent order works without Usage Access too (the
        // launch-history fallback in RecentAppsRepository); it is
        // just shorter then, and the rest stay A-Z.
        val st = st0
        // Icon clamp for the widget band: widget cells are ~50-100dp
        // wide, so the slider's 84dp top would overflow them — 64dp
        // is the most that still fits with the label underneath.
        // (The provider applies the same band when it builds the
        // adapter URI; the column count itself is set on the GridView
        // in updateWidget, not here.)
        iconDp = st.iconSizeDp.coerceIn(24, 64)
        sortMode = st.sortMode
        showLabels = st.showLabels
        val recentOrder = com.cuong.cflip.data.RecentAppsRepository(context).recentPackages(100)
        val installTimes = if (sortMode == 2) AppRepository(context).installTimes(apps.map { it.packageName }) else emptyMap()
        fun sorted(list: List<AppInfo>) =
            com.cuong.cflip.data.AppSort.sort(list, sortMode, recentOrder, installTimes)
        // vc152: custom groups first, then auto groups for the rest,
        // then ungrouped apps — the same merge as the panel card.
        // vc153: no Back cell anymore — back lives in the header.
        val (sections, ungrouped) = CustomGroupStore.sections(
            apps, customGroups, hiddenAuto, overrides,
            autoOrder, unifiedOrder, deletedSections)
        val openId = AppGridWidgetProvider.openGroupName(context)
        val openSection = sections.firstOrNull { it.id == openId }
        items = if (openSection != null) {
            // Widget-lag fix: keep the stored header title in sync
            // with the live section (renames land here), without the
            // provider ever loading apps itself.
            if (AppGridWidgetProvider.openGroupTitle(context) != openSection.title) {
                AppGridWidgetProvider.setOpenGroup(context, openId, openSection.title)
            }
            // Inside a folder: just its members, in the user's order.
            sorted(openSection.members).map { GridItem.App(it) }
        } else {
            // Top view: folder tiles + ungrouped apps (A-Z).
            // A stale open id (group deleted/emptied) resets to top view.
            if (openId.isNotEmpty()) AppGridWidgetProvider.setOpenGroup(context, "")
            sections.map { GridItem.Group(it.id, it.title, sorted(it.members)) } +
                sorted(ungrouped).map { GridItem.App(it) }
        }
        // Widget-lag fix: publish the prepared items for the next
        // rebind (see the cache check at the top of load()).
        // HOTFIX (2026-11-20): an EMPTY result is never published —
        // the same rule AppRepository's vc111 cache follows — so a
        // transient-empty query (vc59/vc71) can never be served to
        // the next rebind as if it were the real grid.
        if (items.isNotEmpty()) {
            synchronized(preparedLock) {
                preparedKey = cacheKey
                preparedAtMs = System.currentTimeMillis()
                preparedItems = items
                preparedIconDp = iconDp
                preparedShowLabels = showLabels
            }
        }
    }

    companion object {
        // Process-wide prepared-items cache, shared by every factory
        // instance (the host may create several). Static state is
        // exactly what lets Cover Power's factory stay cheap across
        // rebinds (its label cache is a static ConcurrentHashMap).
        private const val PREPARED_TTL_MS = 600_000L
        private val preparedLock = Any()
        @Volatile private var preparedKey: String? = null
        @Volatile private var preparedAtMs: Long = 0L
        @Volatile private var preparedItems: List<GridItem> = emptyList()
        @Volatile private var preparedIconDp: Int = 44
        @Volatile private var preparedShowLabels: Boolean = true
    }
}
