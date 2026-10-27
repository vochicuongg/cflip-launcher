package com.cuong.cflip.cover

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.cuong.cflip.MainActivity
import com.cuong.cflip.service.MyAccessibilityService
import kotlin.math.abs

/**
 * AssistiveTouch for the SECONDARY (cover) display only — 25/10/2026, per Cường's
 * rule: the main display is custom/settings only and never shows a bubble;
 * the cover is where one-handed use is optimised.
 *
 * Clean-room implementation of the interaction pattern, not a copy: a small
 * draggable "C" button added through the secondary display's own WindowManager
 * (createDisplayContext, like CoverOverlay), with the three gestures:
 * - tap -> Home: a HOME intent routed to the cover display first, falling back
 *   to the AccessibilityService global Home action.
 * - long-press -> Recents via the AccessibilityService.
 * - double-tap -> Menu: opens CFlip's own cover UI at the Quick (Control Center)
 *   tab, on the cover display, via MainActivity intent extras.
 * Dragging repositions the button; the position is saved with the display size
 * at save time and clamped when the display changes, like the pattern in the
 * 9Flip analysis. Auto-dim drops the button to 40% alpha after 2500ms idle and
 * restores it on touch.
 *
 * This manager never touches the default display: show() returns failure if the
 * display is the default display, so a bug cannot leak the button onto the
 * main screen — the exact symptom Cường reported for the old bubble.
 */
object CoverAssistiveTouchManager {
    private const val TAG = "CFlipAssistiveTouch"
    private const val PREFS = "assistive_touch"
    private const val DIM_DELAY_MS = 2500L
    private const val DIM_ALPHA = 0.4f

    private var wm: WindowManager? = null
    private var button: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var displayId: Int = -1
    private val handler = Handler(Looper.getMainLooper())
    private var dimRunnable: Runnable? = null
    // 3.3.0 keep-awake: Samsung's cover idle timer does not count
    // touches on a NOT_FOCUSABLE overlay as cover activity, so the
    // cover slept while the user was still tapping/dragging the button.
    // 9Flip's button also has no KEEP_SCREEN_ON flag — its cover UI is
    // an Activity, which does count. CFlip's button is an overlay, so
    // it must do it explicitly with the public View.keepScreenOn API:
    // every touch re-arms keepScreenOn=true, and 30s after the last
    // touch it turns back off, so the cover can sleep normally when
    // the user really stops. No WAKE_LOCK permission, no forever-on.
    private var keepAwakeRunnable: Runnable? = null
    private fun pokeKeepAwake() {
        val b = button ?: return
        b.keepScreenOn = true
        keepAwakeRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { button?.keepScreenOn = false }
        keepAwakeRunnable = r
        handler.postDelayed(r, 30_000L)
    }

    fun isShown(): Boolean = button != null

    // vc33 brightness holder: the bubble window itself carries
    // screenBrightness while it is alive on the cover, so a brightness
    // chosen in the panel does NOT revert when the panel closes.
    // When the bubble is removed (phone unfolded), the window goes
    // away and brightness returns to system — cover-only scope, no
    // WRITE_SETTINGS, no effect on the main display.
    fun applyCoverBrightness(appContext: Context) {
        val params = bubbleParams; val v = button; val manager = wm
        if (params == null || v == null || manager == null) return
        val st = com.cuong.cflip.data.SettingsStore(appContext).load()
        params.screenBrightness = if (st.panelBrightnessManual) st.panelBrightness.coerceIn(0.05f, 1.0f) else -1f
        runCatching { manager.updateViewLayout(v, params) }
    }

