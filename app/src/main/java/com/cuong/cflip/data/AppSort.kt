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
    /**
     * Vietnamese alphabetical order for app labels (user,
     * 2026-11-21): the Vietnamese alphabet treats ă/â/đ/ê/ô/ơ/ư
     * as letters of their own (Đ sorts right after D) and orders
     * tone marks within the same base letter, so "Đồng hồ" sits
     * with the Ds and "Đài" precedes "Đèn". Plain lowercase()
     * string order is Unicode code-point order, which throws
     * every accented label behind Z ("Đồng hồ" after "Zalo").
     * A vi-VN Collator implements exactly these rules. A fresh
     * instance per call: Collator is not thread-safe, and sorts
     * here are list-sized, never per-frame. Equal labels fall
     * back to package name so the order is fully deterministic.
     */
    fun labelComparator(): Comparator<AppInfo> {
        val collator = java.text.Collator.getInstance(java.util.Locale("vi", "VN"))
        return Comparator { a, b ->
            val c = collator.compare(a.label, b.label)
            if (c != 0) c else a.packageName.compareTo(b.packageName)
        }
    }

    fun sort(
        apps: List<AppInfo>,
        sortMode: Int,
        recentOrder: List<String>,
        installTimes: Map<String, Long>,
    ): List<AppInfo> = when (sortMode) {
        1 -> {
            val recentIdx = recentOrder.withIndex().associate { it.value to it.index }
            apps.sortedWith(compareBy<AppInfo> { recentIdx[it.packageName] ?: Int.MAX_VALUE }
                .then(labelComparator()))
        }
        2 -> apps.sortedWith(compareByDescending<AppInfo> { installTimes[it.packageName] ?: 0L }
            .then(labelComparator()))
        // Mode 0 is a PASS-THROUGH, not a sort: the A-Z order
        // already lives in AppRepository's output, and callers
        // feed this function lists whose order is user-defined -
        // above all custom-group members in their stored order
        // and edited auto groups. Sorting here would let the
        // alphabet override the user's own arrangement
        // (user rule, 2026-11-21: grouped apps with a set-up
        // order are never affected by the alphabet).
        else -> apps
    }
}
