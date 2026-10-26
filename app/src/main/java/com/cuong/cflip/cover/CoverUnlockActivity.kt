package com.cuong.cflip.cover

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * vc95 LOCK BUBBLE (9Flip UnlockActivity architecture, own code).
 *
 * Problem (vc91-vc94): the AssistiveTouch bubble is a
 * TYPE_APPLICATION_OVERLAY window. Samsung hides such overlays
 * over a secure keyguard no matter what FLAG_SHOW_WHEN_LOCKED
 * is set (vc91 flag proved this on device). 9Flip does NOT
 * show its menu over lock either: when locked and the user
 * calls the menu, it opens CoverFloatingMenuUnlockActivity
 * (setShowWhenLocked, transparent), which requests dismiss
 * keyguard, then opens the menu 120ms after unlock.
 *
 * This activity is CFlip's equivalent, with one difference
 * the user explicitly chose (27/10): it IS visible on lock.
 * It is a real Activity window (setShowWhenLocked), so the
 * keyguard cannot hide it, and it draws the same "C" bubble
 * (same saved position, same size factor, same mint style
 * as CoverAssistiveTouchManager) on the cover display.
 * Tapping the bubble does NOT open the panel directly:
 * panel is an overlay and would be hidden over lock too.
 * Instead it requests dismiss keyguard (biometric / PIN /
 * pattern, the system's own prompt, nothing is bypassed):
 *   - dismiss succeeds -> finish, then ask the foreground
 *     service to show the normal overlay bubble (now that
 *     the phone is unlocked, the overlay is visible again).
 *   - dismiss cancelled / errors -> finish silently, no
 *     retry loop, no prompt spam.
 *   - already unlocked on resume (user unlocked via main) ->
 *     finish at once and ask for the overlay bubble.
 *
 * Safety guards, mirroring 9Flip's generation guard:
 *   - singleTop in the manifest, so repeated launches reuse
 *     this instance instead of stacking activities.
 *   - mirror active -> finish at once (mirror Presentation
 *     already owns the cover over lock; never fight it).
 *   - assistiveTouchEnabled OFF -> finish at once (user
 *     turned the bubble off; lock bubble must respect it).
 *   - a FINISH_UNLOCK broadcast (sent by the foreground
 *     service when it sees the keyguard unlocked) finishes
 *     this activity, so it cannot linger after an unlock
 *     that happened via the main display.
 */
class CoverUnlockActivity : Activity() {
    companion object {
        private const val TAG = "CFlipUnlock"
        const val ACTION_FINISH_UNLOCK = "com.cuong.cflip.cover.FINISH_UNLOCK"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var bubbleShown = false
    private var unlockRequested = false

    private val finishReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_FINISH_UNLOCK) {
                Log.i(TAG, "Finish broadcast, closing lock bubble")
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity setters (not deprecated window flags):
        // this window is allowed over the keyguard and wakes
        // the cover, exactly like 9Flip's UnlockActivity.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setFinishOnTouchOutside(false)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        runCatching {
            registerReceiver(finishReceiver, IntentFilter(ACTION_FINISH_UNLOCK), Context.RECEIVER_NOT_EXPORTED)
        }

        val settings = com.cuong.cflip.data.SettingsStore(this).load()
        if (!settings.assistiveTouchEnabled) {
            finishAndRemoveTask()
            return
        }
        if (com.cuong.cflip.mirror.MirrorState.active) {
            finishAndRemoveTask()
            return
        }

        // Same saved position / size as the overlay bubble,
        // so the lock bubble sits where the user left it.
        val prefs = getSharedPreferences("assistive_touch", Context.MODE_PRIVATE)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val sizeFactor = when (settings.assistiveTouchSize) { 0 -> 0.8f; 2 -> 1.2f; else -> 1.0f }
        val size = (dp(56) * sizeFactor).toInt()
        val dispW = resources.displayMetrics.widthPixels
        val dispH = resources.displayMetrics.heightPixels
        val savedW = prefs.getInt("display_w", 0)
        val savedH = prefs.getInt("display_h", 0)
        var startX = prefs.getInt("x", dispW - size - dp(12))
        var startY = prefs.getInt("y", dispH / 2)
        if (savedW > 0 && savedH > 0) {
            startX = (startX.toFloat() * dispW / savedW).toInt()
            startY = (startY.toFloat() * dispH / savedH).toInt()
        }
        startX = startX.coerceIn(0, maxOf(0, dispW - size))
        startY = startY.coerceIn(0, maxOf(0, dispH - size))

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val btn = TextView(this).apply {
            text = "C"
            textSize = 24f * sizeFactor
            setTextColor(0xFF7FE3C0.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            elevation = dp(8).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF14473A.toInt(), 0xFF0B1F1A.toInt(), 0xFF08090A.toInt())
                gradientType = GradientDrawable.LINEAR_GRADIENT
                setStroke(dp(1), 0xFF7FE3C0.toInt())
            }
            // Tap only (no drag on lock: dragging over the
            // keyguard could fight the lock swipe). One tap
            // asks the system to dismiss the keyguard.
            setOnClickListener { requestUnlock() }
        }
        val lp = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = startX
            topMargin = startY
        }
        root.addView(btn, lp)
        setContentView(root)
        bubbleShown = true
    }

    override fun onResume() {
        super.onResume()
        // 9Flip b(): if the keyguard is already gone, do not
        // sit on top of the unlocked cover - finish and hand
        // back to the normal overlay bubble.
        if (!isKeyguardLocked()) {
            finishAndShowBubble()
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(finishReceiver) }
        super.onDestroy()
    }

    private fun isKeyguardLocked(): Boolean {
        val km = getSystemService(KeyguardManager::class.java) ?: return false
        return km.isKeyguardLocked
    }

    private fun requestUnlock() {
        if (unlockRequested) return
        val km = getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isKeyguardLocked) {
            finishAndShowBubble()
            return
        }
        unlockRequested = true
        // System's own dismiss prompt (biometric / PIN /
        // pattern). CFlip never sees or handles credentials.
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() {
                Log.i(TAG, "Keyguard dismissed from lock bubble")
                handler.postDelayed({ finishAndShowBubble() }, 120L)
            }

            override fun onDismissError() {
                Log.i(TAG, "Keyguard dismiss error, closing lock bubble")
                finishAndRemoveTask()
            }

            override fun onDismissCancelled() {
                Log.i(TAG, "Keyguard dismiss cancelled, closing lock bubble")
                finishAndRemoveTask()
            }
        })
    }

    private fun finishAndShowBubble() {
        if (isFinishing) return
        runCatching {
            applicationContext.startService(
                Intent(
                    applicationContext,
                    com.cuong.cflip.service.CoverForegroundService::class.java
                ).apply {
                    action = com.cuong.cflip.service.CoverForegroundService.ACTION_ROTATION_CHANGED
                }
            )
        }
        finishAndRemoveTask()
    }
}
