package com.cuong.cflip.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cuong.cflip.data.SettingsStore
import com.cuong.cflip.mirror.MirrorState
import com.cuong.cflip.service.CoverForegroundService
import com.cuong.cflip.service.ScreenMirrorService

/**
 * "Mirror & Dual Display" section (lives at the bottom of the Quick tab):
 *  - Phan chieu: mirror the main screen onto the cover display via
 *    MediaProjection (system consent dialog every session - Android rule).
 *  - Xoay cover: rotate cover content 0/90/180/270 (applies to the cover
 *    launcher Presentation and to the mirror feed).
 *  - Hai man hinh: keep the cover UI showing while the main screen is in use.
 */
@Composable
fun MirrorControls() {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    var settings by remember { mutableStateOf(store.load()) }
    val mirrorActive = MirrorState.active

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun coverService(action: String? = null) {
        val i = Intent(context, CoverForegroundService::class.java)
        if (action != null) i.action = action
        ContextCompat.startForegroundService(context, i)
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // Normal cover UI steps aside, then the mirror takes the cover display.
            coverService(CoverForegroundService.ACTION_HIDE_COVER)
            val i = Intent(context, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_START
                putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, i)
            toast("Dang phan chieu len cover - gập dang flex hoac mo may de xem")
        } else {
            toast("Ban chua dong y phan chieu man hinh")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Phản chiếu & Hai màn hình", fontSize = 18.sp)
        Text(
            "Phan chieu: sao chep man hinh chinh len cover theo thoi gian thuc. " +
                "Luu y: gap kin thi man hinh chinh tat nen anh phan chieu se den - " +
                "che do nay dep nhat khi de flex/nghieng hoac bat 2 man hinh.",
            fontSize = 12.sp,
        )

        if (!mirrorActive) {
            Button(onClick = {
                val mpm = context.getSystemService(MediaProjectionManager::class.java)
                if (mpm != null) captureLauncher.launch(mpm.createScreenCaptureIntent())
                else toast("Thiet bi khong ho tro MediaProjection")
            }, modifier = Modifier.fillMaxWidth()) { Text("Bật phản chiếu lên cover") }
        } else {
            Button(onClick = {
                context.startService(Intent(context, ScreenMirrorService::class.java)
                    .setAction(ScreenMirrorService.ACTION_STOP))
                coverService() // restore the normal cover launcher
            }, modifier = Modifier.fillMaxWidth()) { Text("Dừng phản chiếu") }
        }

        Text("Xoay nội dung cover: ${settings.coverRotation}°", fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (deg in listOf(0, 90, 180, 270)) {
                OutlinedButton(onClick = {
                    settings = settings.copy(coverRotation = deg)
                    store.save(settings)
                    if (MirrorState.active) {
                        context.startService(Intent(context, ScreenMirrorService::class.java)
                            .setAction(ScreenMirrorService.ACTION_APPLY_ROTATION))
                    } else {
                        coverService(CoverForegroundService.ACTION_ROTATION_CHANGED)
                    }
                }) { Text("$deg°") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = settings.dualDisplay, onCheckedChange = { on ->
                settings = settings.copy(dualDisplay = on)
                store.save(settings)
                if (on) coverService() else coverService(CoverForegroundService.ACTION_HIDE_COVER)
            })
            Text("Hai màn hình cùng lúc (giao diện cover chạy cả khi đang dùng màn hình chính)", fontSize = 13.sp)
        }
    }
}
