package com.cuong.cflip.cover

import android.content.Context
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
 * a small bottom-centre bar on the cover - a
 * 160x48dp touch area with a 72x6dp white pill
 * centred in it, so a finger finds it easily.
 * Swipe UP on it opens the Full Panel; a plain
 * tap does the same as a fallback (small cover,
 * swipe can be hard to land first try). The bar
 * never shows together with the bubble: the
 * service shows one opener per the open mode,
 * the panel hides the shown opener while open
 * and restores it on close (same rules as the
 * bubble: never on lock, never during mirror,
 * never when the opener is turned off).
 */
object CoverSwipeIndicatorManager {
    private const val TAG = "CFlipSwipeIndicator"

    private var wm: WindowManager? = null
    private var bar: View? = null
    private var displayId: Int = -1

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
        val pill = View(displayContext).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(3).toFloat()
                setColor(0xB3FFFFFF.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(6)).apply {
                gravity = Gravity.CENTER
            }
        }
        val container = LinearLayout(displayContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(pill)
        }
        var downX = 0f
        var downY = 0f
        var swiped = false
        container.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; swiped = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!swiped) {
                        val dx = e.x - downX; val dy = e.y - downY
                        if (dy < -touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.1f) {
                            swiped = true
                            openPanel(appContext, display)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Tap fallback: a tap that never
                    // became a swipe still opens the
                    // panel, so a missed swipe is not
                    // a dead tap on a small cover.
                    if (!swiped) {
                        val dx = e.x - downX; val dy = e.y - downY
                        if (kotlin.math.hypot(dx, dy) <= touchSlop) {
                            openPanel(appContext, display)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        // Window size IS the touch area (160x48dp):
        // WRAP_CONTENT would shrink it to the
        // pill + padding (88x22dp) and make the
        // swipe hard to land on a small cover.
        val params = WindowManager.LayoutParams(
            dp(160),
            dp(48),
            barType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dp(16)
        }
        manager.addView(container, params)
        wm = manager; bar = container; displayId = display.displayId
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
        runCatching {
            val v = bar; val w = wm
            if (v != null && w != null) w.removeView(v)
        }
        bar = null; wm = null; displayId = -1
    }
}
