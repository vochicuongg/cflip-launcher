package com.cuong.cflip.service

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
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
        val models = mutableListOf<CoverNotification>()
        for (sbn in actives) {
            byKey[sbn.key] = sbn
            val extras = sbn.notification.extras
            // vc209: a notification with no title used to fall back
            // to the raw package name (user saw "com.xxx.xxx").
            // Empty is correct - the row already shows the app name.
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
            val actions = sbn.notification.actions ?: emptyArray()
            val canReply = actions.any { a -> (a.remoteInputs ?: emptyArray()).isNotEmpty() }
            models.add(
                CoverNotification(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    title = title,
                    text = text,
                    timeMs = sbn.postTime,
                    actionTitles = actions.map { it.title?.toString() ?: "" },
                    actionIsReply = actions.map { (it.remoteInputs ?: emptyArray()).isNotEmpty() },
                    actionCreatorPackages = actions.map { it.actionIntent?.creatorPackage },
                    canReply = canReply,
                )
            )
        }
        NotificationState.active.value = models.sortedByDescending { it.timeMs }
        // Keep the cover widget's notification count fresh.
        runCatching { com.cuong.cflip.widget.CoverWidgetProvider.requestUpdate(this) }
        // vc212: the open panel's notification tab rebuilds live,
        // so action results (liked / seen / replied) show at once
        // instead of leaving a stale row until reopen.
        runCatching { com.cuong.cflip.cover.CoverControlCenterManager.refreshNotifTab() }
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
        val sbn = byKey[key]
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
