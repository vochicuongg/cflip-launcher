package com.cuong.cflip.cover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * vc37 media progress, like the Spotify / Samsung cover card Cường sent:
 * a THICK pill bar (not the thin 4dp line vc36 used), translucent track,
 * white fill, and a white round thumb at the end of the fill.
 * Tap / drag on it seeks. Times are drawn by the panel as separate
 * TextViews below the bar, like the screenshot (elapsed left, total right).
 */
class MediaProgressBar(context: Context) : View(context) {

    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var onSeek: ((Float) -> Unit)? = null

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(64, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val trackRect = RectF()
    private val fillRect = RectF()

    private fun dp(v: Int): Float = v * context.resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = h / 2f
        trackRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        val fillW = (w * progress).coerceIn(0f, w)
        if (fillW > 0f) {
            fillRect.set(0f, 0f, fillW, h)
            canvas.drawRoundRect(fillRect, radius, radius, fillPaint)
        }
        val thumbR = h / 2f + dp(2)
        canvas.drawCircle(fillW.coerceIn(thumbR, (w - thumbR).coerceAtLeast(thumbR)), h / 2f, thumbR, thumbPaint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val frac = (e.x / width.toFloat()).coerceIn(0f, 1f)
                progress = frac
                onSeek?.invoke(frac)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
