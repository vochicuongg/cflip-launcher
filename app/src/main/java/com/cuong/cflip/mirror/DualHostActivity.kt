package com.cuong.cflip.mirror

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * vc84 DEDICATED DUAL HOST (vc83 host-first flow + cover-dialog fixes).
 * vc83 test (27/10): pull/build/install OK, but NO permission dialog
 * appeared on the cover — only the 180-degree rotation, then nothing.
 * User requirement for vc84: the dialog MUST appear on the cover
 * (màn hình ngoài); the user will not unfold to check the main display.
 *
 * vc84 changes, all in this host + permission (no service, no
 * RotateHost, no widget, no foreground change):
 * 1. Fallback enable: vc83 enabled dual ONLY in
 *    onTopResumedActivityChanged. If that callback never fires for a
 *    cover activity on this device, dual never enables and nothing
 *    follows — silently. Now onResume posts a 500ms fallback: if the
 *    top-resumed path has not enabled dual by then, enable here
 *    (guarded by launchStarted, so the normal path still wins first).
 * 2. Visible pills ON THE COVER (WindowManager overlay on the
 *    secondary display context, not a Toast that can land on the
 *    folded main display): "dual ON" and "permission launch failed"
 *    are no longer silent. A failed startActivity now finishes with
 *    a visible pill instead of leaving the user at a rotated cover
 *    with nothing happening.
 * 3. Panel is hidden by the tile before this host launches (panel
 *    change in vc84): a full-screen cover overlay must not sit on
 *    top of the system dialog.
 * Permission side (vc84, same release): dialog is launched from
 * onResume (not onCreate, so the activity is foreground when the
 * system dialog is requested), and if the permission activity
 * itself ended up on the main display it hands over once to a fresh
 * instance explicitly on the cover instead of showing the dialog
 * on main.
 * Host death still never disables dual (vc82 lesson, structural).
 */
class DualHostActivity : Activity() {
    companion object {
        private const val TAG = "CFlipDualHost"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var launchStarted = false
    private var permissionLaunched = false
    private var pollingStopped = false

    // vc190 NO-DUAL host lifecycle: dual is never enabled now
    // (PowerFlip parity), so the old dual-off finish can never
    // fire. Finish instead when (a) mirror ran and then stopped,
    // or (b) permission was launched but no mirror ever started
    // within 10s (decline / failure) — the host is 1x1 invisible
    // and must not linger into the next start, or its stale
    // permissionLaunched flag would block the next launch.
    private var sawActive = false
    private var permissionLaunchTimeMs = 0L
    private val pollRunnable = Runnable {
        if (!isFinishing && !pollingStopped) {
            if (MirrorState.active) sawActive = true
            val timedOut = permissionLaunched && !MirrorState.active &&
                android.os.SystemClock.uptimeMillis() - permissionLaunchTimeMs > 10_000L
            if ((sawActive && !MirrorState.active) || timedOut) {
                Log.i(TAG, "Host finishing: mirror ended or permission never led to mirror (vc190)")
                finishAndRemoveTask()
            } else {
                startPolling()
            }
        }
    }

    private val fallbackRunnable = Runnable {
        if (!isFinishing && !launchStarted) {
            Log.i(TAG, "Top-resumed callback never fired, fallback launch in onResume (vc190)")
            launchPermission()
        }
    }

    private fun startPolling() {
        if (!pollingStopped && !isFinishing) {
            mainHandler.postDelayed(pollRunnable, 1000L)
        }
    }

    /** Pill drawn on the COVER display context, so it is visible to a
     * folded user even if this host was reparented to main. Failures
     * here are swallowed: a pill must never break the mirror flow. */
    private fun showCoverPill(text: String) {
        runCatching {
            val cover = com.cuong.cflip.display.DisplayRepository(this).secondaryDisplay()
                ?: return@runCatching
            val dctx = createDisplayContext(cover)
            val wm = dctx.getSystemService(WindowManager::class.java) ?: return@runCatching
            val density = dctx.resources.displayMetrics.density
            val tv = TextView(dctx).apply {
                this.text = text
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xE0000000.toInt())
                setPadding((16 * density).toInt(), (12 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
                gravity = Gravity.CENTER
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            wm.addView(tv, params)
            mainHandler.postDelayed({ runCatching { wm.removeView(tv) } }, 2500L)
        }
    }

    // vc190 NO-DUAL (PowerFlip parity): PowerFlip never toggles
    // dual at all — its mirror Activity runs on the cover via
    // show_on_cover alone — and CFlip's 1-2s dead touch tracked
    // the dual window (vc189's pre-launch delay did not fix it).
    // So this host no longer enables dual; it only launches the
    // permission activity on the cover. If show_on_cover alone
    // does not put MirrorActivity on the cover, the service gate
    // (secondary display missing) fails with a pill, cleanly.
    private fun launchPermission() {
        if (isFinishing || launchStarted) return
        launchStarted = true
        Log.i(TAG, "Launching permission on cover, no dual (vc190)")
        showCoverPill("Đang mở dialog trên màn hình ngoài")
        launchPermissionOnCover()
    }

    private fun launchPermissionOnCover() {
        if (permissionLaunched || isFinishing) return
        val cover = runCatching {
            com.cuong.cflip.display.DisplayRepository(this).secondaryDisplay()
        }.getOrNull()
        if (cover == null) {
            Toast.makeText(this, "Không tìm thấy màn hình ngoài", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask()
            return
        }
        permissionLaunched = true
        permissionLaunchTimeMs = android.os.SystemClock.uptimeMillis()
        val permIntent = Intent(this, MirrorPermissionActivity::class.java)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        val opts = android.app.ActivityOptions.makeBasic()
            .setLaunchDisplayId(cover.displayId).toBundle()
        // vc84: a swallowed startActivity failure used to look exactly
        // like the vc83 symptom (rotated cover, nothing else). Surface it.
        val ok = runCatching { startActivity(permIntent, opts) }.isSuccess
        if (!ok) {
            showCoverPill("Không mở được dialog trên màn hình ngoài, thử bấm lại")
            finishAndRemoveTask()
            return
        }
        // Do NOT finish here: this host must stay alive on its display
        // (it may reparent to main after dual, like 9Flip's host) while
        // permission dialogs and the mirror service runs. Polling below
        // finishes it once service cleanup disables dual.
        startPolling()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        window?.setBackgroundDrawable(ColorDrawable(0))
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
        window?.setGravity(Gravity.TOP or Gravity.START)
        window?.attributes?.dimAmount = 0f
        // vc190: no dual anywhere now. The permission launch
        // happens in onTopResumedActivityChanged, with an
        // onResume fallback below for the case that callback never fires.
    }

    override fun onResume() {
        super.onResume()
        // vc84 fallback: top-resumed path normally enables dual within
        // milliseconds; if it never fires, this 500ms fallback enables
        // dual instead of leaving a rotated cover with no dialog.
        mainHandler.removeCallbacks(fallbackRunnable)
        if (!launchStarted) mainHandler.postDelayed(fallbackRunnable, 500L)
    }

    override fun onTopResumedActivityChanged(isTopResumed: Boolean) {
        super.onTopResumedActivityChanged(isTopResumed)
        if (!isTopResumed || isFinishing || launchStarted) return
        mainHandler.removeCallbacks(fallbackRunnable)
        launchPermission()
    }

    override fun onDestroy() {
        pollingStopped = true
        mainHandler.removeCallbacks(pollRunnable)
        mainHandler.removeCallbacks(fallbackRunnable)
        super.onDestroy()
    }
}
