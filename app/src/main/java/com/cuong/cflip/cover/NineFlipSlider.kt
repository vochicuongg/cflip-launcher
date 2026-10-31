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
    // vc41: left-icon click is opt-in via setLeftClickListener(),
    // NOT a blanket OnClickListener in init - otherwise the
    // brightness slider's sun icon would also become clickable and
    // block drags starting on it. Only the volume slider opts in.
    private var onLeftClick: (() -> Unit)? = null
    // vc105 VERTICAL MODE: when the panel shows media
    // (playing), brightness + volume become vertical
    // bars side by side, instead of horizontal bars
    // stacked. Vertical geometry is the horizontal
    // geometry with axes swapped: fill grows from
    // the BOTTOM, left icon sits at the bottom,
    // right icon at the top, drag is vertical
    // (up = increase). Set BEFORE first layout
    // (panel builds sliders, calls setVertical,
    // then adds them) so icon/fill params are
    // correct on first draw. Horizontal behaviour
    // is unchanged when vertical == false.
    var vertical: Boolean = false
        private set
    fun setVertical(v: Boolean) {
        if (vertical == v) return
        vertical = v
        if (v) {
            fillView.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, thumbSize).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            leftIcon.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            rightIcon.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(40)).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            minimumWidth = dp(48)
            minimumHeight = 0
        } else {
            fillView.layoutParams = LayoutParams(thumbSize, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            leftIcon.layoutParams = LayoutParams(dp(40), LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            rightIcon.layoutParams = LayoutParams(dp(40), LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            minimumHeight = dp(48)
        }
        updateFill()
        requestLayout()
    }
    fun setLeftClickListener(action: (() -> Unit)?) {
        onLeftClick = action
        if (action != null) {
            leftIcon.isClickable = true
            leftIcon.setOnClickListener { action.invoke() }
        } else {
            leftIcon.isClickable = false
            leftIcon.setOnClickListener(null)
        }
    }

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
    // vc232: drag-fill glide animator (separate from setValue's
    // programmatic animator, so the two never fight).
    private var dragFillAnimator: ValueAnimator? = null

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
        // Left icon is non-clickable by default; see setLeftClickListener().
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

    // vc118 BASE-ICON ONLY: the panel sliders no
    // longer use a tip icon at all (user, 28/10).
    // Hiding, not just emptying, the right icon:
    // a GONE view takes no space and cannot eat
    // taps at the tip end, so drags starting there
    // reach the slider itself. Also clears its
    // click so a stale onRightClick cannot fire.
    fun hideRightIcon() {
        onRightClick = null
        rightIcon.setOnClickListener(null)
        rightIcon.visibility = View.GONE
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

    // vc232: fill visibility + icon tint follow the fill state, shared
    // by updateFill() (immediate) and the drag path (animated size).
    // At the bottom the fill hides, so the icon sits on the dark
    // track and must be WHITE; above the bottom the icon sits on the
    // white fill and stays dark grey (63,63,63).
    private fun updateFillState() {
        if (value <= minValue) {
            if (fillView.visibility != View.GONE) fillView.visibility = View.GONE
            leftIcon.setColorFilter(Color.WHITE)
        } else {
            if (fillView.visibility != View.VISIBLE) fillView.visibility = View.VISIBLE
            leftIcon.setColorFilter(Color.rgb(63, 63, 63))
        }
    }

    // vc232 DRAG ANIMATION: glide the fill edge toward the finger's
    // target value (100ms, DecelerateInterpolator) instead of jumping
    // to it. The VALUE itself still updates immediately (so brightness
    // / volume respond in real time); only the fill's visual edge
    // glides. Each drag move cancels the previous glide and starts a
    // new one from the current edge, so the fill chases the finger
    // without ever lagging more than 100ms behind it.
    private fun animateFillSizeTo(targetValue: Float) {
        if (targetValue <= minValue) return // hidden, nothing to glide
        dragFillAnimator?.cancel()
        if (vertical) {
            val h = height
            if (h <= 0) return
            val trackH = (h - paddingTop - paddingBottom).coerceAtLeast(thumbSize)
            if (trackH <= thumbSize) return
            val target = (thumbSize + (trackH - thumbSize) * targetValue).toInt()
            val params = fillView.layoutParams as LayoutParams
            val start = params.height
            if (start == target) return
            val anim = ValueAnimator.ofInt(start, target).apply {
                duration = 100L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val p = fillView.layoutParams as LayoutParams
                    p.height = it.animatedValue as Int
                    fillView.layoutParams = p
                }
            }
            dragFillAnimator = anim
            anim.start()
        } else {
            val w = width
            if (w <= 0) return
            val trackW = (w - paddingLeft - paddingRight).coerceAtLeast(thumbSize)
            if (trackW <= thumbSize) return
            val target = (thumbSize + (trackW - thumbSize) * targetValue).toInt()
            val params = fillView.layoutParams as LayoutParams
            val start = params.width
            if (start == target) return
            val anim = ValueAnimator.ofInt(start, target).apply {
                duration = 100L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    val p = fillView.layoutParams as LayoutParams
                    p.width = it.animatedValue as Int
                    fillView.layoutParams = p
                }
            }
            dragFillAnimator = anim
            anim.start()
        }
    }

    private fun updateFill() {
        // vc231: at the bottom (value == minValue) the level is not
        // shown at all — the fill hides, so the bar looks empty
        // (applies to both sliders: volume 0, brightness minimum).
        updateFillState()
        if (value <= minValue) return
        if (vertical) {
            val h = height
            if (h <= 0) {
                post { updateFill() }
                return
            }
            // vc230 MAX GLITCH: the fill must fit the CONTENT height
            // (inside padding), not the full view height. At value 1.0
            // the old formula gave fillHeight = h, 8dp taller than the
            // content area, so the fill's rounded top end was clipped
            // flat (same for the horizontal width below).
            val trackH = (h - paddingTop - paddingBottom).coerceAtLeast(thumbSize)
            val fillHeight = (thumbSize + (trackH - thumbSize) * value).toInt()
            val params = fillView.layoutParams as LayoutParams
            if (params.height != fillHeight) {
                params.height = fillHeight
                fillView.layoutParams = params
            }
            return
        }
        val w = width
        if (w <= 0) {
            post { updateFill() }
            return
        }
        // vc230 MAX GLITCH: see vertical branch above.
        val trackW = (w - paddingLeft - paddingRight).coerceAtLeast(thumbSize)
        val fillWidth = (thumbSize + (trackW - thumbSize) * value).toInt()
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
                // vc106 FIX (vc105 test: dragging a
                // vertical slider scrolled the panel):
                // a vertical drag is the SAME direction
                // as the panel ScrollView, so the
                // ScrollView intercepts the MOVE before
                // the slider's slop threshold is reached
                // and the whole panel follows the finger
                // instead of the value changing.
                // Horizontal sliders do not need this
                // (ScrollView never intercepts a
                // horizontal move), so they keep
                // disallow=false on DOWN and only
                // disallow once horizontal dragging
                // actually starts. Vertical sliders
                // must disallow on DOWN, immediately,
                // so the ScrollView never gets a chance
                // to steal the gesture. UP/CANCEL
                // already reset disallow to false.
                parent?.requestDisallowInterceptTouchEvent(vertical)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!dragging) {
                    if (vertical) {
                        // Vertical mirror of 9Flip p6.e():
                        // vertical only, 0.7 * slop,
                        // vertical > horizontal * 1.1,
                        // otherwise the parent
                        // ScrollView keeps the gesture.
                        if (kotlin.math.abs(dy) > touchSlop * 0.7f && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.1f) {
                            dragging = true
                            parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (kotlin.math.abs(dx) > touchSlop) {
                            return false
                        } else {
                            return true
                        }
                    } else {
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
                }
                if (dragging) {
                    if (vertical) {
                        val h = height
                        // vc230: drag maps within the CONTENT area
                        // (inside padding), matching the fixed fill
                        // geometry, so the fill edge tracks the
                        // finger and max is reached exactly at the
                        // content top, not past the view edge.
                        val trackH = (h - paddingTop - paddingBottom).coerceAtLeast(thumbSize)
                        if (trackH > thumbSize) {
                            // Fill from bottom: y at top
                            // (thumb/2) = max, y at bottom
                            // (h - thumb/2) = min.
                            val v = (1f - (event.y - paddingTop - thumbSize / 2f) / (trackH - thumbSize)).coerceIn(minValue, 1.0f)
                            if (v != value) {
                                value = v
                                // vc232: value updates now (real-time),
                                // fill edge glides to it (animated).
                                updateFillState()
                                animateFillSizeTo(v)
                                onValueChanged?.invoke(v)
                            }
                        }
                    } else {
                        val w = width
                        // vc230: see vertical branch above.
                        val trackW = (w - paddingLeft - paddingRight).coerceAtLeast(thumbSize)
                        if (trackW > thumbSize) {
                            val v = ((event.x - paddingLeft - thumbSize / 2f) / (trackW - thumbSize)).coerceIn(minValue, 1.0f)
                            if (v != value) {
                                value = v
                                // vc232: value updates now (real-time),
                                // fill edge glides to it (animated).
                                updateFillState()
                                animateFillSizeTo(v)
                                onValueChanged?.invoke(v)
                            }
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
                if (wasDragging) {
                    // vc232: stop the glide and snap the fill to the
                    // exact final value, so it never lags behind the
                    // finger after release.
                    dragFillAnimator?.cancel()
                    dragFillAnimator = null
                    updateFill()
                    onDragEnd?.invoke()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
