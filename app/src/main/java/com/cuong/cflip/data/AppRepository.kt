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
        val apps = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            .map { ri ->
                AppInfo(
                    packageName = ri.activityInfo.packageName,
                    label = ri.loadLabel(pm).toString(),
                    icon = runCatching { ri.loadIcon(pm) }.getOrNull(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        if (includeHidden) return apps
        val hidden = HiddenAppsStore(context).getHidden()
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
