package com.cuong.cflip.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Recent apps for quick switching.
 * Best source: UsageStats (needs the user to grant Usage Access once).
 * Fallback: CFlip's own launch history - always available.
 */
class RecentAppsRepository(private val context: Context) {

    fun hasUsageAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun recentPackages(limit: Int = 12): List<String> {
        val fromStats = mutableListOf<Pair<String, Long>>()
        if (hasUsageAccess()) {
            val usm = context.getSystemService(UsageStatsManager::class.java)
            val now = System.currentTimeMillis()
            val stats = runCatching {
                usm?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 1000L * 60 * 60 * 24, now)
            }.getOrNull() ?: emptyList()
            for (s in stats) if (s.lastTimeUsed > 0) fromStats.add(s.packageName to s.lastTimeUsed)
        }
        val history = LaunchHistoryStore(context).lastLaunched()
        return RecentSorter.merge(fromStats, history, context.packageName, limit)
    }
}
