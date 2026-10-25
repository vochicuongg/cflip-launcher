package com.cuong.cflip.cover

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * vc37 Samsung-style vertical slider, as in the screenshot Cường sent:
 * a tall translucent pill track, a white oval thumb that slides up/down
 * with a black icon inside it (sun for brightness, speaker for volume),
 * and a small dark circle button below it (moon / mute in the panel).
 *
 * 9Flip's own sliders (p6/e, ue) are horizontal, but the system look
 * Cường asked for is vertical, so this keeps 9Flip's VALUE logic
 * (brightness clamp, volume mute restore) and changes only the shape
 * to the Samsung vertical pill. Value 0 = thumb at bottom, 1 = at top.
 */
class VerticalSlider(context: Context) : FrameLayout(context) {

    var minValue: Float = 0f
    var onValueChanged: ((Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val track = View(context)
    private val thumb = FrameLayout(context)
    private val thumbIcon = ImageView(context)
    private var value: Float = 0f
    private var dragging = false

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    init {
        track.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(28).toFloat()
            setColor(Color.argb(64, 255, 255, 255))
        }
        addView(track, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        thumb.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.WHITE)
        }
        thumbIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        thumbIcon.setColorFilter(Color.BLACK)
        thumb.addView(thumbIcon, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(thumb, LayoutParams(LayoutParams.MATCH_PARENT, dp(56)))
        setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateFromY(e.y)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragging) updateFromY(e.y)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    onDragEnd?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    fun setIcon(res: Int) {
        thumbIcon.setImageResource(res)
        thumbIcon.setPadding(dp(12), dp(12), dp(12), dp(12))
    }

    fun setValue(v: Float, animate: Boolean = false) {
        value = v.coerceIn(minValue, 1f)
        post { positionThumb() }
    }

    fun setManual(manual: Boolean) {
        // 9Flip p6: alpha 1.0 manual, 0.42 system-following.
        alpha = if (manual) 1f else 0.42f
    }

    private fun positionThumb() {
        val h = height
        val th = thumb.height
        if (h <= th) return
        val frac = if (1f - minValue > 0f) (value - minValue) / (1f - minValue) else 0f
        val y = ((1f - frac) * (h - th)).toInt()
        thumb.y = y.toFloat()
    }

    private fun updateFromY(y: Float) {
        val h = height
        val th = thumb.height
        if (h <= th) return
        val frac = (1f - (y - th / 2f) / (h - th).toFloat()).coerceIn(0f, 1f)
        val v = (minValue + frac * (1f - minValue)).coerceIn(minValue, 1f)
        value = v
        positionThumb()
        onValueChanged?.invoke(v)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        positionThumb()
    }
}
