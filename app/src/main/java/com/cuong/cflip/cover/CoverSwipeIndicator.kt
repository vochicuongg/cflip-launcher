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
        // vc135: the chevron points INWARD (toward the screen centre):
        // right on the left edge, left on the right edge. Drawn by
        // rotating the same ^ path, so it stays font-independent.
        val chevron = object : View(displayContext) {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = (dp(3) * sizeScale).toFloat()
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
                setShadowLayer(dp(2).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat(); val h = height.toFloat()
                canvas.save()
                canvas.rotate(if (startOnRight) -90f else 90f, w / 2f, h / 2f)
                val path = android.graphics.Path().apply {
                    moveTo(w * 0.15f, h * 0.8f)
                    lineTo(w * 0.5f, h * 0.2f)
                    lineTo(w * 0.85f, h * 0.8f)
                }
                canvas.drawPath(path, paint)
                canvas.restore()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams((dp(20) * sizeScale).toInt(), (dp(20) * sizeScale).toInt()).apply { gravity = Gravity.CENTER }
        }
        val pill = View(displayContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (dp(3) * sizeScale).toFloat()
                setColor(0xB3FFFFFF.toInt())
            }
            layoutParams = android.widget.FrameLayout.LayoutParams((dp(5) * sizeScale).toInt(), (dp(56) * sizeScale).toInt()).apply {
                gravity = Gravity.CENTER
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
            gravity = Gravity.CENTER
            setPadding((dp(3) * sizeScale).toInt(), (dp(6) * sizeScale).toInt(), (dp(3) * sizeScale).toInt(), (dp(6) * sizeScale).toInt())
            // Chevron on the inner side: left of the pill on the right
            // edge, right of the pill on the left edge.
            if (startOnRight) { addView(chevron); addView(halo) } else { addView(halo); addView(chevron) }
        }
        var downRawX = 0f
        var downRawY = 0f
        var dragStartY = 0
        var dragH = false
        var dragV = false
        var opened = false
        var switched = false
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
        container.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    dragStartY = barParams?.y ?: 0
                    dragH = false; dragV = false; opened = false; switched = false
                    // Work in left-edge coordinates during the drag so
                    // the bar can travel the full width: if it sits on
                    // the right edge, re-anchor its params to the left
                    // (same on-screen position, different gravity; the
                    // TOP-anchored y needs no conversion).
                    val params = barParams
                    if (params != null && (params.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.RIGHT) {
                        params.gravity = Gravity.TOP or Gravity.LEFT
                        params.x = dispW - edgeMargin - barW
                        runCatching { manager.updateViewLayout(container, params) }
                    }
                    cancelDim()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!opened) {
                        val dxRaw = e.rawX - downRawX
                        val dyRaw = e.rawY - downRawY
                        if (!dragH && kotlin.math.abs(dxRaw) > touchSlop) dragH = true
                        if (!dragV && kotlin.math.abs(dyRaw) > touchSlop) dragV = true
                        val params = barParams
                        if (params != null && (dragH || dragV)) {
                            if (dragH) params.x = (e.rawX - barW / 2f).toInt().coerceIn(edgeMargin, dispW - barW - edgeMargin)
                            if (dragV) params.y = (dragStartY + (e.rawY - downRawY)).toInt().coerceIn(edgeMargin, maxOf(edgeMargin, dispH - barH - edgeMargin))
                            runCatching { manager.updateViewLayout(container, params) }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val params = barParams
                    if (params != null && !opened) {
                        val endOnRight = params.x + barW / 2 > dispW / 2
                        if (dragH && endOnRight != startOnRight) {
                            // Crossed the middle: switch sides and rebuild
                            // (the chevron flips to point inward again).
                            barPrefs.edit().putInt("side", if (endOnRight) 1 else 0)
                                .putInt("y", params.y).putInt("h", dispH).apply()
                            switched = true
                            show(appContext, display)
                        } else if (dragV && !dragH) {
                            // Pure vertical move: stay on the same side,
                            // snap back to the edge, remember the height.
                            params.x = edgeMargin
                            runCatching { manager.updateViewLayout(container, params) }
                            barPrefs.edit().putInt("y", params.y).putInt("h", dispH).apply()
                        } else {
                            // Same half after a horizontal drag = an inward
                            // swipe, and no drag at all = a tap: both open
                            // the panel.
                            opened = true
                            openPanel(appContext, display)
                        }
                    }
                    if (!opened && !switched) armDim()
                    true
                }
                MotionEvent.ACTION_CANCEL -> { armDim(); true }
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
            x = edgeMargin
            y = startY
        }
        manager.addView(container, params)
        wm = manager; bar = container; displayId = display.displayId; barParams = params
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

    private fun openPanel(appContext: Context, display: Display) {
        runCatching { CoverControlCenterManager.show(appContext, display) }
            .onFailure { Log.w(TAG, "Swipe indicator panel open failed", it) }
    }

    fun hide() {
        dimRunnable?.let { dimHandler.removeCallbacks(it) }
        dimRunnable = null; barParams = null
        runCatching {
            val v = bar; val w = wm
            if (v != null && w != null) w.removeView(v)
        }
        bar = null; wm = null; displayId = -1
    }
}
