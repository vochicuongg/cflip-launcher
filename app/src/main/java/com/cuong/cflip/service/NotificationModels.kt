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
    val canReply: Boolean = false,
)

/** Shared, observable snapshot of active notifications for the cover UI. */
object NotificationState {
    val active = MutableStateFlow<List<CoverNotification>>(emptyList())
}

/** Bridge from UI to the running listener service (set when the service connects). */
object NotificationController {
    @Volatile var listener: MyNotificationListenerService? = null

    fun open(key: String) { listener?.openByKey(key) }
    fun dismiss(key: String) { listener?.dismissByKey(key) }
    fun clickAction(key: String, index: Int) { listener?.clickActionByKey(key, index) }
    fun reply(key: String, message: String) { listener?.replyByKey(key, message) }
    fun isConnected(): Boolean = listener != null
}
