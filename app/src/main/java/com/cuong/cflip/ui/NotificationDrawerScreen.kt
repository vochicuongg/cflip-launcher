package com.cuong.cflip.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.service.NotificationController
import com.cuong.cflip.service.NotificationState

/**
 * vc245 NOTIFICATION DRAWER ("đảo cảnh báo", UI/UX Pro Max) — the
 * in-app notification list. There was no such screen before: the main
 * app only used NotificationState for counts (MainScreen) and the
 * cover had its own tab (NotificationsScreen.kt). This drawer follows
 * the AppDrawerScreen pattern (top bar + title + staggered list) and
 * reuses the exact same actions as the cover tab (Mở / Trả lời / Xoá
 * + per-action chips via NotificationController) — no logic change.
 *
 * Read/unread: CoverNotification has NO read field and the listener
 * service is untouched, so read state is session-local here (a
 * remembered set of keys, reset when the drawer closes). Tapping Mở
 * or sending a reply marks that notification read; unread rows show
 * a mint dot + bold title, read rows are plain. The cover tab uses
 * the same session-local rule (NotificationsScreen.kt).
 */
@Composable
fun NotificationDrawerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val hapticView = LocalView.current
    val notifications by NotificationState.active.collectAsState()
    var replyTarget by remember { mutableStateOf<String?>(null) }
    var replyText by remember { mutableStateOf("") }
    // Session-local read marks (see file doc): no service change.
    var readKeys by remember { mutableStateOf(setOf<String>()) }
    fun markRead(key: String) {
        if (key !in readKeys) readKeys = readKeys + key
    }
    val appRepo = remember { AppRepository(context) }
    val appMap = remember { appRepo.loadLaunchableApps().associateBy { it.packageName } }

    BackHandler { onBack() }
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(CFlipColors.Bg, Color(0xFF0B1512)))
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Quay lại", color = CFlipColors.Accent) }
                Spacer(Modifier.weight(1f))
                Text(
                    if (notifications.isEmpty()) "Không có thông báo"
                    else "${notifications.size} thông báo",
                    fontSize = 13.sp, color = CFlipColors.TextSecondary,
                )
            }
            Text(
                "Thông báo",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CFlipColors.TextPrimary,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            // Reply row (same pattern as the cover tab): unfolds in the
            // Column, never inside a Box (vc243 lesson).
            AnimatedVisibility(
                visible = replyTarget != null,
                enter = expandVertically(animationSpec = tween(CFlipMotion.MediumMs)) + fadeIn(animationSpec = tween(CFlipMotion.FastMs)),
                exit = shrinkVertically(animationSpec = tween(CFlipMotion.MediumMs)) + fadeOut(animationSpec = tween(CFlipMotion.FastMs)),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    OutlinedTextField(value = replyText, onValueChange = { replyText = it },
                        placeholder = { Text("Trả lời...") }, singleLine = true,
                        shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val key = replyTarget ?: return@IconButton
                        NotificationController.reply(key, replyText)
                        markRead(key)
                        replyText = ""; replyTarget = null
                    }) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Gửi",
                            tint = CFlipColors.Accent)
                    }
                }
            }
            if (notifications.isEmpty()) {
                EmptyState(
                    Icons.Filled.NotificationsOff, "Không có thông báo",
                    "Thông báo mới sẽ hiện ở đây",
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(notifications, key = { it.key }) { n ->
                        val unread = n.key !in readKeys
                        val app = appMap[n.packageName]
                        GlassCard(modifier = Modifier.fillMaxWidth(), compact = true) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (app?.icon != null) {
                                        AppIcon(app.icon, 40)
                                    } else {
                                        Box(
                                            modifier = Modifier.size(40.dp).clip(CircleShape)
                                                .background(CFlipColors.Accent.copy(alpha = 0.18f)),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(Icons.Filled.Notifications, contentDescription = null,
                                                tint = CFlipColors.Accent, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app?.label ?: n.packageName,
                                            fontSize = 11.sp, color = CFlipColors.TextFaint,
                                            maxLines = 1)
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (unread) {
                                                Box(
                                                    modifier = Modifier.size(6.dp).clip(CircleShape)
                                                        .background(CFlipColors.Accent)
                                                )
                                            }
                                            Text(n.title, fontSize = 14.sp,
                                                fontWeight = if (unread) FontWeight.Bold else FontWeight.SemiBold,
                                                color = CFlipColors.TextPrimary, maxLines = 1,
                                                modifier = Modifier.weight(1f))
                                            Text(formatNotifTime(n.timeMs),
                                                fontSize = 11.sp, color = CFlipColors.TextFaint)
                                        }
                                    }
                                }
                                if (n.text.isNotEmpty()) {
                                    Text(n.text, fontSize = 13.sp, color = CFlipColors.TextSecondary,
                                        maxLines = 2)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    NotifChip("Mở") {
                                        hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                        markRead(n.key)
                                        NotificationController.open(n.key)
                                    }
                                    if (n.canReply) NotifChip("Trả lời") {
                                        hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                        replyTarget = n.key
                                    }
                                    NotifChip("Xoá") {
                                        hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                        NotificationController.dismiss(n.key)
                                    }
                                    n.actionTitles.forEachIndexed { index, title ->
                                        if (title.isNotEmpty()) {
                                            NotifChip(title) {
                                                hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                                NotificationController.clickAction(n.key, index)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Small pill action, same look as the cover tab's chips, 48dp target. */
@Composable
private fun NotifChip(label: String, onClick: () -> Unit) {
    val pressSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(CFlipColors.CardFill)
            .border(1.dp, CFlipColors.CardStroke, CircleShape)
            .pressScale(pressSource)
            .clickable(interactionSource = pressSource, indication = LocalIndication.current) { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CFlipColors.Accent)
    }
}

/** Today -> "HH:mm", older -> "dd/MM HH:mm". */
private fun formatNotifTime(timeMs: Long): String {
    val cal = java.util.Calendar.getInstance()
    val nowDay = cal.get(java.util.Calendar.DAY_OF_YEAR)
    val nowYear = cal.get(java.util.Calendar.YEAR)
    cal.timeInMillis = timeMs
    val sameDay = cal.get(java.util.Calendar.DAY_OF_YEAR) == nowDay &&
        cal.get(java.util.Calendar.YEAR) == nowYear
    val pattern = if (sameDay) "HH:mm" else "dd/MM HH:mm"
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
        .format(java.util.Date(timeMs))
}
