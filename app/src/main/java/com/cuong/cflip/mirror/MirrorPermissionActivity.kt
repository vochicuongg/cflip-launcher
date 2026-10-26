package com.cuong.cflip.mirror

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
            // vc45: DO NOT hide the cover UI any more. vc44 (and
            // earlier) sent ACTION_HIDE_COVER here, which removed
            // the AssistiveTouch bubble - the only control that
            // could open the panel to stop the mirror again. The
            // user then saw "mất luôn AssistiveTouch": mirror on,
            // no bubble, no way back from the cover. Now the bubble
            // stays, and MirrorPresentation itself carries a
            // "Dừng phản chiếu" button as the primary exit, with
            // ScreenMirrorService restoring the bubble on stop as
            // a second path. The panel itself was already hidden
            // by the tile before this activity launched.
            // No hide intent is sent any more.
            val start = Intent(this, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_START
                putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, data)
            }
            // vc57 DUAL SETTLE: 9Flip does not start its projection
            // service the instant permission is granted - its dual
            // host waits for display reparenting (up to 2s) first.
            // CFlip vc51-vc56 started the service immediately on
            // approve, so the service gate could check main display
            // state before dual had finished powering it on, and
            // refuse a mirror that would have worked a second
            // later. Wait 2000ms after grant before starting the
            // service (panel already waited ~2000ms before the
            // dialog, so total settle is ~4-5s including dialog
            // time). Do NOT finish the activity until after the
            // delayed start, or the delayed runnable could be
            // lost with the activity.
            Toast.makeText(this, "Đang phản chiếu lên cover", Toast.LENGTH_SHORT).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                ContextCompat.startForegroundService(this, start)
                finishAndRemoveTask()
            }, 2000)
        } else {
            Toast.makeText(this, "Bạn chưa đồng ý phản chiếu màn hình", Toast.LENGTH_SHORT).show()
            // vc51: user declined capture - turn dual back off, do not leave system in dual mode.
            runCatching { com.cuong.cflip.service.DualDisplayHelper.disable() }
            // vc34: remove the task entirely, so this never looks like
            // re-entering the CFlip app in recents / task stack.
            // Grant path finishes inside its delayed block (vc57),
            // not here, so the delayed service start is not lost.
            finishAndRemoveTask()
        }
    }

    // vc62 SECOND-START DEFENSE: if the system still reuses
    // this activity via onNewIntent (singleTop residue), re-launch
    // the capture intent instead of showing a blank reused activity
    // with no dialog. Normal path still goes through onCreate.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchCapture()
    }

    private fun launchCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            Toast.makeText(this, "Thiết bị không hỗ trợ phản chiếu", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask(); return
        }
        // vc47 config intent (9Flip approach): on Android 14+
        // the capture intent should be built from
        // MediaProjectionConfig.createConfigForDefaultDisplay(),
        // which explicitly says "capture the DEFAULT (main)
        // display". The old no-config intent is kept as fallback
        // for API < 34. This is the modern path Samsung's One UI
        // 9 (Android 16+) expects for a mirror capture.
        val captureIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            mpm.createScreenCaptureIntent(config)
        } else {
            mpm.createScreenCaptureIntent()
        }
        captureLauncher.launch(captureIntent)
    }
}
