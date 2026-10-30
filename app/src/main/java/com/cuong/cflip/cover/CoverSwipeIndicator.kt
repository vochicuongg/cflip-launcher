package com.cuong.cflip.cover

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import com.cuong.cflip.service.MyAccessibilityService

/**
 * vc120 SWIPE INDICATOR ("Thanh Vuốt") - clean-room
 * equivalent of 9Flip's swipe_indicator open mode
 * (NOT a copy of its code or assets).
 *
 * 9Flip decompile, 28/10: the cover control has
 * exactly two open modes, pref "open_mode"
 * (enum gb): swipe_indicator (the default) vs
 * assistive_touch. The indicator itself (d4/pj)
 * is a small custom view on the cover display
 * (WindowManager type 2032, title
 * "CoverFloatingMenuIndicator"), drawn as a
 * rounded pill with a chevron, with a
 * GestureDetector (long-press) and system
 * gesture exclusion rects so its touches are
 * not stolen. Swiping it opens the floating
 * menu (reason "indicator_swipe").
 *
 * CFlip's version, own drawing and gestures:
 * a vertical side handle on the cover - a
 * 40x128dp touch area with a 5x56dp white pill
 * (scaled by the shared size setting, like the bubble)
 * centred in it, so a finger finds it easily.
 * Swipe INWARD on it opens the Full Panel; a plain
 * tap does the same as a fallback (small cover,
 * swipe can be hard to land first try). The bar
 * never shows together with the bubble: the
 * service shows one opener per the open mode,
 * the panel hides the shown opener while open
 * and restores it on close (same rules as the
 * bubble: never on lock, never during mirror,
 * never when the opener is turned off).
 *
 * vc133: drawn ^ chevron + dark halo pill (9Flip mechanism, own drawing)
 * so the bar reads on light wallpapers, and FLAG_NOT_TOUCH_MODAL so
 * touches outside the bar still reach the cover UI.
 *
 * vc134: drag sideways to hug either edge (snaps + remembers position),
 * and auto-dim to 35% after 2.5s without a touch.
 *
 * vc135: the bar moves to the sides - a vertical handle on the left or
 * right edge, vertically centred, never at the top or bottom. Drag it
 * across the screen and it snaps to the nearest side on release; a
 * drag that stays on the same half (or a tap) opens the panel. Dim
 * deepened to 25% after 2.5s.
 *
 * vc136: smaller (40x128dp base), draggable up/down along the edge
 * (height remembered, scaled to the display), and size + auto-dim
 * read from the shared settings so bar and bubble stay in sync.
 *
 * vc138: chevron removed (pill only); gestures are the bubble's
 * (tap / double-tap / long-press via the shared dispatcher, read
 * at fire time); dragging to move never opens the panel or fires
 * an action.
 */
object CoverSwipeIndicatorManager {
    private const val TAG = "CFlipSwipeIndicator"
    private const val PREFS = "swipe_indicator"

    private var wm: WindowManager? = null
    private var bar: View? = null
    private var displayId: Int = -1
    private var barParams: WindowManager.LayoutParams? = null
    private val dimHandler = Handler(Looper.getMainLooper())
    private var dimRunnable: Runnable? = null

    fun isShown(): Boolean = bar != null

