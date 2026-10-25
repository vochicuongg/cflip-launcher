package com.cuong.cflip.cover

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView

/**
 * vc35: 9Flip-style slider, clean-room re-implementation of the
 * architecture in 9Flip p6/e (NOT a copy of its code or assets).
 *
 * 9Flip does NOT use a SeekBar. It is a custom bar:
 * - outer pill: translucent grey background (185,185,185 at ~20%),
 *   1px translucent stroke, corner radius 44px;
 * - inner fill: a rounded bar whose width is
 *   thumbSize + (width - thumbSize) * value, drawn with a
 *   light-grey -> white gradient, animated 130ms with a
 *   DecelerateInterpolator when set programmatically;
 * - left icon inside the bar, tinted dark grey (63,63,63);
 * - right icon at the far end, clipped by the fill in 9Flip;
 * - alpha 1.0 when manual, 0.42 when following the system;
 * - horizontal drag only: |dx| > 0.7 * touchSlop and |dx| > |dy| * 1.1,
 *   otherwise the parent ScrollView keeps the gesture;
 * - value clamped 0.05..1.0 for brightness (9Flip t6), 0..1 for volume.
 *
 * CFlip's version keeps the same geometry and behaviour, in dark
 * panel colours: left icon dark on the white fill, right icon white
 * on the translucent track, fill gradient C6C4C3 -> white.
 */
class NineFlipSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    var minValue: Float = 0.05f
    var value: Float = 0.5f
        private set
    var onValueChanged: ((Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    var onRightClick: (() -> Unit)? = null

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val thumbSize = dp(32)
    private val fillView = View(context)
    private val leftIcon = ImageView(context)
    private val rightIcon = ImageView(context)
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var animator: ValueAnimator? = null

    init {
        // Outer pill, like 9Flip p6.b().
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(Color.argb(51, 185, 185, 185))
            setStroke(dp(1), Color.argb(72, 255, 255, 255))
        }
        setPadding(dp(4), dp(4), dp(4), dp(4))
        // Fill bar, gradient like 9Flip e.onDraw().
        fillView.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            orientation = GradientDrawable.Orientation.LEFT_RIGHT
            colors = intArrayOf(Color.rgb(198, 196, 195), Color.WHITE)
        }
        addView(fillView, LayoutParams(thumbSize, LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })
        leftIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        leftIcon.setColorFilter(Color.rgb(63, 63, 63))
        leftIcon.setPadding(dp(8), dp(8), dp(8), dp(8))
        addView(leftIcon, LayoutParams(dp(40), LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        })
        rightIcon.scaleType = ImageView.ScaleType.CENTER_INSIDE
        rightIcon.setColorFilter(Color.WHITE)
        rightIcon.setPadding(dp(8), dp(8), dp(8), dp(8))
        rightIcon.setOnClickListener { onRightClick?.invoke() }
        addView(rightIcon, LayoutParams(dp(40), LayoutParams.MATCH_PARENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        })
        isClickable = true
        isFocusable = true
        minimumHeight = dp(48)
    }

    fun setIcon(iconRes: Int) {
        leftIcon.setImageResource(iconRes)
    }

    fun setRightIcon(iconRes: Int) {
        rightIcon.setImageResource(iconRes)
        rightIcon.visibility = View.VISIBLE
    }

    fun setManual(manual: Boolean) {
        // 9Flip: alpha 1.0 manual, 0.42 auto.
        alpha = if (manual) 1.0f else 0.42f
    }

    fun setValue(v: Float, animate: Boolean = false) {
        val target = v.coerceIn(minValue, 1.0f)
        animator?.cancel()
        if (!animate || !isAttachedToWindow || kotlin.math.abs(target - value) < 0.005f) {
            value = target
            updateFill()
            return
        }
        val start = value
        val anim = ValueAnimator.ofFloat(start, target).apply {
            duration = 130L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                value = it.animatedValue as Float
                updateFill()
            }
        }
        animator = anim
        anim.start()
    }

    private fun updateFill() {
        val w = width
        if (w <= 0) {
            post { updateFill() }
            return
        }
        val fillWidth = (thumbSize + (w - thumbSize) * value).toInt()
        val params = fillView.layoutParams as LayoutParams
        if (params.width != fillWidth) {
            params.width = fillWidth
            fillView.layoutParams = params
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateFill()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging) {
                    // 9Flip p6.e(): horizontal only, 0.7 * slop, horizontal > vertical * 1.1
                    if (kotlin.math.abs(dx) > touchSlop * 0.7f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.1f) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (kotlin.math.abs(dy) > touchSlop) {
                        return false
                    } else {
                        return true
                    }
                }
                if (dragging) {
                    val w = width
                    if (w > thumbSize) {
                        val v = ((event.x - thumbSize / 2f) / (w - thumbSize)).coerceIn(minValue, 1.0f)
                        if (v != value) {
                            value = v
                            updateFill()
                            onValueChanged?.invoke(v)
                        }
                    }
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (wasDragging) onDragEnd?.invoke()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
