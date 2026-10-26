package com.cuong.cflip.mirror

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Toast

/**
 * vc83 DEDICATED DUAL HOST (9Flip host-first flow, own code, no copy).
 * 9Flip toggles dual inside a 1x1 transparent host activity that is
 * already on the cover, and only THEN shows its permission dialog
 * (permission itself never touches dual). CFlip vc51-vc82 did the
 * opposite: the permission activity enabled dual itself in onCreate
 * (before it was top-resumed), then had to survive dual reparenting
 * (relaunch), settle delays, a 2s grant delay and a 4s bridge, while
 * a second host instance (permission reused as host) could kill dual
 * from its onDestroy (vc81 pill dualOn=false). That inversion is why
 * mirror kept failing while 9Flip worked with the same transact.
 *
 * New order (host-first, like 9Flip):
 *   Tile -> this host (cover, 1x1, top-resumed) -> dual ON
 *        -> MirrorPermissionActivity (cover, dialog only)
 *        -> grant -> ScreenMirrorService START immediately -> feed.
 * This host never shows a dialog, never captures, never owns the
 * mirror UI. It only: enables dual once it is top-resumed (Samsung
 * accepts dual toggles from a foreground top-resumed activity, not
 * from onCreate or from a background service), launches permission
 * on the cover once dual is ON (so permission never reparents and
 * never needs relaunch), then stays alive polling dual until mirror
 * stops (service cleanup disables dual, polling sees dual OFF and
 * finishes this host). Host death never disables dual (host is a
 * keep-alive, not the dual owner; only service cleanup and the
 * permission decline branch may disable dual).
 * Window: 1x1 transparent NOT_TOUCH_MODAL, TOP|START, no dim, like
 * 9Flip's dual host. KEEP_SCREEN_ON is kept (deviation from 9Flip,
 * justified: main must stay ON for AUTO_MIRROR to capture while
 * folded; 9Flip relies on short sessions, CFlip keeps main on).
 * No SCREEN_OFF receiver (deviation, justified: CFlip service
 * onStop already handles system stop, and host polling finishes
 * this host when service cleanup disables dual; stopping mirror
 * on every screen-off would change behaviour the user did not ask
 * for). No integrity/generation/request-ID tracking (9Flip internal
 * safety, not needed here: tile launches this host with CLEAR_TASK,
 * so every tap gets a fresh host, no stale-host reuse).
 */
class DualHostActivity : Activity() {
    companion object {
        private const val TAG = "CFlipDualHost"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dualEnabled = false
    private var permissionLaunched = false
    private var pollingStopped = false

    private val pollRunnable = Runnable {
        if (!isFinishing && !pollingStopped) {
            if (!com.cuong.cflip.service.DualDisplayHelper.isDualOn()) {
                Log.i(TAG, "Dual OFF, finishing host (vc83)")
                finishAndRemoveTask()
            } else {
                startPolling()
            }
        }
    }

    private fun startPolling() {
        if (!pollingStopped && !isFinishing) {
            mainHandler.postDelayed(pollRunnable, 1000L)
        }
    }

    private fun launchPermissionOnCover() {
        if (permissionLaunched || isFinishing) return
        val cover = runCatching {
            com.cuong.cflip.display.DisplayRepository(this).secondaryDisplay()
        }.getOrNull()
        if (cover == null) {
            Toast.makeText(this, "Không tìm thấy màn phụ", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }
        permissionLaunched = true
        val permIntent = Intent(this, MirrorPermissionActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(cover.displayId).toBundle()
        runCatching { startActivity(permIntent, opts) }
        // Do NOT finish here: this host must stay alive on its display
        // (it may reparent to main after dual, like 9Flip's host) while
        // permission dialogs and the mirror service runs. Polling below
        // finishes it once service cleanup disables dual.
        startPolling()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 1x1 transparent host window, like 9Flip's dual host and like
        // CFlip's old permission host window (same standard flags).
        setFinishOnTouchOutside(false)
        window?.setBackgroundDrawable(ColorDrawable(0))
        window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.setLayout(1, 1)
        window?.setGravity(Gravity.TOP or Gravity.START)
        window?.attributes?.dimAmount = 0f
        // Do NOT enable dual here (onCreate, before top-resumed).
        // 9Flip toggles dual only when top-resumed; enabling in
        // onCreate (old CFlip permission flow) is unreliable.
        // Enabling happens in onTopResumedActivityChanged below.
    }

    override fun onTopResumedActivityChanged(isTopResumed: Boolean) {
        super.onTopResumedActivityChanged(isTopResumed)
        if (!isTopResumed || isFinishing || dualEnabled) return
        // Top-resumed foreground activity: Samsung accepts the toggle.
        val res = com.cuong.cflip.service.DualDisplayHelper.enable()
        if (res.isFailure) {
            Toast.makeText(
                this,
                "Dual display failed: " + (res.exceptionOrNull()?.message ?: "unknown"),
                Toast.LENGTH_LONG
            ).show()
            finishAndRemoveTask()
            return
        }
        dualEnabled = true
        Log.i(TAG, "Dual enabled from top-resumed host, launching permission (vc83)")
        launchPermissionOnCover()
    }

    override fun onDestroy() {
        // Host never owns dual: death here must NOT disable dual
        // (vc82 lesson, now structural: separate host, no shared
        // onDestroy disable branch at all). Service cleanup owns
        // dual disable; polling already finished this host when
        // dual went OFF, so nothing else to clean here.
        pollingStopped = true
        mainHandler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }
}
