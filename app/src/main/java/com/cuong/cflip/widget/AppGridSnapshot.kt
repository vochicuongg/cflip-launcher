package com.cuong.cflip.widget

import android.content.Context
import android.graphics.Bitmap
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.data.AppSort
import com.cuong.cflip.data.CustomGroupStore
import com.cuong.cflip.data.RecentAppsRepository
import com.cuong.cflip.data.SettingsStore

/**
 * 9 Flip-style render snapshot for the App Grid widget
 * (architecture rework, 2026-11-20).
 *
 * REA evidence (9 Flip t5.a.getViewAt, ev_831c7afe): their factory
 * holds NO cell state and does NO data work — it reads a prebuilt
 * snapshot from a store outside the factory (v5.c via h.k()), and
 * getViewAt() is a pure field read plus setImageViewBitmap() of the
 * snapshot's pre-rendered Bitmap. That is why their grid never
 * "reloads" cells on scroll, no matter how often the host destroys
 * and recreates the factory.
 *
 * CFlip now works the same way:
 *  - [AppGridSnapshotStore] holds ONE snapshot for the whole grid:
 *    the top view (folder tiles + ungrouped apps) AND every group's
 *    member list, each item carrying its label and its pre-rendered
 *    icon Bitmap at the exact host pixel size.
 *  - [AppGridSnapshotBuilder] (re)builds it only when the dataset
 *    signature changes (package changes, group edits, hidden set,
 *    launch order, widget settings). Builds are triggered eagerly
 *    from the provider (onUpdate / refreshAll, background thread)
 *    and lazily by the factory when it finds no current snapshot.
 *  - The factory's load() is reduced to: pick the top list or the
 *    open group's list out of the current snapshot. getViewAt()
 *    only assembles a RemoteViews from the item's fields.
 *
 * Icons are rendered through [WidgetIconCache], so bitmaps are
 * shared with the cache and a rebuild reuses still-valid renders.
 * Full-size bitmaps are rendered at the cell's real display size
 * (dpPx(iconDp), 1x) like 9 Flip's snapshot — not the 2x oversample
 * the old per-cell path used; the whole grid's bitmaps then total
 * roughly 15-20 MB instead of ~70 MB.
 */
object AppGridSnapshotStore {

    class Item(
        val isGroup: Boolean,
        /** App: package name. Group: section id. */
        val id: String,
        /** App: label. Group: title. */
        val label: String,
        /** App: pre-rendered icon at cell size. Group: null. */
        val icon: Bitmap?,
        /** Group: pre-rendered 32 px previews of the first members. */
        val memberIcons: List<Bitmap?> = emptyList(),
        /** Group: real member count (memberIcons is capped at 9). */
        val memberCount: Int = 0,
    )

    class Snapshot(
        val key: String,
        val iconDp: Int,
        val showLabels: Boolean,
        val top: List<Item>,
        val groups: Map<String, List<Item>>,
    )

    @Volatile
    var current: Snapshot? = null
}

object AppGridSnapshotBuilder {

    private val buildLock = Any()

    // Last cell size the factory asked for, so provider-triggered
    // rebuilds render at the same size. Zero = factory never ran;
    // async rebuilds are skipped then (the factory builds on load).
    @Volatile
    private var lastCellPx: Int = 0

    /**
     * Dataset signature: everything that changes WHAT the snapshot
     * contains. Deliberately does NOT include the open group — one
     * snapshot holds every group, so open/back never rebuilds.
     */
    fun signature(context: Context): String {
        val st = SettingsStore(context).load()
        return listOf(
            AppRepository.appListVersion().toString(),
            CustomGroupStore.signature(
                CustomGroupStore.load(context),
                CustomGroupStore.loadHiddenAuto(context),
                CustomGroupStore.loadOverrides(context),
                CustomGroupStore.loadAutoOrder(context),
                CustomGroupStore.loadUnifiedOrder(context),
                CustomGroupStore.loadDeletedSections(context),
            ),
            com.cuong.cflip.data.HiddenAppsStore(context).getHidden().hashCode().toString(),
            // Launch order affects only the recent sort, but keeping
            // its hash in the key makes a launch invalidate the
            // snapshot immediately instead of at some TTL.
            com.cuong.cflip.data.LaunchHistoryStore(context).lastLaunched().hashCode().toString(),
            st.sortMode.toString(), st.iconSizeDp.toString(),
            st.showLabels.toString(), st.favoritesFirst.toString(),
        ).joinToString("|")
    }

