package com.cuong.cflip.data

import android.content.Context

/** Records apps launched FROM CFlip, so Recents works even without Usage Access. */
class LaunchHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("launch_history", Context.MODE_PRIVATE)

    fun record(packageName: String) {
        val now = System.currentTimeMillis()
        val count = prefs.getInt(pkgCount(packageName), 0)
        prefs.edit()
            .putLong(pkgTime(packageName), now)
            .putInt(pkgCount(packageName), count + 1)
            .apply()
    }

    /** package -> last launch time (only apps launched from CFlip) */
    fun lastLaunched(): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        for ((key, value) in prefs.all) {
            if (key.startsWith("t_") && value is Long) out[key.removePrefix("t_")] = value
        }
        return out
    }

    private fun pkgTime(pkg: String) = "t_$pkg"
    private fun pkgCount(pkg: String) = "c_$pkg"
}
