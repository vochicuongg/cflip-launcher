package com.cuong.cflip.service

import kotlinx.coroutines.flow.MutableStateFlow

/** UI-friendly snapshot of one active notification. */
data class CoverNotification(
    val key: String,
    val packageName: String,
    val title: String,
    val text: String,
    val timeMs: Long,
    val actionTitles: List<String> = emptyList(),
    // vc209: per-action reply flag (an action with RemoteInput
    // must never be fired bare - it needs typed input).
    val actionIsReply: List<Boolean> = emptyList(),
    // vc215: per-action creator package (PendingIntent
    // creator) - a mirror's plain action is the SOURCE app's own
    // intent, so its creator is the source app, not the mirror.
    val actionCreatorPackages: List<String?> = emptyList(),
    val canReply: Boolean = false,
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