    /** The current snapshot, building it first if the signature moved. */
    fun snapshot(context: Context, cellPx: Int): AppGridSnapshotStore.Snapshot {
        if (cellPx > 0) lastCellPx = cellPx
        val key = signature(context)
        AppGridSnapshotStore.current?.let { if (it.key == key) return it }
        synchronized(buildLock) {
            AppGridSnapshotStore.current?.let { if (it.key == key) return it }
            val built = build(context, key, cellPx)
            // Never publish an empty snapshot: a transient-empty
            // query (vc59/vc71) must neither blank the grid over a
            // good snapshot nor be cached as if it were real (the
            // vc111 rule) — serve the previous snapshot, or the
            // empty one unstored so the next load retries.
            if (built.top.isNotEmpty()) {
                AppGridSnapshotStore.current = built
                return built
            }
            return AppGridSnapshotStore.current ?: built
        }
    }

    /**
     * Eager rebuild on a background thread (called by the provider
     * on update / refresh): by the time the host binds the factory,
     * the snapshot is usually already current.
     */
    fun rebuildAsync(context: Context) {
        val cellPx = lastCellPx
        if (cellPx <= 0) return
        val appContext = context.applicationContext
        Thread {
            runCatching { snapshot(appContext, cellPx) }
        }.start()
    }

    private fun build(
        context: Context,
        key: String,
        cellPx: Int,
    ): AppGridSnapshotStore.Snapshot {
        val st = SettingsStore(context).load()
        val iconDp = st.iconSizeDp.coerceIn(24, 64)
        val apps = runCatching {
            AppRepository(context).loadLaunchableApps(includeIcons = false)
        }.getOrDefault(emptyList())
        val recentOrder = RecentAppsRepository(context).recentPackages(100)
        val installTimes = if (st.sortMode == 2) {
            AppRepository(context).installTimes(apps.map { it.packageName })
        } else {
            emptyMap()
        }
        fun sorted(list: List<com.cuong.cflip.data.AppInfo>) =
            AppSort.sort(list, st.sortMode, recentOrder, installTimes)
        val (sections, ungrouped) = CustomGroupStore.sections(
            apps,
            CustomGroupStore.load(context),
            CustomGroupStore.loadHiddenAuto(context),
            CustomGroupStore.loadOverrides(context),
            CustomGroupStore.loadAutoOrder(context),
            CustomGroupStore.loadUnifiedOrder(context),
            CustomGroupStore.loadDeletedSections(context),
        )
        val pm = context.packageManager
        fun iconFor(packageName: String, sizePx: Int): Bitmap? {
            WidgetIconCache.get(packageName, sizePx)?.let { return it }
            val drawable = WidgetIconCache.drawable(packageName, pm) ?: return null
            return runCatching { WidgetIconCache.render(packageName, drawable, sizePx) }
                .getOrNull()
        }
        fun appItem(app: com.cuong.cflip.data.AppInfo) = AppGridSnapshotStore.Item(
            isGroup = false,
            id = app.packageName,
            label = app.label,
            icon = iconFor(app.packageName, cellPx),
        )
        val groups = LinkedHashMap<String, List<AppGridSnapshotStore.Item>>()
        val top = ArrayList<AppGridSnapshotStore.Item>(sections.size + ungrouped.size)
        for (section in sections) {
            val members = sorted(section.members)
            groups[section.id] = members.map { appItem(it) }
            top += AppGridSnapshotStore.Item(
                isGroup = true,
                id = section.id,
                label = section.title,
                icon = null,
                memberIcons = members.take(9).map { iconFor(it.packageName, 32) },
                memberCount = members.size,
            )
        }
        for (app in sorted(ungrouped)) {
            top += appItem(app)
        }
        return AppGridSnapshotStore.Snapshot(
            key = key,
            iconDp = iconDp,
            showLabels = st.showLabels,
            top = top,
            groups = groups,
        )
    }
}
