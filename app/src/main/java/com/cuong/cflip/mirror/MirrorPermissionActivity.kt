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
 * vc83 DIALOG-ONLY permission activity (9Flip host-first flow, own code).
 * vc67-vc82 made this activity do everything: enable dual itself in
 * onCreate (before top-resumed), settle, relaunch on reparent, delay
 * 2s after grant, bridge 4s, and act as the main-side host (hostOnly,
 * mainHostLaunched, relaunchingOnCover, dualCaptureScheduled). That
 * overload is why dual kept dying before the service gate (vc81 pill
 * dualOn=false: a host instance's onDestroy disabled dual).
 *
 * vc83 split (like 9Flip): DualHostActivity owns dual (enables it
 * only when top-resumed, polls it, never disables it on death).
 * This activity owns ONLY the system capture dialog:
 *   onCreate: dual OFF -> toast + finish (never enable dual here);
 *             dual ON  -> launchCapture() immediately.
 *   grant: start ScreenMirrorService IMMEDIATELY (no 2s delay, no
 *          4s bridge, no main-host launch), then finish.
 *   decline: disable dual + tell CoverForegroundService to restore
 *            the bubble, then finish.
 *   onDestroy: if service was NOT started and dual is still ON and
 *              mirror is not active, disable dual + restore bubble
 *              (covers back-press / system destroy mid-setup, the
 *              vc68 widget-break case). Grant path sets
 *              serviceStarted=true first, so a just-started mirror
 *              is never killed by its own activity finishing.
 * Window flags are kept from vc69/vc70: 1x1 transparent
 * NOT_TOUCH_MODAL + KEEP_SCREEN_ON, so this window never blocks
 * cover touches while the dialog is pending.
 */
class MirrorPermissionActivity : ComponentActivity() {
    private var captureLaunched = false
    private var serviceStarted = false

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // vc83: NO delay, NO bridge, NO main host. 9Flip starts
            // its projection service immediately on grant, and the
            // feed appears at once because its Presentation already
            // exists; CFlip's service creates its Presentation right
            // after start, so the only correct move here is to start
            // now and get out of the way. DualHostActivity stays
            // alive (polling dual) as the keep-alive host.
            Toast.makeText(this, "Đang phản chiếu lên cover", Toast.LENGTH_SHORT).show()
            serviceStarted = true
            val start = Intent(this, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_START
                putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, start)
            finishAndRemoveTask()
        } else {
            Toast.makeText(this, "Bạn chưa đồng ý phản chiếu màn hình", Toast.LENGTH_SHORT).show()
            runCatching { com.cuong.cflip.service.DualDisplayHelper.disable() }
            restoreBubble()
            finishAndRemoveTask()
        }
    }

    private fun restoreBubble() {
        runCatching {
            applicationContext.startService(
                android.content.Intent(
                    applicationContext,
                    com.cuong.cflip.service.CoverForegroundService::class.java
                ).apply {
                    action = com.cuong.cflip.service.CoverForegroundService.ACTION_ROTATION_CHANGED
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTop reuse (vc83 manifest): do not launch a second
        // dialog if one is already pending.
        if (!captureLaunched && !serviceStarted) launchCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.setLayout(1, 1)
        window?.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
        // vc83: dual must already be ON (DualHostActivity enabled it
        // when top-resumed). If it is OFF, this dialog would be
        // stale (9Flip refuses with dual_display_off in the same
        // case) — refuse here, never enable dual from this activity.
        if (!com.cuong.cflip.service.DualDisplayHelper.isDualOn()) {
            Toast.makeText(this, "Phản chiếu chưa bật màn hình kép, thử bấm lại", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }
        launchCapture()
    }

    override fun onDestroy() {
        // vc83: only the dialog instance may disable dual, and only
        // when no mirror started (back-press / destroy mid-setup).
        // serviceStarted=true (grant) must never reach this branch.
        // MirrorState.active=true means the service already owns
        // dual — never touch it here.
        if (!serviceStarted && !MirrorState.active) {
            if (com.cuong.cflip.service.DualDisplayHelper.isDualOn()) {
                runCatching { com.cuong.cflip.service.DualDisplayHelper.disable() }
                restoreBubble()
            }
        }
        super.onDestroy()
    }

    private fun launchCapture() {
        if (captureLaunched) return
        captureLaunched = true
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            Toast.makeText(this, "Thiết bị không hỗ trợ phản chiếu", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask(); return
        }
        val captureIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            mpm.createScreenCaptureIntent(config)
        } else {
            mpm.createScreenCaptureIntent()
        }
        captureLauncher.launch(captureIntent)
    }
}
