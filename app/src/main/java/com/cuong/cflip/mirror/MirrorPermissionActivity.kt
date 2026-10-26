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
 * vc84 DIALOG-ONLY permission activity (vc83 split + cover-dialog fixes).
 * vc83 test (27/10): NO permission dialog on the cover, only the
 * 180-degree rotation, then nothing. User requirement for vc84: the
 * dialog MUST appear on the cover (màn phụ); the user will not
 * unfold to check the main display.
 *
 * vc84 changes in this file only (host changes are in
 * DualHostActivity; no service, RotateHost, widget or foreground change):
 * 1. Dialog launched from onResume, NOT onCreate. vc83 launched the
 *    capture intent in onCreate, before the activity was foreground;
 *    a system dialog requested from a not-yet-resumed 1x1 activity
 *    is the prime suspect for "dialog never appears". onResume is
 *    guarded by captureLaunched (restored across recreation via
 *    savedInstanceState), so a config-change recreation cannot show
 *    a second dialog.
 * 2. Cover-display guard in onResume: if this instance ended up on
 *    the MAIN display (dual reparent, vc69 pattern) instead of the
 *    cover, it does NOT show the dialog on main. It hands over ONCE
 *    to a fresh instance explicitly launched on the cover
 *    (NEW_TASK | CLEAR_TASK + setLaunchDisplayId(cover), extra
 *    "cflip_relaunched_once" so the new instance never relaunches
 *    again and cannot loop). If that handover itself lands on main,
 *    the dialog is shown there as a last resort rather than looping.
 * 3. Dual check stays in onCreate (dual OFF -> toast + finish, never
 *    enable dual here — DualHostActivity owns dual).
 * Grant still starts ScreenMirrorService IMMEDIATELY (no delay, no
 * bridge, no main host); decline still disables dual + restores the
 * bubble; onDestroy still only disables dual when no mirror started.
 */
class MirrorPermissionActivity : ComponentActivity() {
    private var captureLaunched = false
    private var serviceStarted = false
    private var relaunchingOnCover = false
    // vc85 BRIDGE: handler + poller that keeps this cover window
    // alive after grant until the service's feed window takes over.
    private val bridgeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var bridgeRunnable: Runnable? = null
    private var bridgeAttempts = 0

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            Toast.makeText(this, "Đang phản chiếu lên cover", Toast.LENGTH_SHORT).show()
            serviceStarted = true
            val start = Intent(this, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_START
                putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, start)
            // vc85 BRIDGE (vc84 lock gap): vc84 finished this activity
            // the instant grant arrived, leaving a windowless gap on
            // the cover between dialog dismiss and Presentation.show()
            // — the cover fell back to lock in that gap. This window
            // (now TURN_SCREEN_ON + SHOW_WHEN_LOCKED) stays alive
            // until the service sets MirrorState.active (Presentation
            // shown) or 16 x 300ms ~= 4.8s timeout, whichever first.
            // Gate failure never sets active, so the timeout still
            // finishes and service cleanup owns dual/bubble after that.
            startBridgeAndFinish()
        } else {
            Toast.makeText(this, "Bạn chưa đồng ý phản chiếu màn hình", Toast.LENGTH_SHORT).show()
            runCatching { com.cuong.cflip.service.DualDisplayHelper.disable() }
            restoreBubble()
            finishAndRemoveTask()
        }
    }

    private fun startBridgeAndFinish() {
        bridgeAttempts = 0
        bridgeRunnable = Runnable {
            if (MirrorState.active || bridgeAttempts >= 16 || isFinishing) {
                finishAndRemoveTask()
            } else {
                bridgeAttempts++
                bridgeRunnable?.let { bridgeHandler.postDelayed(it, 300L) }
            }
        }
        bridgeRunnable?.let { bridgeHandler.post(it) }
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
        // singleTop reuse: do not launch a second dialog if one is
        // already pending; onResume below handles the cover guard.
        if (!captureLaunched && !serviceStarted) launchCaptureIfOnCover()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captureLaunched = savedInstanceState?.getBoolean("cflip_capture_launched") ?: false
        setFinishOnTouchOutside(false)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // vc85 LOCK FIX: wake the cover and allow this window over
        // lock, so the dialog/bridge window cannot be swallowed by
        // the cover lock screen between dialog dismiss and feed show.
        window?.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        window?.setLayout(1, 1)
        window?.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
        // Dual must already be ON (DualHostActivity enabled it).
        // If OFF, this dialog would be stale — refuse, never enable dual here.
        if (!com.cuong.cflip.service.DualDisplayHelper.isDualOn()) {
            Toast.makeText(this, "Phản chiếu chưa bật màn hình kép, thử bấm lại", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }
        // vc84: do NOT launchCapture() here. onResume below does it,
        // once the activity is foreground on its display.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("cflip_capture_launched", captureLaunched)
    }

    override fun onResume() {
        super.onResume()
        if (!captureLaunched && !serviceStarted && !relaunchingOnCover && !isFinishing) {
            launchCaptureIfOnCover()
        }
    }

    /** vc84 cover guard: dialog must be requested from the cover
     * display. If this instance is on main and it has not already
     * been relaunched once, hand over to a fresh cover instance
     * instead of showing the dialog on main (which a folded user
     * can never see). One handover only — the new instance carries
     * "cflip_relaunched_once" and launches capture wherever it lands. */
    private fun launchCaptureIfOnCover() {
        val cover = runCatching {
            com.cuong.cflip.display.DisplayRepository(this).secondaryDisplay()
        }.getOrNull()
        val myDisplayId = display?.displayId
        val alreadyRelaunched = intent?.getBooleanExtra("cflip_relaunched_once", false) == true
        if (cover != null && myDisplayId != null && myDisplayId != cover.displayId && !alreadyRelaunched) {
            relaunchingOnCover = true
            val relaunch = Intent(this, MirrorPermissionActivity::class.java)
                .putExtra("cflip_relaunched_once", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val opts = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(cover.displayId).toBundle()
            runCatching { startActivity(relaunch, opts) }
            finishAndRemoveTask()
            return
        }
        launchCapture()
    }

    override fun onDestroy() {
        bridgeRunnable?.let { bridgeHandler.removeCallbacks(it) }
        // Only the dialog instance may disable dual, and only when
        // no mirror started (back-press / destroy mid-setup).
        // Grant sets serviceStarted=true first, so a just-started
        // mirror is never killed here. MirrorState.active=true means
        // the service already owns dual — never touch it here.
        // A cover handover (relaunchingOnCover) must NOT disable dual
        // either: the fresh cover instance needs it.
        if (!serviceStarted && !relaunchingOnCover && !MirrorState.active) {
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