    fun show(appContext: Context, display: Display): Result<Unit> = runCatching {
        if (display.displayId == Display.DEFAULT_DISPLAY) {
            throw IllegalArgumentException("AssistiveTouch is cover-only, refusing default display")
        }
        hide()
        // vc99 LOCK BUBBLE TYPE (9Flip ub.java: type =
        // a11y != null ? 2032 : fallback): CFlip's bubble
        // used 2038 (TYPE_APPLICATION_OVERLAY) + SHOW_WHEN
        // _LOCKED, which Samsung hides over a secure
        // keyguard (vc91 proof) - so vc95-vc98 had to use
        // an Activity on lock, and an Activity displaces
        // Samsung's lock UI (clock/calendar) even when
        // its window is bubble-sized (vc97-vc98 proof:
        // info still missing). A 2032 overlay window,
        // like RotateHost vc96, survives lock WITHOUT
        // being an Activity, so it should sit on lock
        // WITHOUT displacing lock info. Try 2032 via
        // the accessibility service display context
        // first; if the system rejects it, fall back to
        // 2038 in the same call (never worse than vc98).
        val a11ySvc = MyAccessibilityService.instance
        val displayContext = if (a11ySvc != null) a11ySvc.createDisplayContext(display) else appContext.createDisplayContext(display)
        val bubbleType = if (a11ySvc != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        var manager = displayContext.getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("No WindowManager for display ${display.displayId}")
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val density = displayContext.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        // Redesign 2.7.0: size from settings (0.8 / 1.0 / 1.2), mint-on-dark
        // button matching CFlip's own design system (not a copied icon).
        val settings = com.cuong.cflip.data.SettingsStore(appContext).load()
        val sizeFactor = when (settings.assistiveTouchSize) { 0 -> 0.8f; 2 -> 1.2f; else -> 1.0f }
        val autoDim = settings.assistiveTouchAutoDim
        val size = (dp(56) * sizeFactor).toInt()

        // Saved position, clamped to the current display size.
        val dispW = displayContext.resources.displayMetrics.widthPixels
        val dispH = displayContext.resources.displayMetrics.heightPixels
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

        // vc91 LOCK FIX (user: bubble must appear on lock screen
        // and in Settings, otherwise rotate controls are lost
        // exactly when the phone is locked): without
        // SHOW_WHEN_LOCKED the keyguard hides this overlay.
        val params = WindowManager.LayoutParams(
            size, size,
            bubbleType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = startX; y = startY }

        // Modern button 2.9.0: soft gradient (deep mint -> near-black),
        // thin mint ring, bold letter, drop shadow via elevation. No copied
        // icon — the "C" is CFlip's own mark, kept from earlier versions.
        val btn = TextView(displayContext).apply {
            text = "C"
            textSize = 24f * sizeFactor
            setTextColor(0xFF7FE3C0.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            elevation = dp(8).toFloat()
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xFF14473A.toInt(), 0xFF0B1F1A.toInt(), 0xFF08090A.toInt())
                gradientType = android.graphics.drawable.GradientDrawable.LINEAR_GRADIENT
                setStroke(dp(1), 0xFF7FE3C0.toInt())
            }
        }

        // Gesture state machine, using system timeouts (not hardcoded guesses):
        // long-press timeout for hold, double-tap timeout for the tap delay.
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        val touchSlop = ViewConfiguration.get(displayContext).scaledTouchSlop
        var downX = 0f; var downY = 0f; var paramsX = 0; var paramsY = 0
        var moved = false; var longPressed = false; var pendingSingleTap = false
        var doubleTapOpened = false
        var longPressRunnable: Runnable? = null
        var singleTapRunnable: Runnable? = null

        fun scheduleDim() {
            dimRunnable?.let { handler.removeCallbacks(it) }
            if (!autoDim) { btn.alpha = 1f; return }
            val r = Runnable { btn.alpha = DIM_ALPHA }
            dimRunnable = r
            handler.postDelayed(r, DIM_DELAY_MS)
        }
        fun cancelTapRunnables() {
            longPressRunnable?.let { handler.removeCallbacks(it) }
            singleTapRunnable?.let { handler.removeCallbacks(it) }
            longPressRunnable = null; singleTapRunnable = null
        }

        btn.setOnTouchListener { v, e ->
            // vc112 PRESS ANIMATION (user, 27/10:
            // smooth interaction): shrink to 0.9
            // on DOWN, spring back to 1.0 on UP /
            // CANCEL. Visual only - drag maths use
            // rawX/rawY and params, not view scale,
            // so gestures are untouched.
            if (e.action == MotionEvent.ACTION_DOWN) {
                v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(100L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
            if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
                v.animate().scaleX(1f).scaleY(1f).setDuration(150L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
            // 3.3.0: every touch on the cover button resets the cover
            // idle timer via keepScreenOn (see pokeKeepAwake above).
            // vc32: poke only on DOWN, not on every MOVE (handler churn per move event was part of the stutter).
            if (e.action == MotionEvent.ACTION_DOWN) pokeKeepAwake()
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    btn.alpha = 1f
                    dimRunnable?.let { handler.removeCallbacks(it) }
                    // vc41 fast double-tap: previously the panel opened
                    // only on ACTION_UP of the second tap, so the user
                    // paid a full tap duration before anything happened.
                    // Now, if a first tap is already pending, the second
                    // DOWN opens the panel immediately (no long-press
                    // scheduled for this DOWN, no waiting for UP).
                    // Trade-off, stated honestly: a tap-tap-drag that
                    // starts a drag on the second tap will open the
                    // panel instead of dragging - drags normally start
                    // on the first touch, not the second, so this is
                    // the standard double-tap trade-off for speed.
                    if (pendingSingleTap) {
                        pendingSingleTap = false
                        singleTapRunnable?.let { handler.removeCallbacks(it) }
                        singleTapRunnable = null
                        // vc99: on lock, double-tap must
                        // NOT open the panel (panel is an
                        // overlay, hidden/displacing on
                        // lock). Go to unlock instead.
                        if (isKeyguardLocked(appContext)) {
                            launchUnlockActivity(appContext, display.displayId)
                            scheduleDim()
                            return@setOnTouchListener true
                        }
                        doubleTapOpened = true
                        downX = e.rawX; downY = e.rawY
                        paramsX = params.x; paramsY = params.y
                        moved = false; longPressed = false
                        val dtAction = com.cuong.cflip.data.SettingsStore(appContext).load().bubbleDoubleTapAction
                        performBubbleAction(dtAction, appContext, display.displayId)
                        scheduleDim()
                        return@setOnTouchListener true
                    }
                    doubleTapOpened = false
                    downX = e.rawX; downY = e.rawY
                    paramsX = params.x; paramsY = params.y
                    moved = false; longPressed = false
                    val r = Runnable {
                        longPressed = true
                        val action = com.cuong.cflip.data.SettingsStore(appContext).load().bubbleLongPressAction
                        performBubbleAction(action, appContext, display.displayId)
                    }
                    longPressRunnable = r
                    handler.postDelayed(r, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // vc33: cancel long-press on ANY move, not only after
                    // slop. A slow swipe that paused under slop used to
                    // fire Recents mid-drag and kill the drag.
                    if (longPressRunnable != null) {
                        handler.removeCallbacks(longPressRunnable!!)
                        longPressRunnable = null
                    }
                    // vc32: Euclidean distance (like 9Flip c1 hypot),
                    // not Manhattan; keep float until the final round.
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (kotlin.math.hypot(dx, dy) > touchSlop) {
                        if (!moved) {
                            cancelTapRunnables(); moved = true
                            // Elevation shadow is recomputed on every
                            // updateViewLayout; drop it during drag.
                            v.elevation = 0f
                        }
                        val newX = Math.round(paramsX + dx).coerceIn(0, maxOf(0, dispW - size))
                        val newY = Math.round(paramsY + dy).coerceIn(0, maxOf(0, dispH - size))
                        // Skip redundant relayouts (9Flip b6 does the same).
                        if (newX != params.x || newY != params.y) {
                            params.x = newX; params.y = newY
                            runCatching { manager.updateViewLayout(v, params) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // vc41: UP belonging to the DOWN that already opened
                    // the panel must not start a new pending single tap
                    // (which would later fire Home over the panel).
                    if (doubleTapOpened) {
                        doubleTapOpened = false
                        v.elevation = dp(8).toFloat()
                        scheduleDim()
                        return@setOnTouchListener true
                    }
                    cancelTapRunnables()
                    v.elevation = dp(8).toFloat()
                    if (moved) {
                        // vc35 snap to nearest side edge, like a floating
                        // bubble should: centre left of mid -> left edge,
                        // centre right of mid -> right edge, animated
                        // 150ms Decelerate, then save the snapped position.
                        val targetX = if (params.x + size / 2 < dispW / 2) 0 else maxOf(0, dispW - size)
                        if (targetX != params.x) {
                            val startX = params.x
                            val anim = android.animation.ValueAnimator.ofInt(startX, targetX).apply {
                                duration = 150L
                                interpolator = android.view.animation.DecelerateInterpolator()
                                addUpdateListener { a ->
                                    params.x = a.animatedValue as Int
                                    runCatching { manager.updateViewLayout(v, params) }
                                }
                                addListener(object : android.animation.AnimatorListenerAdapter() {
                                    override fun onAnimationEnd(animation: android.animation.Animator) {
                                        prefs.edit()
                                            .putInt("x", params.x).putInt("y", params.y)
                                            .putInt("display_w", dispW).putInt("display_h", dispH)
                                            .apply()
                                        scheduleDim()
                                    }
                                })
                            }
                            anim.start()
                        } else {
                            prefs.edit()
                                .putInt("x", params.x).putInt("y", params.y)
                                .putInt("display_w", dispW).putInt("display_h", dispH)
                                .apply()
                            scheduleDim()
                        }
                        return@setOnTouchListener true
                    }
                    if (longPressed) { scheduleDim(); return@setOnTouchListener true }
                    // vc99: on lock, a tap must NOT fire
                    // Home/Menu (both blocked on lock).
                    // It asks the system to dismiss the
                    // keyguard via UnlockActivity, exactly
                    // like 9Flip's transient unlock flow.
                    if (isKeyguardLocked(appContext)) {
                        launchUnlockActivity(appContext, display.displayId)
                        scheduleDim()
                        return@setOnTouchListener true
                    }
                    if (pendingSingleTap) {
                        // Second tap inside the double-tap window -> configured double-tap action.
                        pendingSingleTap = false
                        val dtAction2 = com.cuong.cflip.data.SettingsStore(appContext).load().bubbleDoubleTapAction
                        performBubbleAction(dtAction2, appContext, display.displayId)
                    } else {
                        pendingSingleTap = true
                        val r = Runnable {
                            pendingSingleTap = false
                            val tapAction = com.cuong.cflip.data.SettingsStore(appContext).load().bubbleTapAction
                            performBubbleAction(tapAction, appContext, display.displayId)
                        }
                        singleTapRunnable = r
                        handler.postDelayed(r, doubleTapTimeout)
                    }
                    scheduleDim()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    doubleTapOpened = false
                    cancelTapRunnables(); pendingSingleTap = false
                    v.elevation = dp(8).toFloat()
                    scheduleDim(); true
                }
                else -> false
            }
        }

        // vc33: 9Flip sets gesture exclusion to the whole button rect,
        // so edge swipes do not steal the drag. CFlip never set it, so a
        // bubble near the edge lost MOVE events to the system back/home
        // gesture (ACTION_CANCEL) — the "swipe does not move" symptom.
        runCatching {
            btn.systemGestureExclusionRects = listOf(
                android.graphics.Rect(0, 0, size, size))
        }
        try {
            manager.addView(btn, params)
        } catch (e: Exception) {
            Log.w(TAG, "Bubble type $bubbleType rejected, falling back to 2038", e)
            val fallbackCtx = appContext.createDisplayContext(display)
            val fallbackMgr = fallbackCtx.getSystemService(WindowManager::class.java)
                ?: throw IllegalStateException("No WindowManager for display ${display.displayId}")
            params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            fallbackMgr.addView(btn, params)
            manager = fallbackMgr
        }
        // vc112 SHOW ANIMATION: fade + scale in
        // (200ms) instead of popping in, matching
        // the panel enter animation. scheduleDim
        // below still dims to 0.4 after idle, so
        // the end state is unchanged.
        btn.alpha = 0f
        btn.scaleX = 0.8f
        btn.scaleY = 0.8f
        btn.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200L)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        Log.i(TAG, "Bubble type=${params.type} (a11yAlive=${a11ySvc != null})")
        wm = manager; button = btn; bubbleParams = params; displayId = display.displayId
        // Apply the saved cover brightness immediately, not only after
        // the panel is opened once.
        runCatching {
            val st = com.cuong.cflip.data.SettingsStore(appContext).load()
            params.screenBrightness = if (st.panelBrightnessManual) st.panelBrightness.coerceIn(0.05f, 1.0f) else -1f
            manager.updateViewLayout(btn, params)
        }
        scheduleDim()
        Log.i(TAG, "AssistiveTouch shown on cover display ${display.displayId} at ($startX,$startY)")
        Unit
    }.onFailure { Log.w(TAG, "AssistiveTouch addView failed on display ${display.displayId}", it) }

    fun hide() {
        keepAwakeRunnable?.let { handler.removeCallbacks(it) }
        keepAwakeRunnable = null
        button?.keepScreenOn = false
        dimRunnable?.let { handler.removeCallbacks(it) }
        dimRunnable = null
        val v = button
        if (v != null) {
            runCatching { wm?.removeView(v) }
            button = null; wm = null; bubbleParams = null; displayId = -1
            Log.i(TAG, "AssistiveTouch hidden")
        }
    }

    private fun isKeyguardLocked(context: Context): Boolean {
        val km = context.getSystemService(android.app.KeyguardManager::class.java) ?: return false
        return km.isKeyguardLocked
    }

    private fun launchUnlockActivity(context: Context, displayId: Int) {
        runCatching {
            val intent = Intent(context, CoverUnlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            context.startActivity(intent, opts.toBundle())
        }.onFailure { Log.w(TAG, "Lock bubble unlock launch failed", it) }
    }

    private fun doHome(context: Context, displayId: Int) {
        // HOME intent on the cover display first, global action as fallback.
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching {
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            context.startActivity(home, opts.toBundle())
        }.isSuccess
        if (!ok) {
            val a11y = MyAccessibilityService.instance
            if (a11y?.goHome() != true) {
                Log.w(TAG, "Home failed: no AccessibilityService")
                android.widget.Toast.makeText(
                    context, "Home không chạy: cần bật Accessibility cho CFlip",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun doRecents(context: Context) {
        val a11y = MyAccessibilityService.instance
        if (a11y?.openRecents() != true) {
            Log.w(TAG, "Recents failed: no AccessibilityService")
            android.widget.Toast.makeText(
                context, "Gần đây không chạy: cần bật Accessibility cho CFlip",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // vc115 CUSTOM ACTIONS (user, 27/10):
    // tap / double-tap / long-press are no longer
    // hard-coded. Each gesture reads its action
    // from SettingsStore at fire time, so a change
    // in MainScreen applies to the next touch
    // without restarting the service. Defaults
    // (SettingsStore) preserve the old mapping:
    // tap = Home, double-tap = Full panel,
    // long-press = Recents. Lock on keyguard is
    // still handled at the call sites (unlock
    // activity), exactly as before - this function
    // only dispatches the chosen action.
    private fun performBubbleAction(action: Int, context: Context, displayId: Int) {
        val a = com.cuong.cflip.data.BubbleActions
        when (action) {
            a.HOME -> doHome(context, displayId)
            a.RECENTS -> doRecents(context)
            a.FULL_PANEL -> doMenu(context, displayId, quickPanel = false)
            a.QUICK_PANEL -> doMenu(context, displayId, quickPanel = true)
            a.LOCK -> doLock(context)
            a.NONE -> { /* deliberately nothing */ }
            else -> doHome(context, displayId)
        }
    }

    private fun doLock(context: Context) {
        val a11y = MyAccessibilityService.instance
        if (a11y?.lockScreen() != true) {
            Log.w(TAG, "Lock failed: no AccessibilityService")
            android.widget.Toast.makeText(
                context, "Khóa máy không chạy: cần bật Accessibility cho CFlip",
                android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun doMenu(context: Context, displayId: Int, quickPanel: Boolean = false) {
        // Menu = CFlip's own Control Center panel, as an overlay on the
        // cover display (2.6.0). It used to launch MainActivity at the
        // Quick tab on the cover display, but that activity route is the
        // one Samsung blocks most often — the panel uses the same
        // WindowManager overlay route as CoverOverlay, so it survives
        // where the activity does not. Only if the panel itself fails do
        // we fall back to the activity route.
        val display = runCatching {
            (context.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                .getDisplay(displayId)
        }.getOrNull()
        val panelOk = if (display != null) {
            CoverControlCenterManager.show(context, display, quickPanel = quickPanel).isSuccess
        } else false
        if (!panelOk) {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("cflip_show_cover", true)
                .putExtra("cflip_cover_tab", "QUICK")
            val opts = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
            runCatching { context.startActivity(intent, opts.toBundle()) }
                .onFailure { Log.w(TAG, "Menu (Quick tab) launch failed", it) }
        }
    }
}
