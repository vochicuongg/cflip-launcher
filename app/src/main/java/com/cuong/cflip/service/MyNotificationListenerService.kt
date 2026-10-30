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
        val sbn = byKey[key] ?: return false
        val ci = sbn.notification.contentIntent
        if (ci != null) {
            val ok = runCatching {
                ci.send(this, 0, null, null, null, null, coverOptions())
            }.isSuccess
            if (ok) return true
            Log.w(TAG, "contentIntent send failed, falling back to launch")
        }
        // vc209: notifications with no content intent (or a failed
        // send) used to do nothing on tap. Launch the app instead,
        // so tapping a notification always lands somewhere.
        return runCatching {
            val launch = packageManager.getLaunchIntentForPackage(sbn.packageName) ?: return false
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opts = coverOptions()
            if (opts != null) startActivity(launch, opts) else startActivity(launch)
            true
        }.onFailure { Log.w(TAG, "launch app failed", it) }.getOrDefault(false)
    }

    fun dismissByKey(key: String) { runCatching { cancelNotification(key) } }

    fun clickActionByKey(key: String, index: Int) {
        val action = byKey[key]?.notification?.actions?.getOrNull(index) ?: return
        // vc209: a reply action fired without RemoteInput results
        // could post an empty reply. Open the app instead, so the
        // user replies with real text. (The panel routes reply
        // actions to open directly; this is the backstop.)
        if ((action.remoteInputs ?: emptyArray()).isNotEmpty()) { openByKey(key); return }
        // vc212: action intents that open UI get the same cover
        // routing as taps (background actions ignore the options).
        runCatching { action.actionIntent?.send(this, 0, null, null, null, null, coverOptions()) }
            .onFailure { Log.w(TAG, "action send failed", it) }
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
            runCatching { action.actionIntent?.send(this, 0, intent, null, null, null, coverOptions()) }
                .onFailure { Log.w(TAG, "reply send failed", it) }
            return
        }
    }

    companion object { private const val TAG = "CFlipNotifListener" }
}
