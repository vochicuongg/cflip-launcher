package com.cuong.cflip.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.service.BubbleOverlayService

/** Floating bubble menu controls: CFlip's own overlay surface, no Good Lock needed. */
@Composable
fun BubbleControls() {
    val context = LocalContext.current
    var bubbleOn by remember { mutableStateOf(false) }
    val canOverlay = remember { Settings.canDrawOverlays(context) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Bong bóng menu (không cần Good Lock)", fontSize = 18.sp)
        Text(
            "Bong bong 'C' noi tren man hinh: cham de mo menu nhanh (app yeu thich, " +
                "den pin, nhac, khoa may). Keo de di chuyen bong bong.",
            fontSize = 12.sp,
        )
        if (!canOverlay) {
            OutlinedButton(onClick = {
                val i = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(i)
                Toast.makeText(context, "Bat 'Hien thi tren ung dung khac' cho CFlip", Toast.LENGTH_LONG).show()
            }) { Text("Cấp quyền hiển thị trên ứng dụng khác") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = bubbleOn, onCheckedChange = { on ->
                bubbleOn = on
                if (on) {
                    if (Settings.canDrawOverlays(context)) BubbleOverlayService.start(context)
                    else Toast.makeText(context, "Chua co quyen overlay", Toast.LENGTH_SHORT).show()
                } else BubbleOverlayService.stop(context)
            })
            Text("Hiện bong bóng menu", fontSize = 13.sp)
        }
    }
}
