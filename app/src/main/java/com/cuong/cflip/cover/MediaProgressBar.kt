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
 * vc105 WAVEFORM (One UI 8+ media card, screenshot Cường sent
 * 27/10): the timeline itself IS the wave - no separate pill
 * under it. Bars across the full width, played bars white,
 * unplayed bars translucent white, thumb circle at the
 * progress point, exactly like the One UI card's wave line.
 * HONEST LIMIT: CFlip has no audio data (MediaState only
 * gives position/duration/title), so this is NOT the song's
 * real analysed waveform. Amplitudes are a deterministic
 * pseudo-random pattern seeded by the song title hash, so
 * the same song always shows the same wave and a different
 * song shows a different wave, but it does not follow the
 * actual music. A real waveform would need reading the
 * audio file / audio session, which CFlip does not do.
 */
class MediaProgressBar(context: Context) : View(context) {

    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }
    var onSeek: ((Float) -> Unit)? = null
    // vc105: seed for the decorative wave pattern.
    // Panel sets it from the song title hash on
    // build and on every media tick update.
    var waveformSeed: Int = 0
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

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
    private val barRect = RectF()

    // Deterministic amplitude for bar i, 0.2..1.0.
    // Mix of sines so the wave looks organic, not
    // uniform and not spiky. Seed shifts phase, so
    // different songs get different waves.
    private fun waveAmp(i: Int): Float {
        val s = waveformSeed.toFloat()
        val a = kotlin.math.abs(
            kotlin.math.sin(s * 0.013f + i * 0.9f) *
            kotlin.math.cos(i * 0.23f + s * 0.007f) +
            0.5f * kotlin.math.sin(i * 0.47f + s * 0.031f)
        )
        return (0.2f + 0.8f * a).coerceIn(0.2f, 1.0f)
    }

    private fun dp(v: Int): Float = v * context.resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        if (w <= 0f || h <= 0f) return
        // vc105 waveform: bars 3dp wide, 2dp gap,
        // centred vertically, rounded ends. Played
        // bars (centre left of progress) white,
        // unplayed bars translucent white, like
        // One UI's wave track in the screenshot.
        val barW = dp(3)
        val gap = dp(2)
        val step = barW + gap
        val count = (w / step).toInt().coerceIn(1, 160)
        val progressX = w * progress
        var x = 0f
        for (i in 0 until count) {
            val amp = waveAmp(i)
            val bh = h * amp
            val top = (h - bh) / 2f
            barRect.set(x, top, x + barW, top + bh)
            val paint = if (x + barW / 2f <= progressX) fillPaint else trackPaint
            canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, paint)
            x += step
        }
        val thumbR = h / 2f + dp(2)
        canvas.drawCircle(progressX.coerceIn(thumbR, (w - thumbR).coerceAtLeast(thumbR)), h / 2f, thumbR, thumbPaint)
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
