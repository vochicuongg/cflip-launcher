package com.cuong.cflip.cover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * vc107 media timeline, following 9Flip e4/i (decompiled
 * 27/10, clean-room re-implementation, NOT a copy):
 * - View height 28dp, timeline inset 8dp left/right.
 * - Track: thin pill, height 4dp, centred, white 128
 *   alpha, drawn ONLY for the unplayed segment
 *   (progressX..right), like 9Flip.
 * - Progress pill: same 4dp pill, primary colour at
 *   150 alpha, drawn ONLY for the played segment,
 *   fading out as the wave fades in (progress
 *   0.05..0.09, smoothstep). At normal progress the
 *   pill is gone and the wave is the timeline.
 * - Wave: NOT discrete bars (vc105 was wrong - 9Flip
 *   has no bars). Two continuous smooth filled wave
 *   paths, top edge a sine wave with an envelope
 *   that is 0 at both ends of the played segment
 *   and 1 in the middle, bottom edge at the pill
 *   bottom, clipped to the played segment only.
 *   Primary colour at 128 alpha * fadeIn, secondary
 *   colour (hue-shifted primary) at the same alpha.
 *   Wave phase animates slowly (68s loop, like
 *   9Flip's 68s ValueAnimator) while playing and
 *   progress >= 0.05, via a 100ms invalidate tick.
 * - Palette: primary hue from the song title hash,
 *   secondary = primary hue + 140 degrees, like
 *   9Flip's hash palette + hue shift (own hue set,
 *   not 9Flip's 24-colour list).
 * - Thumb: white solid circle radius 7dp at
 *   progressX, with a soft black 32 alpha shadow
 *   circle radius 8dp under it, like 9Flip's
 *   seekable thumb (shadow gradient + white fill).
 * - Seek: touch x maps through the 8dp inset, so
 *   the seek point matches the drawn progressX.
 * HONEST LIMIT (unchanged from vc105): the wave
 * shape is a synthetic sine wave, not the song's
 * real analysed waveform - 9Flip's wave is also
 * synthetic (its shape comes from sine functions
 * and the metadata hash, not from audio data).
 * What vc107 copies from 9Flip is the SHAPE and
 * BEHAVIOUR (smooth filled wave, pill track,
 * palette, fade-in, slow animation), not real
 * audio analysis, which neither app does here.
 */
class MediaProgressBar(context: Context) : View(context) {

    var progress: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            updateWaveTick()
            invalidate()
        }
    var onSeek: ((Float) -> Unit)? = null

    var waveformSeed: Int = 0
        set(v) {
            if (field != v) {
                field = v
                updatePalette()
                invalidate()
            }
        }
    var wavePlaying: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                updateWaveTick()
            }
        }

    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int): Float = v * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val progressPillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val wavePrimaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val waveSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val thumbShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(32, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val trackRect = RectF()
    private val pillRect = RectF()
    private val wavePathPrimary = Path()
    private val wavePathSecondary = Path()

    private fun hsv(hue: Float, sat: Float, value: Float): Int {
        return Color.HSVToColor(floatArrayOf(((hue % 360f) + 360f) % 360f, sat, value))
    }
    private fun updatePalette() {
        val hue = ((waveformSeed % 360) + 360).toFloat()
        val primary = hsv(hue, 0.7f, 0.95f)
        val secondary = hsv(hue + 140f, 0.65f, 0.95f)
        progressPillPaint.color = primary
        wavePrimaryPaint.color = primary
        waveSecondaryPaint.color = secondary
    }

    private val waveTickRunnable: Runnable = object : Runnable {
        override fun run() {
            if (wavePlaying && progress >= 0.05f && isAttachedToWindow) {
                invalidate()
                postDelayed(this, 100)
            }
        }
    }
    private fun updateWaveTick() {
        removeCallbacks(waveTickRunnable)
        if (wavePlaying && progress >= 0.05f && isAttachedToWindow) {
            postDelayed(waveTickRunnable, 100)
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateWaveTick()
    }
    override fun onDetachedFromWindow() {
        removeCallbacks(waveTickRunnable)
        super.onDetachedFromWindow()
    }

    private fun smoothstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }
    // Wave height factor for point t in 0..1, phase in
    // 0..50 (9Flip's 68s loop), offset shifts the
    // second layer. Returns 0..1.
    private fun waveFactor(t: Float, phase: Float, offset: Float): Float {
        val main = kotlin.math.sin(t * 6.2831855f + phase + offset)
        val mod = kotlin.math.sin(10.5f * phase * 0.1f + t * 6.2831855f + offset)
        val v = ((main * 0.7f + mod * 0.3f + 1f) / 2f * 0.62f + 0.14f)
        return v.coerceIn(0f, 1f)
    }
    private fun buildWavePath(path: Path, left: Float, right: Float, centerY: Float, bottom: Float, amp: Float, phase: Float, offset: Float) {
        path.reset()
        if (right - left <= 1f) return
        val steps = 64
        var prevX = left
        var prevY = centerY
        path.moveTo(left, bottom)
        path.lineTo(left, centerY)
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = left + (right - left) * t
            val envelope = kotlin.math.sin(t * 3.1415927f).coerceAtLeast(0f)
            val y = centerY - amp * envelope * waveFactor(t, phase, offset)
            if (i == 0) {
                prevX = x; prevY = y
                path.lineTo(x, y)
            } else {
                // Smooth curve through midpoints, like
                // 9Flip's quadTo chain in e4/i.a().
                val midX = (prevX + x) / 2f
                val midY = (prevY + y) / 2f
                path.quadTo(prevX, prevY, midX, midY)
                prevX = x; prevY = y
            }
        }
        path.lineTo(right, prevY)
        path.lineTo(right, bottom)
        path.lineTo(left, bottom)
        path.close()
    }

    init {
        updatePalette()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val w = width.toFloat()
        if (w <= 0f || h <= 0f) return
        val inset = dp(8)
        val left = inset
        val right = (w - inset).coerceAtLeast(left)
        val trackW = (right - left).coerceAtLeast(0f)
        val centerY = h / 2f
        val pillH = dp(4)
        val pillTop = centerY - pillH / 2f
        val pillBottom = centerY + pillH / 2f
        val radius = pillH / 2f
        val progressX = left + trackW * progress
        val fadeIn = smoothstep((progress - 0.05f) / 0.04f)
        // Track: unplayed segment only.
        if (progressX < right) {
            trackRect.set(progressX, pillTop, right, pillBottom)
            canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        }
        // Progress pill: played segment, fading out
        // as the wave fades in (progress 0.05..0.09).
        if (progressX > left && fadeIn < 1f) {
            val saved = progressPillPaint.alpha
            progressPillPaint.alpha = (150f * (1f - fadeIn)).toInt().coerceIn(0, 150)
            pillRect.set(left, pillTop, progressX, pillBottom)
            canvas.drawRoundRect(pillRect, radius, radius, progressPillPaint)
            progressPillPaint.alpha = saved
        }
        // Wave: played segment only, two layers.
        if (progressX > left + radius && fadeIn > 0f) {
            val phase = ((System.currentTimeMillis() % 68000L).toFloat() / 68000f) * 50f
            val baseAmp = kotlin.math.min(h * 0.6f, dp(16))
            val amp = baseAmp * (1f + 0.33f * progress) * (0.65f * fadeIn + 0.35f)
            val waveAlpha = (128f * fadeIn).toInt().coerceIn(0, 128)
            buildWavePath(wavePathSecondary, left, progressX, centerY, pillBottom, amp, phase, 1.0f)
            val savedSec = waveSecondaryPaint.alpha
            waveSecondaryPaint.alpha = waveAlpha
            canvas.drawPath(wavePathSecondary, waveSecondaryPaint)
            waveSecondaryPaint.alpha = savedSec
            buildWavePath(wavePathPrimary, left, progressX, centerY, pillBottom, amp, phase, 0f)
            val savedPri = wavePrimaryPaint.alpha
            wavePrimaryPaint.alpha = waveAlpha
            canvas.drawPath(wavePathPrimary, wavePrimaryPaint)
            wavePrimaryPaint.alpha = savedPri
        }
        // Thumb: shadow + white solid circle, 7dp.
        if (progressX in left..right) {
            canvas.drawCircle(progressX, centerY, dp(8), thumbShadowPaint)
            canvas.drawCircle(progressX, centerY, dp(7), thumbPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val inset = dp(8)
                val trackW = (width.toFloat() - 2f * inset).coerceAtLeast(1f)
                val frac = ((e.x - inset) / trackW).coerceIn(0f, 1f)
                progress = frac
                onSeek?.invoke(frac)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
