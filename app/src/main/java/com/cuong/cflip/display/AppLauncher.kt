package com.cuong.cflip.display

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cuong.cflip.data.LaunchHistoryStore

/** Launches an app, optionally routed to a specific display (the cover screen). */
object AppLauncher {
    private const val TAG = "AppLauncher"

    fun launch(context: Context, packageName: String, displayId: Int? = null) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: run {
            Log.w(TAG, "No launch intent for $packageName"); return
        }
        LaunchHistoryStore(context).record(packageName)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (displayId == null) { context.startActivity(intent); return }
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        runCatching { context.startActivity(intent, options.toBundle()) }
            .onFailure { Log.w(TAG, "Launch on display $displayId failed, falling back", it)
                // Fallback must not crash either (e.g. SecurityException for a
                // protected app): a failed launch is a log line, not a crash.
                runCatching { context.startActivity(intent) }
                    .onFailure { e -> Log.w(TAG, "Fallback launch also failed", e) } }
    }
}
