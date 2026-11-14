package com.cuong.cflip.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Full notification hub: list, open, dismiss, action buttons and inline reply.
 * Grant access in: Settings > Notifications > Device and app notifications.
 */
class MyNotificationListenerService : NotificationListenerService() {

    private val byKey = mutableMapOf<String, StatusBarNotification>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationController.listener = this
        refresh()
        Log.i(TAG, "Listener connected, ${byKey.size} active")
    }

    override fun onListenerDisconnected() {
        if (NotificationController.listener === this) NotificationController.listener = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) { refresh() }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { refresh() }

    private fun refresh() {
        byKey.clear()
        val actives = runCatching { activeNotifications?.toList() }.getOrNull() ?: emptyList()
        for (sbn in actives) byKey[sbn.key] = sbn
        NotificationState.active.value = buildDisplayList(actives)
        // Keep the cover widget's notification count fresh.
        runCatching { com.cuong.cflip.widget.CoverWidgetProvider.requestUpdate(this) }
        // vc247: the clock + music widgets have no refresh of their own
        // (nothing ever called Clock.requestUpdate, Music had no such
        // function) — refresh them on the same notification tick, so
        // battery / notification counts and now-playing stay fresh
        // without a timer. Loop-free: requestUpdate only writes
        // RemoteViews, it never posts a notification.
        runCatching { com.cuong.cflip.widget.ClockWidgetProvider.requestUpdate(this) }
        runCatching { com.cuong.cflip.widget.MusicWidgetProvider.requestUpdate(this) }
        // vc212: the open panel's notification tab rebuilds live,
        // so action results (liked / seen / replied) show at once
        // instead of leaving a stale row until reopen.
        runCatching { com.cuong.cflip.cover.CoverControlCenterManager.refreshNotifTab() }
    }

    // vc252 DISPLAY LIST: the tab shows ONLY what is on the phone
    // right now, in two groups — Now Bar first, then the rest. No
    // history, no dismissed items, no deep dive (each row stays
    // shallow: icon + app + title + time + the old chips).
    // Runs on the listener (binder) thread, never the UI thread.
    private fun buildDisplayList(actives: List<StatusBarNotification>): List<CoverNotification> {
        // Step 1 - CFlip's own notifications never show.
        val ownFiltered = actives.filter { it.packageName != packageName }
        // Step 2 - group collapse (on the sbn level, before
        // mirrors): one representative row per group key.
        val grouped = collapseGroups(ownFiltered)
        // Step 3 - raw models (no icon/name yet; those resolve
        // only for survivors, so dropped mirrors/groups cost no
        // PackageManager calls).
        val raw = grouped.map { toRawModel(it) }
        // Step 4 - mirrors: a mirror never gets its own row. When
        // its source is active, the source row becomes Now Bar and
        // the mirror is dropped. When the source is gone but its
        // package is known, the mirror survives as ONE row shown as
        // the source app (source icon + name, mirror key for
        // open/dismiss). An orphan mirror with no identifiable
        // source is dropped — it cannot be shown as any app.
        val displayPkgByKey = mutableMapOf<String, String>()
        val mirroredSourceKeys = mutableSetOf<String>()
        for (m in raw.filter { LiveBridgeUnwrap.isMirrorPackage(it.packageName) }) {
            val target = LiveBridgeUnwrap.unwrapLiveBridge(m, raw)
            if (target != null) mirroredSourceKeys += target.key
            else LiveBridgeUnwrap.mirrorSourcePackage(m)?.let { displayPkgByKey[m.key] = it }
        }
        val kept = raw.filter { row ->
            if (!LiveBridgeUnwrap.isMirrorPackage(row.packageName)) true
            else row.key in displayPkgByKey
        }.map { row ->
            // vc252: a source with a mirror is Now Bar, and so is
            // an orphan mirror — it IS a LiveBridge Now Bar item,
            // only shown as its source app because the source
            // notification is gone.
            if (row.key in mirroredSourceKeys || row.key in displayPkgByKey) row.copy(isNowBar = true) else row
        }
        // Step 5 - display identity (launcher label + icon bitmap),
        // cached per distinct package WITHIN this refresh only (no
        // cross-refresh cache: an app update would leave a stale
        // icon behind). The icon is the app's LAUNCHER icon — the
        // same icon LiveBridge itself puts on its mirrors
        // (resolveAppIconAssets) and the same icon the panel's rows
        // already showed. A notification's smallIcon is a
        // status-bar glyph (often white-on-transparent), not a row
        // icon, so it is deliberately not used here.
        val labelByPkg = mutableMapOf<String, String>()
        val iconByPkg = mutableMapOf<String, Bitmap?>()
        val iconPx = (48 * resources.displayMetrics.density).toInt()
        val filled = kept.map { row ->
            val pkg = displayPkgByKey[row.key] ?: row.packageName
            row.copy(
                appName = labelByPkg.getOrPut(pkg) { appLabel(pkg) },
                appIcon = iconByPkg.getOrPut(pkg) { appIconBitmap(pkg, iconPx) },
            )
        }
        // Step 6 - Now Bar first (newest first), then the rest
        // (newest first). The panel renders the two sections in
        // this order.
        return filled.sortedWith(
            compareByDescending<CoverNotification> { it.isNowBar }.thenByDescending { it.timeMs }
        )
    }

    // vc252 GROUP COLLAPSE: one representative row per group key —
    // the summary when it carries title/text, else the newest
    // child. The summary is the app's own aggregate for the group;
    // an empty summary says nothing on the cover, so the newest
    // child stands in. Ungrouped notifications pass through.
    private fun collapseGroups(sbns: List<StatusBarNotification>): List<StatusBarNotification> {
        val grouped = sbns.filter { it.notification.group != null }.groupBy { it.notification.group!! }
        if (grouped.isEmpty()) return sbns
        val keepKeys = mutableSetOf<String>()
        for ((_, members) in grouped) {
            // vc253: Notification has NO isGroupSummary() method
            // (verified against android-35 android.jar — only
            // FLAG_GROUP_SUMMARY exists); a summary is a
            // notification whose flags carry FLAG_GROUP_SUMMARY.
            val summary = members.firstOrNull {
                (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            }
            val rep = if (summary != null && hasContent(summary)) summary
            else members.maxByOrNull { it.postTime }
            if (rep != null) keepKeys += rep.key
        }
        return sbns.filter { it.notification.group == null || it.key in keepKeys }
    }

    private fun hasContent(sbn: StatusBarNotification): Boolean {
        val e = sbn.notification.extras
        return e.getCharSequence(Notification.EXTRA_TITLE) != null ||
            e.getCharSequence(Notification.EXTRA_TEXT) != null ||
            e.getCharSequence(Notification.EXTRA_BIG_TEXT) != null
    }

    private fun toRawModel(sbn: StatusBarNotification): CoverNotification {
        val extras = sbn.notification.extras
        // vc209: a notification with no title used to fall back
        // to the raw package name (user saw "com.xxx.xxx").
        // Empty is correct - the row already shows the app name.
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        val actions = sbn.notification.actions ?: emptyArray()
        val canReply = actions.any { a -> (a.remoteInputs ?: emptyArray()).isNotEmpty() }
        return CoverNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            title = title,
            text = text,
            timeMs = sbn.postTime,
            notifId = sbn.id,
            actionTitles = actions.map { it.title?.toString() ?: "" },
            actionIsReply = actions.map { (it.remoteInputs ?: emptyArray()).isNotEmpty() },
            actionCreatorPackages = actions.map { it.actionIntent?.creatorPackage },
            canReply = canReply,
            // vc252: ongoing (music / call / navigation / timer)
            // belongs in the Now Bar group.
            isNowBar = sbn.isOngoing,
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.takeIf { it != pkg } ?: pkg.substringAfterLast('.')

    private fun appIconBitmap(pkg: String, sizePx: Int): Bitmap? {
        val d = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
            ?: runCatching { getDrawable(android.R.drawable.sym_def_app_icon) }.getOrNull()
            ?: return null
        return runCatching { drawableToBitmap(d, sizePx) }.getOrNull()
    }

    private fun drawableToBitmap(d: Drawable, sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(canvas)
        return bmp
    }

    private fun coverDisplayIdForLog(): String = runCatching {
        com.cuong.cflip.display.DisplayRepository(this).secondaryDisplay()?.displayId?.toString()
    }.getOrNull() ?: "none"

    /** ActivityOptions routing the opened UI to the cover display.
     *  vc212 ROOT CAUSE of "tap goes nowhere": content intents sent
     *  from this service used to land on the main display, which is
     *  OFF while folded - so the user saw nothing. Routing to the
     *  secondary (cover) display puts the app where they look. Null
     *  when there is no cover display (unfolded) = default routing. */
    private fun coverOptions(): android.os.Bundle? {
        val coverId = runCatching {
            com.cuong.cflip.display.DisplayRepository(this).secondaryDisplay()?.displayId
        }.getOrNull() ?: return null
        return android.app.ActivityOptions.makeBasic().setLaunchDisplayId(coverId).toBundle()
    }

    fun openByKey(key: String): Boolean {
        val sbn = byKey[key] ?: run {
            Log.i(TAG, "open: no sbn for key, listener has ${byKey.size} active")
            return false
        }
        Log.i(TAG, "open: pkg=${sbn.packageName} cover=${coverDisplayIdForLog()} hasContentIntent=${sbn.notification.contentIntent != null}")
        val ci = sbn.notification.contentIntent
        if (ci != null) {
            val res = runCatching {
                ci.send(this, 0, null, null, null, null, coverOptions())
            }
            Log.i(TAG, "open: contentIntent send ok=${res.isSuccess} err=${res.exceptionOrNull()?.message}")
            if (res.isSuccess) return true
            Log.w(TAG, "contentIntent send failed, falling back to launch")
        }
        // vc209: notifications with no content intent (or a failed
        // send) used to do nothing on tap. Launch the app instead,
        // so tapping a notification always lands somewhere.
        return runCatching {
            val launch = packageManager.getLaunchIntentForPackage(sbn.packageName) ?: run {
                Log.i(TAG, "open: no launch intent for ${sbn.packageName}")
                return false
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opts = coverOptions()
            if (opts != null) startActivity(launch, opts) else startActivity(launch)
            Log.i(TAG, "open: launch started for ${sbn.packageName}")
            true
        }.onFailure { Log.w(TAG, "launch app failed", it) }.getOrDefault(false)
    }

    fun dismissByKey(key: String) { runCatching { cancelNotification(key) } }

    fun clickActionByKey(key: String, index: Int): Boolean {
        // vc216: the tab's delayed send (500ms after launching the
        // app) can race a notification update/removal that drops
        // the key from byKey. Fall back to a fresh fetch instead
        // of giving up on a key that still exists.
        val sbn = byKey[key] ?: runCatching {
            activeNotifications?.find { it.key == key }
        }.getOrNull()?.also {
            Log.i(TAG, "action: byKey missed key=$key, fresh fetch found pkg=${it.packageName}")
        }
        val action = sbn?.notification?.actions?.getOrNull(index) ?: run {
            Log.i(TAG, "action: no action key=$key idx=$index (have ${byKey.size} active)")
            return false
        }
        Log.i(TAG, "action: pkg=${sbn.packageName} idx=$index isReply=${(action.remoteInputs ?: emptyArray()).isNotEmpty()} cover=${coverDisplayIdForLog()}")
        // vc209: a reply action fired without RemoteInput results
        // could post an empty reply. Open the app instead, so the
        // user replies with real text. (The panel routes reply
        // actions to open directly; this is the backstop.)
        if ((action.remoteInputs ?: emptyArray()).isNotEmpty()) return openByKey(key)
        // vc212: action intents that open UI get the same cover
        // routing as taps (background actions ignore the options).
        val res = runCatching { action.actionIntent?.send(this, 0, null, null, null, null, coverOptions()) }
        Log.i(TAG, "action: send ok=${res.isSuccess} err=${res.exceptionOrNull()?.message}")
        if (res.isSuccess) return true
        // vc214: a CanceledException usually means the stored copy
        // is stale (one-shot already consumed, or the app re-posted).
        // Retry once with a freshly fetched copy before giving up -
        // the panel hides the button when this still returns false.
        val freshAction = runCatching {
            activeNotifications?.find { it.key == key }?.notification?.actions?.getOrNull(index)
        }.getOrNull()
        if (freshAction != null && freshAction !== action) {
            val retry = runCatching { freshAction.actionIntent?.send(this, 0, null, null, null, null, coverOptions()) }
            Log.i(TAG, "action: fresh retry ok=${retry.isSuccess} err=${retry.exceptionOrNull()?.message}")
            if (retry.isSuccess) return true
        }
        res.exceptionOrNull()?.let { Log.w(TAG, "action send failed", it) }
        return false
    }

    /** Inline reply through the notification's RemoteInput, exactly like the shade reply. */
    fun replyByKey(key: String, message: String) {
        val sbn = byKey[key] ?: return
        val actions = sbn.notification.actions ?: return
        for (action in actions) {
            val inputs = action.remoteInputs ?: continue
            if (inputs.isEmpty()) continue
            val intent = Intent()
            val bundle = Bundle()
            for (ri in inputs) bundle.putCharSequence(ri.resultKey, message)
            RemoteInput.addResultsToIntent(inputs, intent, bundle)
            val res = runCatching { action.actionIntent?.send(this, 0, intent, null, null, null, coverOptions()) }
            Log.i(TAG, "reply: pkg=${sbn.packageName} ok=${res.isSuccess} err=${res.exceptionOrNull()?.message}")
            res.onFailure { Log.w(TAG, "reply send failed", it) }
            return
        }
    }

    companion object { private const val TAG = "CFlipNotifListener" }
}
