package com.cuong.cflip.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Lists every app that can be launched - the data source for the cover app drawer. */
class AppRepository(private val context: Context) {

    /**
     * Launchable apps, alphabetically. Apps the user hid (HiddenAppsStore)
     * are filtered out here so every surface - Compose UI, Presentation,
     * widget, bubble - respects the hidden list. Pass includeHidden = true
     * only for the management UI that lists hidden apps to unhide them.
     */
    companion object {
        // vc111 APP LIST CACHE (user, 27/10: widget
        // appgrid loads a lot): queryIntentActivities
        // + loadLabel + loadIcon for 100+ apps is the
        // expensive part of both the appgrid widget
        // and the Apps tab, and it used to run on
        // EVERY panel build / tab build. Now the
        // unfiltered list (package + label + icon)
        // is cached in memory after the first
        // successful query, and later calls return
        // a shallow copy of it (same AppInfo
        // objects, new list, so callers sorting the
        // result cannot corrupt the cache). Callers
        // must not mutate icon drawables directly -
        // CC already uses constantState.newDrawable()
        // for every button, which is cheap because
        // it reuses the cached constantState.
        // Invalidation, in order of strength:
        // 1. Package broadcasts (added / removed /
        //    replaced / changed) clear the cache
        //    immediately, so a newly installed app
        //    appears on the next build, not after
        //    a TTL. The receiver is registered once,
        //    with applicationContext, NOT_EXPORTED
        //    (system package broadcasts still reach
        //    it, other apps cannot).
        // 2. If receiver registration fails, caching
        //    is DISABLED (query every time) - slow
        //    but never stale, which is safer than
        //    a cache that cannot be invalidated.
        // 3. Empty query results are NEVER cached,
        //    so vc59 / vc71 transient-empty retries
        //    still re-query instead of caching zero.
        // Hidden apps are NOT part of the cache:
        // the hidden filter is applied per call
        // from the cached unfiltered list, so
        // hiding / unhiding takes effect at once.
        @Volatile private var cachedUnfiltered: List<AppInfo>? = null
        @Volatile private var cacheVersionValue: Int = 0
        @Volatile private var receiverRegistered: Boolean = false
        private val cacheLock = Any()
        fun appListVersion(): Int = cacheVersionValue
        fun clearAppListCache() {
            synchronized(cacheLock) {
                cachedUnfiltered = null
                cacheVersionValue++
            }
        }
        private fun ensurePackageReceiver(appContext: Context): Boolean {
            synchronized(cacheLock) {
                if (receiverRegistered) return true
                val ok = runCatching {
                    val filter = IntentFilter().apply {
                        addAction(Intent.ACTION_PACKAGE_ADDED)
                        addAction(Intent.ACTION_PACKAGE_REMOVED)
                        addAction(Intent.ACTION_PACKAGE_REPLACED)
                        addAction(Intent.ACTION_PACKAGE_CHANGED)
                        addDataScheme("package")
                    }
                    appContext.registerReceiver(object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            clearAppListCache()
                        }
                    }, filter, Context.RECEIVER_NOT_EXPORTED)
                }.isSuccess
                receiverRegistered = ok
                return ok
            }
        }
    }

    private fun filterHidden(apps: List<AppInfo>): List<AppInfo> {
        val hidden = runCatching { HiddenAppsStore(context).getHidden() }.getOrDefault(emptySet())
        if (hidden.isEmpty()) return apps
        return apps.filterNot { it.packageName in hidden }
    }

    fun loadLaunchableApps(includeHidden: Boolean = false): List<AppInfo> {
        val appContext = context.applicationContext
        val receiverOk = ensurePackageReceiver(appContext)
        if (receiverOk) {
            synchronized(cacheLock) {
                cachedUnfiltered?.let { cached ->
                    return if (includeHidden) cached.toList() else filterHidden(cached).toList()
                }
            }
        }
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        // vc71 WIDGET ROOT FIX: the old map called loadLabel()
        // unprotected. If ANY single app's label load threw (bad
        // resources, app updating mid-query, restricted profile),
        // the whole map threw, callers' runCatching turned it into
        // emptyList, and widget + apps tab both showed zero apps
        // even though 100+ apps were fine. Now each app falls back
        // to its package name as label, so one bad app can never
        // empty the whole list again. Icon was already guarded.
        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { ri ->
                val pkg = ri.activityInfo.packageName
                AppInfo(
                    packageName = pkg,
                    label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg),
                    icon = runCatching { ri.loadIcon(pm) }.getOrNull(),
                    // vc149 APP GROUPS: declared category for the
                    // grouper (UNDEFINED when the app declares none).
                    category = runCatching { ri.activityInfo.applicationInfo.category }
                        .getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        // vc111: cache only non-empty results,
        // and only when the package receiver is
        // alive to invalidate them (see companion).
        if (apps.isNotEmpty() && receiverOk) {
            synchronized(cacheLock) { cachedUnfiltered = apps }
        }
        if (includeHidden) return apps
        return filterHidden(apps)
    }

    /** First-install time per package, for the "newly installed" sort mode.
     *  Missing packages (uninstalled mid-query, restricted profiles) are
     *  simply absent - the caller falls back to alphabetical order. */
    fun installTimes(packages: List<String>): Map<String, Long> {
        val pm = context.packageManager
        val out = mutableMapOf<String, Long>()
        for (pkg in packages) {
            val t = runCatching { pm.getPackageInfo(pkg, 0).firstInstallTime }.getOrNull()
            if (t != null) out[pkg] = t
        }
        return out
    }
}
