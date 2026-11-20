package com.cuong.cflip.widget

import android.content.Context
import android.graphics.Bitmap
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.data.AppSort
import com.cuong.cflip.data.CustomGroupStore
import com.cuong.cflip.data.RecentAppsRepository
import com.cuong.cflip.data.SettingsStore

/**
 * reference-style render snapshot for the App Grid widget
 * (architecture rework, 2026-11-20).
 *
 * REA evidence (the reference app t5.a.getViewAt, ev_831c7afe): their factory
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
 * (dpPx(iconDp), 1x) like the reference app's snapshot — not the 2x oversample
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
        /** Group: member packages in display order (snapshot persistence). */
        val memberPkgs: List<String> = emptyList(),
        /** Disk-loaded items only: PNG to decode lazily for [icon]. */
        val iconPath: String? = null,
        /** Disk-loaded items only: PNG paths parallel to the first members. */
        val memberIconPaths: List<String?> = emptyList(),
    ) {
        // Lazy icon decoding for disk-loaded snapshots: the factory
        // (or any other consumer, e.g. a widget-picker preview
        // binding in a fresh process) gets the bitmap on first use,
        // decoded once from the persisted PNG — no PackageManager
        // query, no re-render.
        @Volatile
        private var decodedIcon: Bitmap? = icon

        fun iconBitmap(): Bitmap? {
            decodedIcon?.let { return it }
            val path = iconPath ?: return null
            val bmp = runCatching { android.graphics.BitmapFactory.decodeFile(path) }
                .getOrNull() ?: return null
            decodedIcon = bmp
            return bmp
        }

        private val decodedMemberIcons = HashMap<Int, Bitmap?>()

        fun memberIconBitmap(index: Int): Bitmap? {
            if (memberIcons.isNotEmpty()) return memberIcons.getOrNull(index)
            synchronized(decodedMemberIcons) {
                if (decodedMemberIcons.containsKey(index)) return decodedMemberIcons[index]
                val path = memberIconPaths.getOrNull(index)
                val bmp = path?.let {
                    runCatching { android.graphics.BitmapFactory.decodeFile(it) }.getOrNull()
                }
                decodedMemberIcons[index] = bmp
                return bmp
            }
        }
    }

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
            AppRepository.appListVersion(context).toString(),
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

    /**
     * The current snapshot. NEVER-EMPTY rule (preview fix,
     * 2026-11-20): a binding must never sit empty while ANY
     * snapshot exists. Samsung's picker preview kept coming up
     * blank because the persisted snapshot was gated on exact
     * signature + cell-size equality — in the picker's fresh
     * process the size (density source) or a signature component
     * could differ, the disk snapshot was rejected, and the
     * factory paid a full cold build on its binder thread while
     * the host was already capturing. Now: an exact match wins;
     * otherwise any in-memory or persisted snapshot is served
     * immediately (icons decode at their stored size and the
     * host scales them into the cells) and the exact snapshot is
     * rebuilt in the background, re-notifying the widgets when
     * it lands. A synchronous build happens only when no
     * snapshot exists anywhere yet.
     */
    fun snapshot(context: Context, cellPx: Int): AppGridSnapshotStore.Snapshot {
        if (cellPx > 0) lastCellPx = cellPx
        val key = signature(context)
        AppGridSnapshotStore.current?.let { cur ->
            if (cur.key == key) return cur
            rebuildInBackground(context, key, cellPx)
            return cur
        }
        synchronized(buildLock) {
            AppGridSnapshotStore.current?.let { if (it.key == key) return it }
            loadFromDisk(context)?.let { disk ->
                AppGridSnapshotStore.current = disk
                if (disk.key != key) rebuildInBackground(context, key, cellPx)
                return disk
            }
            return buildAndStore(context, key, cellPx)
        }
    }

    /** Build + publish + persist. The caller holds [buildLock]. */
    private fun buildAndStore(
        context: Context,
        key: String,
        cellPx: Int,
    ): AppGridSnapshotStore.Snapshot {
        val built = build(context, key, cellPx)
        // Never publish an empty snapshot: a transient-empty
        // query (vc59/vc71) must neither blank the grid over a
        // good snapshot nor be cached as if it were real (the
        // vc111 rule) — serve the previous snapshot, or the
        // empty one unstored so the next load retries.
        if (built.top.isNotEmpty()) {
            AppGridSnapshotStore.current = built
            persistAsync(context, built, cellPx)
            return built
        }
        return AppGridSnapshotStore.current ?: built
    }

    private val rebuilding = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Rebuild the exact snapshot off the binder thread, then re-notify. */
    private fun rebuildInBackground(context: Context, key: String, cellPx: Int) {
        if (!rebuilding.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread {
            try {
                synchronized(buildLock) { buildAndStore(appContext, key, cellPx) }
                runCatching { AppGridWidgetProvider.refreshAll(appContext) }
            } finally {
                rebuilding.set(false)
            }
        }.start()
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
                memberPkgs = members.map { it.packageName },
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

    // ---- Disk persistence (the reference app's apps_snapshot.json pattern) ----
    //
    // The structure is one small JSON in filesDir; icons are PNG
    // files per package+size, written once and reused across
    // snapshot generations (an app's icon rarely changes). A stale
    // JSON (signature or cell size moved on) is ignored, never
    // served. All failures are silent: persistence is an
    // accelerator, never a requirement.

    private fun snapshotDir(context: Context) =
        java.io.File(context.filesDir, "appgrid_snapshot")

    private fun iconFile(context: Context, packageName: String, sizePx: Int) =
        java.io.File(snapshotDir(context), "icons/$packageName@$sizePx.png")

    @Volatile
    private var persistedKey: String? = null

    private fun persistAsync(
        context: Context,
        snap: AppGridSnapshotStore.Snapshot,
        cellPx: Int,
    ) {
        if (persistedKey == snap.key) return
        val appContext = context.applicationContext
        Thread {
            runCatching {
                val dir = snapshotDir(appContext)
                java.io.File(dir, "icons").mkdirs()
                fun writeIcon(packageName: String, bmp: Bitmap?, sizePx: Int) {
                    if (bmp == null) return
                    val f = iconFile(appContext, packageName, sizePx)
                    if (f.exists()) return
                    val tmp = java.io.File(f.path + ".tmp")
                    java.io.FileOutputStream(tmp).use { out ->
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    tmp.renameTo(f)
                }
                // Full-size icons for every app item, 32 px previews
                // for group members (same files the disk loader
                // points at).
                val seenFull = HashSet<String>()
                val seenPreview = HashSet<String>()
                fun persistApp(item: AppGridSnapshotStore.Item) {
                    if (seenFull.add(item.id)) writeIcon(item.id, item.icon, cellPx)
                }
                for (item in snap.top) {
                    if (item.isGroup) {
                        item.memberPkgs.take(9).forEachIndexed { i, pkg ->
                            if (seenPreview.add(pkg)) {
                                writeIcon(pkg, item.memberIcons.getOrNull(i), 32)
                            }
                        }
                    } else {
                        persistApp(item)
                    }
                }
                for ((_, members) in snap.groups) members.forEach { persistApp(it) }
                // Structure JSON.
                val root = org.json.JSONObject()
                root.put("v", 1)
                root.put("key", snap.key)
                root.put("iconDp", snap.iconDp)
                root.put("showLabels", snap.showLabels)
                root.put("cellPx", cellPx)
                val topArr = org.json.JSONArray()
                for (item in snap.top) {
                    val o = org.json.JSONObject()
                    o.put("g", item.isGroup)
                    o.put("id", item.id)
                    o.put("label", item.label)
                    if (item.isGroup) {
                        o.put("count", item.memberCount)
                        o.put("members", org.json.JSONArray(item.memberPkgs))
                    }
                    topArr.put(o)
                }
                root.put("top", topArr)
                val groupsObj = org.json.JSONObject()
                for ((gid, members) in snap.groups) {
                    val arr = org.json.JSONArray()
                    for (m in members) {
                        val o = org.json.JSONObject()
                        o.put("id", m.id)
                        o.put("label", m.label)
                        arr.put(o)
                    }
                    groupsObj.put(gid, arr)
                }
                root.put("groups", groupsObj)
                val tmp = java.io.File(dir, "snapshot.json.tmp")
                tmp.writeText(root.toString())
                tmp.renameTo(java.io.File(dir, "snapshot.json"))
                persistedKey = snap.key
            }
        }.start()
    }

    /**
     * The persisted snapshot, whatever its signature / cell size
     * (see the never-empty rule on [snapshot]). Icon paths use
     * the STORED cell size — that is the size the PNG files on
     * disk were actually written at.
     */
    private fun loadFromDisk(
        context: Context,
    ): AppGridSnapshotStore.Snapshot? = runCatching {
        val file = java.io.File(snapshotDir(context), "snapshot.json")
        if (!file.exists()) return null
        val root = org.json.JSONObject(file.readText())
        val storedKey = root.optString("key")
        if (storedKey.isEmpty()) return null
        val cellPx = root.optInt("cellPx")
        fun iconPath(packageName: String, sizePx: Int): String? {
            val f = iconFile(context, packageName, sizePx)
            return if (f.exists()) f.path else null
        }
        val groups = LinkedHashMap<String, List<AppGridSnapshotStore.Item>>()
        val groupsObj = root.getJSONObject("groups")
        val gids = groupsObj.keys()
        while (gids.hasNext()) {
            val gid = gids.next()
            val arr = groupsObj.getJSONArray(gid)
            val members = ArrayList<AppGridSnapshotStore.Item>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val pkg = o.getString("id")
                members += AppGridSnapshotStore.Item(
                    isGroup = false,
                    id = pkg,
                    label = o.optString("label"),
                    icon = null,
                    iconPath = iconPath(pkg, cellPx),
                )
            }
            groups[gid] = members
        }
        val topArr = root.getJSONArray("top")
        val top = ArrayList<AppGridSnapshotStore.Item>(topArr.length())
        for (i in 0 until topArr.length()) {
            val o = topArr.getJSONObject(i)
            val id = o.getString("id")
            if (o.optBoolean("g")) {
                val memberPkgs = ArrayList<String>()
                val mArr = o.optJSONArray("members")
                if (mArr != null) {
                    for (j in 0 until mArr.length()) memberPkgs += mArr.getString(j)
                }
                top += AppGridSnapshotStore.Item(
                    isGroup = true,
                    id = id,
                    label = o.optString("label"),
                    icon = null,
                    memberCount = o.optInt("count", memberPkgs.size),
                    memberPkgs = memberPkgs,
                    memberIconPaths = memberPkgs.take(9).map { iconPath(it, 32) },
                )
            } else {
                top += AppGridSnapshotStore.Item(
                    isGroup = false,
                    id = id,
                    label = o.optString("label"),
                    icon = null,
                    iconPath = iconPath(id, cellPx),
                )
            }
        }
        if (top.isEmpty()) return null
        AppGridSnapshotStore.Snapshot(
            key = storedKey,
            iconDp = root.optInt("iconDp", 44),
            showLabels = root.optBoolean("showLabels", true),
            top = top,
            groups = groups,
        )
    }.getOrNull()
}
