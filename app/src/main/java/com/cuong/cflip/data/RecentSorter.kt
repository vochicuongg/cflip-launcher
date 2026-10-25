package com.cuong.cflip.data

/** Pure logic, unit-testable: merge usage-stats recents with CFlip launch history. */
object RecentSorter {
    /** Most recent first, duplicates removed, [limit] entries max. */
    fun merge(
        fromUsageStats: List<Pair<String, Long>>,
        fromHistory: Map<String, Long>,
        ownPackage: String,
        limit: Int = 12,
    ): List<String> {
        val best = mutableMapOf<String, Long>()
        for ((pkg, t) in fromUsageStats) best[pkg] = maxOf(best[pkg] ?: 0L, t)
        for ((pkg, t) in fromHistory) best[pkg] = maxOf(best[pkg] ?: 0L, t)
        best.remove(ownPackage)
        return best.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }
}
