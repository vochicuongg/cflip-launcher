package com.cuong.cflip.cover

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.animation.DecelerateInterpolator
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
 * vc112 CHANGES (user, 27/10):
 * - Wave DENSE, no transparency: wave alpha
 *   255 (was 128), pill alpha 255 (was 150).
 * - No unrelated green: secondary is no longer
 *   hue+140 (that turned a pink/red art into a
 *   green second layer). Secondary is now hue+30
 *   (analogous, same colour family as the art),
 *   front layer is the same hue brighter, so all
 *   wave layers come from the art's dominant
 *   colour. Dominant colour also weights central
 *   pixels more than corners, so the subject
 *   wins over a saturated background.
 * - STRONGER, MORE WAVES, like Samsung SystemUI:
 *   view height 36dp (CC, was 28dp), symmetric
 *   ribbon (above AND below the centre line, not
 *   only above the pill), 3 layers at 3 / 4 / 5
 *   cycles across the played segment (was 1
 *   cycle, 2 layers), full 0..1 height factor
 *   (was 0.14..0.76), faster phase morph (20s
 *   loop, was 68s), plus a 2Hz beat pulse (15%
 *   amplitude, ~120 BPM tempo) so the wave
 *   visibly beats. HONEST LIMIT, unchanged:
 *   beat pulse is a synthetic tempo, not real
 *   audio amplitude - MediaSession here gives
 *   position / duration / art only, no waveform
 *   data, so neither CFlip nor a 9Flip-style
 *   synthetic wave can beat to the real music.
 * vc108 CHANGES (user, 27/10):
 * - Wave colour: dominant colour of the album
 *   art (sampled, vivid-weighted, saturation /
 *   value clamped so the wave stays visible on
 *   the dark card), falling back to the title-
 *   hash hue when art is null. Secondary colour
 *   is still primary hue + 140 degrees.
 * - Stop: when wavePlaying goes true -> false,
 *   the wave does NOT vanish instantly. Its
 *   height factor animates 1 -> 0 over 300ms,
 *   so the wave lowers down into the pill
 *   timeline, and the pill takes over (pill
 *   alpha returns to 150 as the wave alpha
 *   returns to 0). Resume animates 0 -> 1.
 *   First set at panel build is direct, not
 *   animated, so opening the panel while
 *   playing does not start with a rising wave.
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
                animateWaveHeight(if (v) 1f else 0f)
            }
        }
    // vc108 wave height factor: 1 = full wave,
    // 0 = wave collapsed into the pill timeline.
    // Animated on play/stop, direct on first set.
    private var waveHeightFactor = 0f
    private var waveFactorInitialized = false
    private var waveHeightAnimator: ValueAnimator? = null
    private fun animateWaveHeight(target: Float) {
        if (!waveFactorInitialized) {
            waveFactorInitialized = true
            waveHeightAnimator?.cancel()
            waveHeightAnimator = null
            waveHeightFactor = target.coerceIn(0f, 1f)
            invalidate()
            return
        }
        if (waveHeightFactor == target.coerceIn(0f, 1f)) return
        waveHeightAnimator?.cancel()
        val anim = ValueAnimator.ofFloat(waveHeightFactor, target.coerceIn(0f, 1f)).apply {
            duration = 300L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                waveHeightFactor = it.animatedValue as Float
                invalidate()
            }
        }
        waveHeightAnimator = anim
        anim.start()
    }
    // vc108: album art for dominant-colour palette.
    // Stored as a reference only (panel already
    // holds the bitmap); null = fall back to seed.
    private var waveArt: Bitmap? = null
    fun setMediaArt(art: Bitmap?) {
        if (waveArt === art) return
        waveArt = art
        updatePalette()
        invalidate()
    }
    // Dominant colour: sample up to 32x32 pixels,
    // skip transparent / grey / dark pixels (they
    // do not make a good wave colour), bucket hues
    // into 12 x 30-degree buckets weighted by
    // saturation * value, pick the heaviest bucket,
    // average its pixels, clamp saturation /
    // value so the wave stays vivid on dark art.
    // Returns (hue, sat, value) or null if no vivid
    // pixel was found (then seed hue is used).
    private fun dominantHsv(art: Bitmap): Triple<Float, Float, Float>? {
        val w = art.width
        val h = art.height
        if (w <= 0 || h <= 0) return null
        val stepX = kotlin.math.max(1, w / 32)
        val stepY = kotlin.math.max(1, h / 32)
        val weightSum = FloatArray(12)
        val hueSum = FloatArray(12)
        val satSum = FloatArray(12)
        val valSum = FloatArray(12)
        val hsv = FloatArray(3)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val pixel = art.getPixel(x, y)
                if (android.graphics.Color.alpha(pixel) >= 128) {
                    android.graphics.Color.colorToHSV(pixel, hsv)
                    val sat = hsv[1]
                    val value = hsv[2]
                    if (sat >= 0.2f && value >= 0.2f) {
                        val bucket = ((hsv[0] / 30f).toInt() % 12).coerceIn(0, 11)
                        // vc112: central pixels count
                        // up to 2x more than corners,
                        // so the art subject wins over
                        // a saturated background.
                        val dx = (x - w / 2f) / (w / 2f)
                        val dy = (y - h / 2f) / (h / 2f)
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy) / 1.4142135f
                        val centrality = 1f - 0.5f * dist.coerceIn(0f, 1f)
                        val weight = sat * value * centrality
                        weightSum[bucket] += weight
                        hueSum[bucket] += hsv[0] * weight
                        satSum[bucket] += sat * weight
                        valSum[bucket] += value * weight
                    }
                }
                x += stepX
            }
            y += stepY
        }
        var best = -1
        var bestWeight = 0f
        for (i in 0 until 12) {
            if (weightSum[i] > bestWeight) {
                bestWeight = weightSum[i]
                best = i
            }
        }
        if (best < 0 || bestWeight <= 0f) return null
        val hue = (hueSum[best] / bestWeight).coerceIn(0f, 360f)
        val sat = (satSum[best] / bestWeight).coerceIn(0.62f, 0.94f)
        val value = (valSum[best] / bestWeight).coerceIn(0.88f, 1.0f)
        return Triple(hue, sat, value)
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
    private val waveFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
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
    private val wavePathFront = Path()

    private fun hsv(hue: Float, sat: Float, value: Float): Int {
        return Color.HSVToColor(floatArrayOf(((hue % 360f) + 360f) % 360f, sat, value))
    }
    private fun updatePalette() {
        // vc108: art dominant colour wins; seed
        // hue is only the no-art fallback.
        val art = waveArt
        val dom = if (art != null) runCatching { dominantHsv(art) }.getOrNull() else null
        val primary: Int
        val secondary: Int
        val front: Int
        if (dom != null) {
            primary = hsv(dom.first, dom.second, dom.third)
            // vc112: analogous +30, NOT +140 -
            // +140 turned pink/red art green.
            secondary = hsv(dom.first + 30f, dom.second, dom.third * 0.92f)
            front = hsv(dom.first, dom.second * 0.85f, 1.0f)
        } else {
            val hue = ((waveformSeed % 360) + 360).toFloat()
            primary = hsv(hue, 0.7f, 0.95f)
            secondary = hsv(hue + 30f, 0.65f, 0.90f)
            front = hsv(hue, 0.6f, 1.0f)
        }
        progressPillPaint.color = primary
        wavePrimaryPaint.color = primary
        waveSecondaryPaint.color = secondary
        waveFrontPaint.color = front
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
        waveHeightAnimator?.cancel()
        waveHeightAnimator = null
        super.onDetachedFromWindow()
    }

    private fun smoothstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }
    // Wave height factor for point t in 0..1, phase in
    // 0..50 (9Flip's 68s loop), offset shifts the
    // second layer. Returns 0..1.
    private fun waveFactor(t: Float, phase: Float, offset: Float, freq: Float): Float {
        val main = kotlin.math.sin(t * 6.2831855f * freq + phase + offset)
        val mod = kotlin.math.sin(t * 6.2831855f * freq * 2.3f + phase * 0.5f + offset)
        val v = 0.5f + 0.5f * (main * 0.7f + mod * 0.3f)
        return v.coerceIn(0f, 1f)
    }
    // vc112 SYMMETRIC RIBBON: top edge above the
    // centre line and bottom edge below it, both
    // with their own frequency / phase, so the
    // ribbon thickness beats instead of a flat
    // bottom at the pill. Top and bottom are each
    // quadTo-smoothed through midpoints, like
    // vc107's top edge.
    private fun buildWavePath(path: Path, left: Float, right: Float, centerY: Float, amp: Float, phase: Float, offset: Float, freq: Float) {
        path.reset()
        if (right - left <= 1f) return
        val steps = 64
        val xs = FloatArray(steps + 1)
        val topYs = FloatArray(steps + 1)
        val botYs = FloatArray(steps + 1)
        for (i in 0..steps) {
            val tt = i.toFloat() / steps
            val x = left + (right - left) * tt
            val envelope = kotlin.math.sin(tt * 3.1415927f).coerceAtLeast(0f)
            topYs[i] = centerY - amp * envelope * waveFactor(tt, phase, offset, freq)
            botYs[i] = centerY + amp * envelope * waveFactor(tt, phase, offset + 3.1415927f, freq * 0.85f)
            xs[i] = x
        }
        path.moveTo(xs[0], topYs[0])
        var prevX = xs[0]
        var prevY = topYs[0]
        for (i in 1..steps) {
            val midX = (prevX + xs[i]) / 2f
            val midY = (prevY + topYs[i]) / 2f
            path.quadTo(prevX, prevY, midX, midY)
            prevX = xs[i]; prevY = topYs[i]
        }
        path.lineTo(xs[steps], topYs[steps])
        path.lineTo(xs[steps], botYs[steps])
        prevX = xs[steps]; prevY = botYs[steps]
        for (i in steps - 1 downTo 0) {
            val midX = (prevX + xs[i]) / 2f
            val midY = (prevY + botYs[i]) / 2f
            path.quadTo(prevX, prevY, midX, midY)
            prevX = xs[i]; prevY = botYs[i]
        }
        path.lineTo(xs[0], botYs[0])
        path.lineTo(xs[0], topYs[0])
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
        // vc108 effective wave visibility: progress
        // fade-in AND height factor. When stopped,
        // height factor goes 1 -> 0, so waveAlpha
        // goes 128 -> 0 and pillAlpha goes back to
        // 150 - the wave lowers into the pill.
        val waveVisible = fadeIn * waveHeightFactor
        // Track: unplayed segment only.
        if (progressX < right) {
            trackRect.set(progressX, pillTop, right, pillBottom)
            canvas.drawRoundRect(trackRect, radius, radius, trackPaint)
        }
        // Progress pill: played segment, fading out
        // as the wave fades in, fading back in as
        // the wave lowers on stop. vc112: opaque
        // (255, was 150) for a dense timeline.
        if (progressX > left && waveVisible < 1f) {
            val saved = progressPillPaint.alpha
            progressPillPaint.alpha = (255f * (1f - waveVisible)).toInt().coerceIn(0, 255)
            pillRect.set(left, pillTop, progressX, pillBottom)
            canvas.drawRoundRect(pillRect, radius, radius, progressPillPaint)
            progressPillPaint.alpha = saved
        }
        // Wave: played segment only, THREE layers,
        // largest first so each smaller layer peeks
        // out above / below it. vc112: opaque
        // (255, was 128), 3 / 4 / 5 cycles across
        // the segment (was 1 cycle, 2 layers),
        // faster morph (20s loop, was 68s), and a
        // 2Hz beat pulse (15% amplitude) so the
        // wave visibly beats like SystemUI.
        if (progressX > left + radius && waveVisible > 0.02f) {
            val nowMs = System.currentTimeMillis()
            val phase = ((nowMs % 20000L).toFloat() / 20000f) * 50f
            val pulse = 0.85f + 0.15f * (0.5f + 0.5f * kotlin.math.sin(nowMs.toFloat() * 6.2831855f / 500f))
            val baseAmp = kotlin.math.min(h * 0.45f, dp(18))
            val amp = baseAmp * (1f + 0.2f * progress) * waveVisible * pulse
            val waveAlpha = (255f * waveVisible).toInt().coerceIn(0, 255)
            buildWavePath(wavePathSecondary, left, progressX, centerY, amp, phase, 1.2f, 3f)
            val savedSec = waveSecondaryPaint.alpha
            waveSecondaryPaint.alpha = waveAlpha
            canvas.drawPath(wavePathSecondary, waveSecondaryPaint)
            waveSecondaryPaint.alpha = savedSec
            buildWavePath(wavePathPrimary, left, progressX, centerY, amp * 0.85f, phase, 0f, 4f)
            val savedPri = wavePrimaryPaint.alpha
            wavePrimaryPaint.alpha = waveAlpha
            canvas.drawPath(wavePathPrimary, wavePrimaryPaint)
            wavePrimaryPaint.alpha = savedPri
            buildWavePath(wavePathFront, left, progressX, centerY, amp * 0.7f, phase, 2.0f, 5f)
            val savedFront = waveFrontPaint.alpha
            waveFrontPaint.alpha = waveAlpha
            canvas.drawPath(wavePathFront, waveFrontPaint)
            waveFrontPaint.alpha = savedFront
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
