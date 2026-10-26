package com.cuong.cflip.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Lists every app that can be launched - the data source for the cover app drawer. */
class AppRepository(private val context: Context) {

    /**
     * Launchable apps, alphabetically. Apps the user hid (HiddenAppsStore)
     * are filtered out here so every surface - Compose UI, Presentation,
     * widget, bubble - respects the hidden list. Pass includeHidden = true
     * only for the management UI that lists hidden apps to unhide them.
     */
    fun loadLaunchableApps(includeHidden: Boolean = false): List<AppInfo> {
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
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        if (includeHidden) return apps
        val hidden = runCatching { HiddenAppsStore(context).getHidden() }.getOrDefault(emptySet())
        if (hidden.isEmpty()) return apps
        return apps.filterNot { it.packageName in hidden }
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