    fun show(appContext: Context, display: Display): Result<Unit> = runCatching {
        if (display.displayId == Display.DEFAULT_DISPLAY) {
            throw IllegalArgumentException("Swipe indicator is cover-only, refusing default display")
        }
        hide()
        // Same window type rule as the bubble
        // (vc99): 2032 via accessibility when
        // alive, else 2038 overlay fallback.
        val a11ySvc = MyAccessibilityService.instance
        val displayContext = if (a11ySvc != null) a11ySvc.createDisplayContext(display) else appContext.createDisplayContext(display)
        val barType = if (a11ySvc != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val manager = displayContext.getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("No WindowManager for display ${display.displayId}")
        val density = displayContext.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val touchSlop = ViewConfiguration.get(displayContext).scaledTouchSlop
        // vc135 SIDE BAR (user, 29/10): the bar no longer lives at the
        // bottom - it is a vertical handle on the left or right edge.
        // Drag it across the screen and it snaps to the nearest side
        // on release; drag it up/down to move it along the edge.
        // vc136: smaller base size, and size + auto-dim read from the
        // shared settings so bar and bubble stay in sync both ways.
        val coverSettings = runCatching { com.cuong.cflip.data.SettingsStore(appContext).load() }.getOrNull()
        val sizeScale = when (coverSettings?.assistiveTouchSize) { 0 -> 0.8f; 2 -> 1.2f; else -> 1.0f }
        val autoDimEnabled = coverSettings?.assistiveTouchAutoDim ?: true
        val barW = (dp(40) * sizeScale).toInt()
        val barH = (dp(128) * sizeScale).toInt()
        val dispW = displayContext.resources.displayMetrics.widthPixels
        val dispH = displayContext.resources.displayMetrics.heightPixels
        val edgeMargin = dp(8)
        val barPrefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Migrate vc134's bottom offset ("x") to a side: a negative
        // offset meant the left half, anything else the right half.
        val savedSide = barPrefs.getInt("side", -1).let { s ->
            if (s >= 0) s else if (barPrefs.contains("x") && barPrefs.getInt("x", 0) < 0) 0 else 1
        }
        val startOnRight = savedSide != 0
        // Height: restored (scaled to the current display height),
        // centred by default, clamped so the bar stays on screen.
        val savedY = barPrefs.getInt("y", -1)
        val savedH = barPrefs.getInt("h", 0)
        val startY = (if (savedY >= 0 && savedH > 0) savedY * dispH / savedH else (dispH - barH) / 2)
            .coerceIn(edgeMargin, maxOf(edgeMargin, dispH - barH - edgeMargin))
        // vc133 VISIBILITY (user, 29/10: bar never seen; 9Flip mechanism):
        // 9Flip's indicator draws a chevron affordance (^) over a pill
        // with contrast layers, so it reads on light wallpapers too.
        // CFlip's old bar was a lone white pill with no chevron and no
        // edge - white-on-white on a light cover wallpaper. Own drawing:
        // a drawn ^ chevron (no font dependency) with a dark shadow,
        // over a white pill set in a larger dark halo pill.
        val pill = View(displayContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (dp(3) * sizeScale).toFloat()
                setColor(0xB3FFFFFF.toInt())
            }
            layoutParams = android.widget.FrameLayout.LayoutParams((dp(5) * sizeScale).toInt(), (dp(56) * sizeScale).toInt()).apply {
                // vc193 EDGE-FLUSH: pill hugs the window edge on the
                // bar's side (was CENTER, leaving ~4.5dp inside halo).
                gravity = Gravity.CENTER_VERTICAL or if (startOnRight) Gravity.RIGHT else Gravity.LEFT
            }
        }
        val halo = android.widget.FrameLayout(displayContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (dp(8) * sizeScale).toFloat()
                setColor(0x66000000.toInt())
            }
            layoutParams = LinearLayout.LayoutParams((dp(14) * sizeScale).toInt(), (dp(72) * sizeScale).toInt()).apply { gravity = Gravity.CENTER }
            addView(pill)
        }
        val container = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            // vc193 EDGE-FLUSH: row hugs the window edge on the
            // bar's side (was CENTER, leaving ~13dp inside window).
            gravity = Gravity.CENTER_VERTICAL or if (startOnRight) Gravity.RIGHT else Gravity.LEFT
            setPadding(0, (dp(6) * sizeScale).toInt(), 0, (dp(6) * sizeScale).toInt())
            // vc138: no chevron anymore - the pill alone is the bar.
            addView(halo)
        }
        var downRawX = 0f
        var downRawY = 0f
        var dragStartY = 0
        var dragH = false
        var dragV = false
        var switched = false
        var longPressed = false
        var pendingSingleTap = false
        var doubleTapOpened = false
        var longPressRunnable: Runnable? = null
        var singleTapRunnable: Runnable? = null
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        val doubleTapTimeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        // vc135 DIM (user, 29/10: a little fainter): the bar fades to
        // 25% after 2.5s without a touch, and comes back on touch.
        // vc136: the dim follows the shared auto-dim switch - off
        // means the bar never dims, like the bubble.
        fun armDim() {
            if (!autoDimEnabled) return
            dimRunnable?.let { dimHandler.removeCallbacks(it) }
            val r = Runnable { container.alpha = 0.25f }
            dimRunnable = r
            dimHandler.postDelayed(r, 2500L)
        }
        fun cancelDim() {
            dimRunnable?.let { dimHandler.removeCallbacks(it) }
            dimRunnable = null
            container.alpha = 1f
        }
        fun cancelTapRunnables() {
            longPressRunnable?.let { dimHandler.removeCallbacks(it) }
            singleTapRunnable?.let { dimHandler.removeCallbacks(it) }
            longPressRunnable = null; singleTapRunnable = null
        }
        container.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelDim()
                    // vc138 SYNC (user, 29/10: the bar is only a style,
                    // not an independent function): gestures are the
                    // bubble's - fast double-tap fires on the second
                    // DOWN, exactly like the bubble (vc41).
                    if (pendingSingleTap) {
                        pendingSingleTap = false
                        singleTapRunnable?.let { dimHandler.removeCallbacks(it) }
                        singleTapRunnable = null
                        doubleTapOpened = true
                        val dtAction = com.cuong.cflip.data.SettingsStore(appContext).load().bubbleDoubleTapAction
                        CoverAssistiveTouchManager.performBubbleAction(dtAction, appContext, display.displayId)
                        armDim()
                        true
                    } else {
                        downRawX = e.rawX; downRawY = e.rawY
                        dragStartY = barParams?.y ?: 0
                        dragH = false; dragV = false; longPressed = false; doubleTapOpened = false
                        // Work in left-edge coordinates during the drag so
                        // the bar can travel the full width: if it sits on
                        // the right edge, re-anchor its params to the left
                        // (same on-screen position, different gravity; the
                        // TOP-anchored y needs no conversion).
                        val params = barParams
                        if (params != null && (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT) {
                            params.gravity = Gravity.TOP or Gravity.LEFT
                            params.x = dispW - barW
                            runCatching { manager.updateViewLayout(container, params) }
                        }
                        val r = Runnable {
                            longPressed = true
                            val action = com.cuong.cflip.data.SettingsStore(appContext).load().bubbleLongPressAction
                            CoverAssistiveTouchManager.performBubbleAction(action, appContext, display.displayId)
                        }
                        longPressRunnable = r
                        dimHandler.postDelayed(r, longPressTimeout)
                        true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!doubleTapOpened) {
                        // Bubble vc117: cancel long-press only past HALF
                        // the slop, so a trembling finger still holds.
                        if (longPressRunnable != null) {
                            val mdx = e.rawX - downRawX; val mdy = e.rawY - downRawY
                            if (kotlin.math.hypot(mdx, mdy) > touchSlop / 2f) {
                                dimHandler.removeCallbacks(longPressRunnable!!)
                                longPressRunnable = null
                            }
                        }
                        val dxRaw = e.rawX - downRawX
                        val dyRaw = e.rawY - downRawY
                        val wasDragging = dragH || dragV
                        if (!dragH && kotlin.math.abs(dxRaw) > touchSlop) dragH = true
                        if (!dragV && kotlin.math.abs(dyRaw) > touchSlop) dragV = true
                        if ((dragH || dragV) && !wasDragging) cancelTapRunnables()
                        val params = barParams
                        if (params != null && (dragH || dragV)) {
                            if (dragH) params.x = (e.rawX - barW / 2f).toInt().coerceIn(0, dispW - barW)
                            if (dragV) params.y = (dragStartY + (e.rawY - downRawY)).toInt().coerceIn(edgeMargin, maxOf(edgeMargin, dispH - barH - edgeMargin))
                            runCatching { manager.updateViewLayout(container, params) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (doubleTapOpened) {
                        // UP belonging to the DOWN that already fired the
                        // double-tap action must not start a pending single
                        // (bubble vc41: it would later fire Home over the panel).
                        doubleTapOpened = false
                        armDim()
                        true
                    } else {
                        cancelTapRunnables()
                        val params = barParams
                        if (params != null && (dragH || dragV)) {
                            // vc138 MOVE ONLY (user, 29/10): dragging to
                            // move the bar never opens the panel and never
                            // fires an action - the vc136 same-half drag
                            // opening the panel is gone.
                            val endOnRight = params.x + barW / 2 > dispW / 2
                            if (dragH && endOnRight != startOnRight) {
                                barPrefs.edit().putInt("side", if (endOnRight) 1 else 0)
                                    .putInt("y", params.y).putInt("h", dispH).apply()
                                switched = true
                                show(appContext, display)
                            } else {
                                // Same side: snap x back to the ORIGINAL edge.
                                // vc136 bug fix: a right-edge bar's params are
                                // in left coords after the DOWN re-anchor, so
                                // snapping to edgeMargin would wrongly jump it
                                // to the left edge - restore its own edge.
                                params.x = if (startOnRight) dispW - barW else 0
                                runCatching { manager.updateViewLayout(container, params) }
                                barPrefs.edit().putInt("y", params.y).putInt("h", dispH).apply()
                            }
                        } else if (longPressed) {
                            // Long-press already fired on the timeout; the
                            // UP itself does nothing more.
                        } else {
                            // Tap: schedule the single-tap action after the
                            // double-tap window, so a second DOWN can still
                            // turn it into a double-tap (bubble behaviour).
                            pendingSingleTap = true
                            val r = Runnable {
                                pendingSingleTap = false
                                val tapAction = com.cuong.cflip.data.SettingsStore(appContext).load().bubbleTapAction
                                CoverAssistiveTouchManager.performBubbleAction(tapAction, appContext, display.displayId)
                            }
                            singleTapRunnable = r
                            dimHandler.postDelayed(r, doubleTapTimeout)
                        }
                        if (!switched) armDim()
                        true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    doubleTapOpened = false
                    cancelTapRunnables(); pendingSingleTap = false
                    armDim(); true
                }
                else -> true
            }
        }

        // Window size IS the touch area (40x128dp at default size):
        // WRAP_CONTENT would shrink it to the
        // pill + padding (20x84dp) and make the
        // swipe hard to land on a small cover.
        val params = WindowManager.LayoutParams(
            barW,
            barH,
            barType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or if (startOnRight) Gravity.RIGHT else Gravity.LEFT
            // vc193 EDGE-FLUSH: window touches the screen edge
            // (was edgeMargin 8dp). Y clamps still use edgeMargin.
            x = 0
            y = startY
            // vc140: the bar window follows the same CFlip brightness as
            // the bubble (manual -> stored value, auto -> system), so
            // switching openers no longer changes the cover brightness.
            screenBrightness = coverSettings?.let {
                if (it.panelBrightnessManual) it.panelBrightness.coerceIn(0.05f, 1.0f) else -1f
            } ?: -1f
        }
        manager.addView(container, params)
        wm = manager; bar = container; displayId = display.displayId; barParams = params
        // vc141: hold the manual brightness on the cover after the panel
        // closes (the tiny bar window alone does not hold it). Best
        // effort: a holder failure must never break the opener itself.
        runCatching { CoverBrightnessHolder.show(appContext, display) }
            .onFailure { Log.w(TAG, "Brightness holder failed", it) }
        armDim()
        // 9Flip pj.a(): keep system gestures from
        // stealing the bar's touches. Posted so
        // the view is laid out and has a size.
        container.post {
            runCatching {
                container.setSystemGestureExclusionRects(
                    listOf(Rect(0, 0, container.width, container.height)))
            }
        }
        Log.i(TAG, "Swipe indicator shown on display ${display.displayId}")
    }


    // vc140: mirror of the bubble's applyCoverBrightness - the bar window
    // follows the same CFlip brightness, so switching openers no longer
    // changes the cover brightness (before vc140 only the bubble window
    // was updated, the bar always sat at system brightness).
    fun applyBarBrightness(appContext: Context) {
        val params = barParams; val v = bar; val manager = wm
        if (params == null || v == null || manager == null) return
        val st = com.cuong.cflip.data.SettingsStore(appContext).load()
        params.screenBrightness = if (st.panelBrightnessManual) st.panelBrightness.coerceIn(0.05f, 1.0f) else -1f
        runCatching { manager.updateViewLayout(v, params) }
    }

    fun hide() {
        dimRunnable?.let { dimHandler.removeCallbacks(it) }
        dimRunnable = null; barParams = null
        runCatching {
            val v = bar; val w = wm
            if (v != null && w != null) w.removeView(v)
        }
        bar = null; wm = null; displayId = -1
        CoverBrightnessHolder.hide()
    }
}
