package com.cuong.cflip.data

/**
 * vc255 SHARED APP SORT — one ordering for all three grid surfaces
 * (cover grid in CoverApp, Quick Panel grid in CoverControlCenter,
 * AppGrid widget factory). Before vc255 each surface had its own
 * inline copy and they drifted: the panel and the widget quietly
 * ignored sortMode changes the cover honoured. Modes: 0 = A-Z
 * (AppRepository already returns A-Z), 1 = recently opened
 * (recent order first, the rest A-Z), 2 = newly installed
 * (firstInstallTime descending, the rest A-Z). Callers that need
 * favourites-first apply it on top (CoverApp does); the panel and
 * the widget do not, they have no favourite star affordance.
 */
object AppSort {
    fun sort(
        apps: List<AppInfo>,
        sortMode: Int,
        recentOrder: List<String>,
        installTimes: Map<String, Long>,
    ): List<AppInfo> = when (sortMode) {
        1 -> {
            val recentIdx = recentOrder.withIndex().associate { it.value to it.index }
            apps.sortedWith(compareBy<AppInfo> { recentIdx[it.packageName] ?: Int.MAX_VALUE }
                .thenBy { it.label.lowercase() })
        }
        2 -> apps.sortedWith(compareByDescending<AppInfo> { installTimes[it.packageName] ?: 0L }
            .thenBy { it.label.lowercase() })
        else -> apps
    }
}
