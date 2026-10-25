package com.cuong.cflip.mirror

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.cuong.cflip.service.CoverForegroundService
import com.cuong.cflip.service.ScreenMirrorService

/**
 * vc32: dedicated MediaProjection permission activity, like 9Flip's
 * MirrorPermissionActivity in architecture only (own code, no copy).
 * The panel Mirror tile launches this on the main display; the system
 * capture dialog appears there, the user approves, and this activity
 * hands the one-shot result to ScreenMirrorService and finishes.
 * If the user declines, it just toasts and finishes — no mirror starts.
 */
class MirrorPermissionActivity : ComponentActivity() {
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // Normal cover UI steps aside, then the mirror takes over.
            val hide = Intent(this, CoverForegroundService::class.java).apply {
                action = CoverForegroundService.ACTION_HIDE_COVER
            }
            ContextCompat.startForegroundService(this, hide)
            val start = Intent(this, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_START
                putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, start)
            Toast.makeText(this, "Đang phản chiếu lên cover", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Bạn chưa đồng ý phản chiếu màn hình", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            Toast.makeText(this, "Thiết bị không hỗ trợ phản chiếu", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        captureLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
