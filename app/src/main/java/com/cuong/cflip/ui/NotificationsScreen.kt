package com.cuong.cflip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.service.NotificationController
import com.cuong.cflip.service.NotificationState
import java.text.DateFormat
import java.util.Date

/** Cover notification hub (2.0): one glass card per notification, pill actions, inline reply. */
@Composable
fun NotificationsScreen(modifier: Modifier = Modifier) {
    val notifications by NotificationState.active.collectAsState()
    var replyTarget by remember { mutableStateOf<String?>(null) }
    var replyText by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Thông báo", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = CFlipColors.TextPrimary)
            if (notifications.isNotEmpty()) {
                StatusPill(icon = null, text = notifications.size.toString())
            }
        }
        if (!NotificationController.isConnected()) {
            Text("Chưa cấp quyền truy cập thông báo — vào màn hình chính của CFlip để cấp quyền.",
                fontSize = 12.sp, color = CFlipColors.Danger, modifier = Modifier.padding(top = 4.dp))
        }
        if (replyTarget != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                OutlinedTextField(value = replyText, onValueChange = { replyText = it },
                    placeholder = { Text("Trả lời...") }, singleLine = true,
                    shape = RoundedCornerShape(20.dp), modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    NotificationController.reply(replyTarget!!, replyText)
                    replyText = ""; replyTarget = null
                }) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = CFlipColors.Accent) }
            }
        }
        if (notifications.isEmpty()) {
            EmptyState(Icons.Filled.NotificationsOff, "Không có thông báo",
                "Thông báo mới sẽ hiện ở đây")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 8.dp)) {
                items(notifications, key = { it.key }) { n ->
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(n.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = CFlipColors.TextPrimary, maxLines = 1,
                                    modifier = Modifier.weight(1f))
                                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(n.timeMs)),
                                    fontSize = 11.sp, color = CFlipColors.TextFaint)
                            }
                            if (n.text.isNotEmpty()) {
                                Text(n.text, fontSize = 13.sp, color = CFlipColors.TextSecondary,
                                    maxLines = 3, modifier = Modifier.padding(top = 2.dp))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 8.dp)) {
                                ActionChip("Mở") { NotificationController.open(n.key) }
                                ActionChip("Xoá") { NotificationController.dismiss(n.key) }
                                if (n.canReply) ActionChip("Trả lời") { replyTarget = n.key }
                                n.actionTitles.forEachIndexed { index, title ->
                                    if (title.isNotEmpty()) {
                                        ActionChip(title) { NotificationController.clickAction(n.key, index) }
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

/** Small pill button used for notification actions. */
@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier.clip(CircleShape)
            .background(CFlipColors.CardFill)
            .border(1.dp, CFlipColors.CardStroke, CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CFlipColors.Accent)
    }
}
