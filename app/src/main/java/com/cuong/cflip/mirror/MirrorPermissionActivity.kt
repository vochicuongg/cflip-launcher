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

    // vc67: guards a second enableDualThenCapture call (e.g.
    // onNewIntent arriving while the first settle delay is still
    // pending) from scheduling a duplicate capture dialog.
    private var dualCaptureScheduled = false

    // vc67 HOST-BEFORE-DUAL (9Flip decompile, architecture only,
    // no copied code): 9Flip toggles dual INSIDE a 1x1
    // NOT_TOUCH_MODAL transparent host activity that is already
    // on the cover, registers the SCREEN_OFF receiver, and only
    // then shows its cover UI. CFlip vc51-vc66 did the opposite:
    // the tile enabled dual from a panel overlay (no activity
    // alive), then waited 2000ms with nothing alive on the cover
    // in dual mode - that is the 1-2s window in the vc66 repro
    // (panel -> mirror -> wait 1-2s -> screen off completely),
    // before the permission activity even exists. New order:
    // this activity is launched on the cover FIRST (the tile no
    // longer enables dual), it enables dual itself while its own
    // window is alive on the cover, waits for dual to settle,
    // then shows the capture dialog. "dual already on" from a
    // previous attempt is not a failure: settle briefly, then
    // show the dialog. On dual failure, toast + finish without
    // a dialog; dual was never turned on, so nothing to disable.
    // Decline still disables dual (existing branch below).
    private fun enableDualThenCapture() {
        if (dualCaptureScheduled) return
        dualCaptureScheduled = true
        val dualRes = com.cuong.cflip.service.DualDisplayHelper.enable()
        if (dualRes.isFailure) {
            Toast.makeText(this, "Dual display failed: " + (dualRes.exceptionOrNull()?.message ?: "unknown"), Toast.LENGTH_LONG).show()
            finishAndRemoveTask()
            return
        }
        val settleMs = if (dualRes.getOrNull() == "dual already on") 500L else 2000L
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                launchCapture()
            }
        }, settleMs)
    }

    // vc62 SECOND-START DEFENSE, updated vc67: if the system
    // still reuses this activity, go through the same
    // enable-then-dialog flow (guarded against duplicates),
    // not straight to launchCapture, so a reused activity
    // cannot skip the dual step.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enableDualThenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableDualThenCapture()
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
