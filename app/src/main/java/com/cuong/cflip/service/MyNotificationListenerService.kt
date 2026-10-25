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
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: sbn.packageName
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
                    canReply = canReply,
                )
            )
        }
        NotificationState.active.value = models.sortedByDescending { it.timeMs }
        // Keep the cover widget's notification count fresh.
        runCatching { com.cuong.cflip.widget.CoverWidgetProvider.requestUpdate(this) }
    }

    fun openByKey(key: String) {
        val sbn = byKey[key] ?: return
        runCatching { sbn.notification.contentIntent?.send() }
            .onFailure { Log.w(TAG, "contentIntent failed", it) }
    }

    fun dismissByKey(key: String) { runCatching { cancelNotification(key) } }

    fun clickActionByKey(key: String, index: Int) {
        val action = byKey[key]?.notification?.actions?.getOrNull(index) ?: return
        runCatching { action.actionIntent?.send() }
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
            runCatching { action.actionIntent?.send(this, 0, intent) }
                .onFailure { Log.w(TAG, "reply send failed", it) }
            return
        }
    }

    companion object { private const val TAG = "CFlipNotifListener" }
}
