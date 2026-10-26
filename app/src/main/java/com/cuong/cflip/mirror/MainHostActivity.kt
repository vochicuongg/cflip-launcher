package com.cuong.cflip.mirror

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity

/**
 * vc86 MAIN-SIDE HOST (9Flip host-on-main pattern, own code).
 * vc85 test: device was UNLOCKED before tapping mirror, dialog
 * showed on the cover, yet the screen LOCKED immediately after
 * agree and no feed appeared. vc85's show-over-lock flags on the
 * cover windows did not prevent it, because the lock is on the
 * MAIN side, not the cover side: during mirror, CFlip had NO
 * activity alive on the main display at all (DualHost is on the
 * cover, permission is on the cover, Presentation is on the
 * cover). A folded main display with no foreground activity
 * goes to sleep and locks, and then there is no live main UI
 * for AUTO_MIRROR to capture — the mirror dies at the lock.
 *
 * 9Flip solves this by keeping its 1x1 host top-resumed on MAIN
 * for the whole mirror (its host reparents to main after dual
 * and stays there). This activity is CFlip's equivalent, launched
 * by MirrorPermissionActivity right after grant, explicitly on
 * display 0:
 *   - 1x1 transparent NOT_TOUCH_MODAL window (same as DualHost),
 *     so it never covers or blocks the main UI when unfolded.
 *   - KEEP_SCREEN_ON + setShowWhenLocked(true) +
 *     setTurnScreenOn(true) via the modern Activity setters (not
 *     the deprecated window flags vc85 used on the cover side),
 *     so the main display stays awake for capture.
 *   - Polls MirrorState.active every 1000ms. It does NOT finish
 *     immediately if active is still false at launch (service
 *     needs a moment to set it): it waits up to 5000ms for the
 *     first active=true, then finishes if active never appears
 *     (gate failure — service cleanup owns dual/bubble after
 *     that). Once active was seen, active=false means mirror
 *     stopped, so it finishes at once.
 *   - onDestroy NEVER disables dual and never restores the
 *     bubble: this host is a keep-alive for the main display
 *     only. Dual disable belongs to ScreenMirrorService cleanup
 *     (and the permission decline branch), never to a host death
 *     (vc82 lesson, applied here structurally as well).
 */
class MainHostActivity : Activity() {
    companion object {
        private const val TAG = "CFlipMainHost"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pollingStopped = false
    private var seenActive = false
    private var startTimeMs = 0L

    private val pollRunnable = Runnable {
        if (!isFinishing && !pollingStopped) {
            if (MirrorState.active) {
                seenActive = true
                startPolling()
            } else if (seenActive) {
                Log.i(TAG, "Mirror stopped, finishing main host (vc86)")
                finishAndRemoveTask()
            } else if (System.currentTimeMillis() - startTimeMs > 5000L) {
                Log.i(TAG, "Mirror never became active in 5s, finishing main host (vc86)")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startTimeMs = System.currentTimeMillis()
        // Modern Activity setters (API 27+, minSdk 33 here), not the
        // deprecated window flags: keep main awake and allow this
        // host over lock while mirror runs.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setFinishOnTouchOutside(false)
        window?.setBackgroundDrawable(ColorDrawable(0))
        window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.setLayout(1, 1)
        window?.setGravity(Gravity.TOP or Gravity.START)
        window?.attributes?.dimAmount = 0f
        startPolling()
    }

    override fun onDestroy() {
        pollingStopped = true
        mainHandler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }
}
