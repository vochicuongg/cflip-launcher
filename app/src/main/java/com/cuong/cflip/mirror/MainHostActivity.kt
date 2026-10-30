package com.cuong.cflip.mirror

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

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
        const val ACTION_MIRROR_STOP = "com.cuong.cflip.mirror.STOP_HOST"
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

    // vc94 MAIN-BRIGHT FIX: MainHost is the ONLY window
    // CFlip holds on MAIN with KEEP_SCREEN_ON + TurnScreenOn
    // (vc86, to stop the main locking during mirror). It used
    // to finish only on its 1s poll after MirrorState went
    // false, and 9Flip has no force-off either - it simply
    // stops keeping main awake, so main then follows the
    // normal screen timeout. To make CFlip release main
    // deterministically at stop time (not on poll luck),
    // cleanupSession now broadcasts STOP and this host
    // finishes at once, releasing KEEP_SCREEN_ON. After
    // that, main follows the user's own timeout - it is
    // NOT instantly locked (instant lock needs device-admin
    // lockNow, which neither CFlip nor 9Flip uses).
    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_MIRROR_STOP) {
                Log.i(TAG, "Mirror stop broadcast, finishing main host (vc94)")
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            registerReceiver(stopReceiver, IntentFilter(ACTION_MIRROR_STOP), Context.RECEIVER_NOT_EXPORTED)
        }
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
        // vc201 FOCUS FIX: this 1x1 host is the ONLY CFlip window
        // on MAIN, and as a resumed focusable activity it holds
        // Display 0's input focus - so touches injected to main
        // land on this 1x1 window (which ignores them) instead
        // of the launcher/apps. A nav/home gesture is a SystemUI
        // gesture that bypasses it and resets focus to the
        // launcher - that is why ONLY a gesture ever unlocked
        // touch (vc200 proved waiting pre-feed never does).
        // NOT_FOCUSABLE leaves focus on the launcher while this
        // host keeps its vc86 keep-awake job (KEEP_SCREEN_ON
        // works regardless of focus), so the feed stays alive
        // and touches work from the first frame.
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        // vc202 INPUT-INVISIBLE: MainHost's theme is Translucent.
        // NoTitleBar (NOT floating), so window.setLayout(1,1) is
        // ignored and this window is actually FULLSCREEN -
        // transparent, but it still eats every touch on main
        // (NOT_TOUCH_MODAL passes only touches OUTSIDE the window,
        // and a fullscreen window has no outside). That is the
        // user's "invisible layer": taps land on this empty window
        // and die, while SystemUI gestures (nav swipe) bypass
        // windows entirely - which is why only Recents+exit ever
        // cleared it (Recents puts the launcher back on top).
        // vc201's NOT_FOCUSABLE alone could not fix it, because a
        // not-focusable window is still a touch target.
        // NOT_TOUCHABLE removes this window from touch dispatch
        // completely, so taps fall through to the launcher below,
        // while the host stays alive and resumed for its vc86
        // keep-awake job (KEEP_SCREEN_ON is unaffected).
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.setLayout(1, 1)
        window?.setGravity(Gravity.TOP or Gravity.START)
        window?.attributes?.dimAmount = 0f
        startPolling()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(stopReceiver) }
        pollingStopped = true
        mainHandler.removeCallbacks(pollRunnable)
        super.onDestroy()
    }
}
