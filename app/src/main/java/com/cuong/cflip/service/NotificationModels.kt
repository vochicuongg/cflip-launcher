package com.cuong.cflip.service

import kotlinx.coroutines.flow.MutableStateFlow

/** UI-friendly snapshot of one active notification. */
data class CoverNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timeMs: Long,
    // vc217: StatusBarNotification id - for a LiveBridge mirror
    // this is abs(sourceKey.hashCode()), the exact key to its source.
    val notifId: Int = 0,
    val actionTitles: List<String> = emptyList(),
    // vc209: per-action reply flag (an action with RemoteInput
    // must never be fired bare - it needs typed input).
    val actionIsReply: List<Boolean> = emptyList(),
    // vc215: per-action creator package (PendingIntent
    // creator) - a mirror's plain action is the SOURCE app's own
    // intent, so its creator is the source app, not the mirror.
    val actionCreatorPackages: List<String?> = emptyList(),
    val canReply: Boolean = false,
    // vc252 NOW BAR: true when this row belongs in the Now Bar
    // group - it is an ongoing notification (music / call /
    // navigation / timer, sbn.isOngoing) or it has a LiveBridge
    // mirror (the Now Bar shows it through LiveBridge). The
    // list is already ordered Now Bar first, then the rest.
    val isNowBar: Boolean = false,
    // vc252 DISPLAY IDENTITY: launcher label of the app this row
    // is shown as. For a LiveBridge mirror this is the SOURCE
    // app's label - a mirror is never shown as LiveBridge.
    val appName: String = "",
    // vc252 DISPLAY ICON: launcher icon bitmap of the app this
    // row is shown as (source app for a mirror, never
    // LiveBridge's icon). Resolved in refresh() on the binder
    // thread, never on the UI thread; null = no icon available
    // (row falls back to the system app icon).
    val appIcon: android.graphics.Bitmap? = null,
)

/** Shared, observable snapshot of active notifications for the cover UI. */
object NotificationState {
    val active = MutableStateFlow<List<CoverNotification>>(emptyList())
}

/** Bridge from UI to the running listener service (set when the service connects). */
object NotificationController {
    @Volatile var listener: MyNotificationListenerService? = null

    // vc212: true when something actually launched, so the
    // panel only closes when the tap really went somewhere.
    fun open(key: String): Boolean = listener?.openByKey(key) ?: false
    fun dismiss(key: String) { listener?.dismissByKey(key) }
    fun clickAction(key: String, index: Int): Boolean = listener?.clickActionByKey(key, index) ?: false
    fun reply(key: String, message: String) { listener?.replyByKey(key, message) }
    fun isConnected(): Boolean = listener != null
}
