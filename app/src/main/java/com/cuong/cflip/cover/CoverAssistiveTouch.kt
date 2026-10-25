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

    fun show(appContext: Context, display: Display): Result<Unit> = runCatching {
        if (display.displayId == Display.DEFAULT_DISPLAY) {
            throw IllegalArgumentException("AssistiveTouch is cover-only, refusing default display")
        }
        hide()
        val displayContext = appContext.createDisplayContext(display)
        val manager = displayContext.getSystemService(WindowManager::class.java)
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

        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
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
            // 3.3.0: every touch on the cover button resets the cover
            // idle timer via keepScreenOn (see pokeKeepAwake above).
            // vc32: poke only on DOWN, not on every MOVE (handler churn per move event was part of the stutter).
            if (e.action == MotionEvent.ACTION_DOWN) pokeKeepAwake()
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    btn.alpha = 1f
                    dimRunnable?.let { handler.removeCallbacks(it) }
                    downX = e.rawX; downY = e.rawY
                    paramsX = params.x; paramsY = params.y
                    moved = false; longPressed = false
                    val r = Runnable {
                        longPressed = true
                        doRecents(appContext)
                    }
                    longPressRunnable = r
                    handler.postDelayed(r, longPressTimeout)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // vc32: Euclidean distance (like 9Flip c1 hypot),
                    // not Manhattan; keep float until the final round
                    // (toInt() truncated toward zero and stepped unevenly).
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (kotlin.math.hypot(dx, dy) > touchSlop) {
                        if (!moved) { cancelTapRunnables(); moved = true }
                        val newX = kotlin.math.roundToInt(paramsX + dx).coerceIn(0, maxOf(0, dispW - size))
                        val newY = kotlin.math.roundToInt(paramsY + dy).coerceIn(0, maxOf(0, dispH - size))
                        // Skip redundant relayouts (9Flip b6 does the same).
                        if (newX != params.x || newY != params.y) {
                            params.x = newX; params.y = newY
                            runCatching { manager.updateViewLayout(v, params) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelTapRunnables()
                    if (moved) {
                        prefs.edit()
                            .putInt("x", params.x).putInt("y", params.y)
                            .putInt("display_w", dispW).putInt("display_h", dispH)
                            .apply()
                        scheduleDim()
                        return@setOnTouchListener true
                    }
                    if (longPressed) { scheduleDim(); return@setOnTouchListener true }
                    if (pendingSingleTap) {
                        // Second tap inside the double-tap window -> Menu.
                        pendingSingleTap = false
                        doMenu(appContext, display.displayId)
                    } else {
                        pendingSingleTap = true
                        val r = Runnable {
                            pendingSingleTap = false
                            doHome(appContext, display.displayId)
                        }
                        singleTapRunnable = r
                        handler.postDelayed(r, doubleTapTimeout)
                    }
                    scheduleDim()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    cancelTapRunnables(); pendingSingleTap = false; scheduleDim(); true
                }
                else -> false
            }
        }

        manager.addView(btn, params)
        wm = manager; button = btn; displayId = display.displayId
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
            button = null; wm = null; displayId = -1
            Log.i(TAG, "AssistiveTouch hidden")
        }
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

    private fun doMenu(context: Context, displayId: Int) {
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
            CoverControlCenterManager.show(context, display).isSuccess
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
