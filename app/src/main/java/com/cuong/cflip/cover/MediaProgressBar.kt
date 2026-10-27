package com.cuong.cflip.cover

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
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
 * vc113 CHANGES (user, 27/10, build vc112
 * already good):
 * - FOUR wave layers, darkest outermost:
 *   layer 0 (largest amp) = dominant hue at
 *   0.60x value, layer 1 = 0.75x, layer 2 =
 *   0.90x, layer 3 (smallest amp, innermost) =
 *   full dominant colour. Same hue family, so
 *   sweeping inward goes dark -> dominant.
 * - Wave ONLY ABOVE the timeline: bottom edge
 *   is flat at the pill bottom, never below it
 *   (vc112's symmetric ribbon below the centre
 *   is gone). View height back to 28dp (CC).
 * - GENTLER, SMOOTHER, 9Flip tempo: phase is
 *   a 68s linear ValueAnimator (exactly 9Flip
 *   e4/i: ofFloat(0, 50), 68000ms, Linear,
 *   repeat), updating at frame rate instead of
 *   a 100ms tick (10fps was part of the jerk).
 *   The 2Hz beat pulse from vc112 is REMOVED,
 *   and the height factor is back to 9Flip's
 *   gentle 0.14..0.76 (was vc112's full 0..1).
 * - NO SCALE CHANGE with progress: amplitude
 *   is constant (no progress factor, no pulse),
 *   and wavelength is FIXED at 32dp in absolute
 *   x, not 1 cycle across the played segment.
 *   So when the played segment is short (near
 *   the start) or long (near the end), each
 *   peak stays the same size - only the peak
 *   COUNT changes. Edge taper is also fixed:
 *   16dp smoothstep at each end, not a
 *   sin(pi*t) taper over the whole segment.
 *   This is a deliberate deviation from 9Flip's
 *   segment-relative shape, to fix the
 *   small-scale complaint; tempo (68s linear)
 *   and fade-in (0.05..0.09) still follow 9Flip.
 * - SEEK SMOOTHNESS (see onTouchEvent): drag
 *   updates the UI locally only, and seeks the
 *   session ONCE on finger-up. Previously every
 *   MOVE did a full seekTo + refresh + progress
 *   overwrite with the session's stale position,
 *   so the thumb fought the finger.
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
            updateWaveAnimation()
            invalidate()
        }
    var onSeek: ((Float) -> Unit)? = null

    // vc113 SEEK STATE: true while the finger
    // is down on the timeline. The panel's
    // 1s media tick must not overwrite progress
    // while this is true (that was half the
    // drag jerk: tick wrote the stale session
    // position over the finger position).
    var isSeeking: Boolean = false
        private set
    private var dragStartProgress: Float = 0f

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
                updateWaveAnimation()
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
    // vc113 FOUR layer paints: 0 = outermost
    // (darkest), 3 = innermost (dominant).
    private val waveLayerPaints = Array(4) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
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
    private val wavePaths = Array(4) { Path() }

    private fun hsv(hue: Float, sat: Float, value: Float): Int {
        return Color.HSVToColor(floatArrayOf(((hue % 360f) + 360f) % 360f, sat, value))
    }
    private fun updatePalette() {
        // vc108: art dominant colour wins; seed
        // hue is only the no-art fallback.
        val art = waveArt
        val dom = if (art != null) runCatching { dominantHsv(art) }.getOrNull() else null
        // vc113: same hue family, value ramps
        // dark -> dominant. Outermost layer is
        // darkest, innermost is the full dominant
        // colour (also the progress pill colour).
        val hue: Float
        val sat: Float
        val valFull: Float
        if (dom != null) {
            hue = dom.first; sat = dom.second; valFull = dom.third
        } else {
            hue = ((waveformSeed % 360) + 360).toFloat(); sat = 0.7f; valFull = 0.95f
        }
        val primary = hsv(hue, sat, valFull)
        progressPillPaint.color = primary
        val valueSteps = floatArrayOf(0.60f, 0.75f, 0.90f, 1.0f)
        for (i in 0 until 4) {
            waveLayerPaints[i].color = hsv(hue, sat, valFull * valueSteps[i])
        }
    }

    // vc113 9Flip TEMPO, frame-rate smooth:
    // 9Flip e4/i runs ValueAnimator.ofFloat(0,
    // 50) over 68000ms, Linear, repeat forever,
    // invalidating on every animator update
    // (i.e. at display refresh rate). CFlip's
    // old 100ms tick was only 10fps, so the
    // wave stepped visibly. This animator is
    // the same tempo, at frame rate, and it
    // only runs while playing, progress >=
    // 0.05 and attached - the same gate 9Flip
    // uses (its j() stops the animator below
    // 0.05). Phase jumps back every 68s, exactly
    // as 9Flip's repeat does (50 rad is not a
    // multiple of 2pi, so the jump is small
    // but real - same as the original).
    private var wavePhase: Float = 0f
    private var wavePhaseAnimator: ValueAnimator? = null
    private fun updateWaveAnimation() {
        val shouldRun = wavePlaying && progress >= 0.05f && isAttachedToWindow
        if (shouldRun && wavePhaseAnimator == null) {
            val anim = ValueAnimator.ofFloat(wavePhase, wavePhase + 50f).apply {
                duration = 68000L
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    wavePhase = it.animatedValue as Float
                    invalidate()
                }
            }
            wavePhaseAnimator = anim
            anim.start()
        } else if (!shouldRun) {
            wavePhaseAnimator?.cancel()
            wavePhaseAnimator = null
        }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateWaveAnimation()
    }
    override fun onDetachedFromWindow() {
        wavePhaseAnimator?.cancel()
        wavePhaseAnimator = null
        waveHeightAnimator?.cancel()
        waveHeightAnimator = null
        super.onDetachedFromWindow()
    }

    private fun smoothstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }
    // vc113 WAVE SHAPE: fixed wavelength, above
    // only, fixed edge taper.
    // - Wavelength is 32dp in ABSOLUTE x, not
    //   1 cycle across the played segment, so
    //   peak size never scales with progress.
    // - Height factor is 9Flip-gentle 0.14..0.76
    //   (main sine + small 2nd harmonic), not
    //   vc112's full 0..1 beat.
    // - Edge taper is a 16dp smoothstep at each
    //   end of the played segment, not a
    //   sin(pi*t) taper over the whole segment,
    //   so the middle of a long segment is full
    //   height throughout, not just at its centre.
    private fun waveFactorAtX(x: Float, left: Float, phase: Float, offset: Float): Float {
        val waveLen = dp(32)
        val p = (x - left) / waveLen * 6.2831855f
        val main = kotlin.math.sin(p + phase + offset)
        val mod = kotlin.math.sin(p * 2f + phase * 0.5f + offset)
        val v = ((main * 0.7f + mod * 0.3f + 1f) / 2f * 0.62f + 0.14f)
        return v.coerceIn(0f, 1f)
    }
    // Top edge varies, bottom edge is flat at
    // pillBottom: wave lives ONLY above the
    // timeline, never below it.
    private fun buildWavePath(path: Path, left: Float, right: Float, centerY: Float, pillBottom: Float, amp: Float, phase: Float, offset: Float) {
        path.reset()
        if (right - left <= 1f) return
        // Sample about every 4dp, so a 32dp peak
        // gets ~8 points - smooth without waste.
        val steps = ((right - left) / dp(4)).toInt().coerceIn(16, 128)
        val xs = FloatArray(steps + 1)
        val topYs = FloatArray(steps + 1)
        val taperLen = dp(16)
        for (i in 0..steps) {
            val x = left + (right - left) * i.toFloat() / steps
            val distEdge = kotlin.math.min(x - left, right - x)
            val taper = smoothstep(distEdge / taperLen)
            topYs[i] = centerY - amp * taper * waveFactorAtX(x, left, phase, offset)
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
        path.lineTo(xs[steps], pillBottom)
        path.lineTo(xs[0], pillBottom)
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
        // Wave: played segment only, FOUR layers,
        // largest + darkest first, smallest +
        // dominant last, so the dark outer layer
        // peeks out above the brighter inner
        // layers. vc113: opaque (255, kept from
        // vc112), constant amplitude (no progress
        // factor, no beat pulse), 9Flip phase
        // from the frame-rate animator above.
        if (progressX > left + radius && waveVisible > 0.02f) {
            val baseAmp = kotlin.math.min(h * 0.5f, dp(14))
            val amp = baseAmp * waveVisible
            val waveAlpha = (255f * waveVisible).toInt().coerceIn(0, 255)
            val ampSteps = floatArrayOf(1.0f, 0.85f, 0.70f, 0.55f)
            val offsets = floatArrayOf(0f, 0.8f, 1.6f, 2.4f)
            for (i in 0 until 4) {
                buildWavePath(wavePaths[i], left, progressX, centerY, pillBottom, amp * ampSteps[i], wavePhase, offsets[i])
                val paint = waveLayerPaints[i]
                val saved = paint.alpha
                paint.alpha = waveAlpha
                canvas.drawPath(wavePaths[i], paint)
                paint.alpha = saved
            }
        }
        // Thumb: shadow + white solid circle, 7dp.
        if (progressX in left..right) {
            canvas.drawCircle(progressX, centerY, dp(8), thumbShadowPaint)
            canvas.drawCircle(progressX, centerY, dp(7), thumbPaint)
        }
    }

    // vc113 SEEK SMOOTHNESS: drag updates the
    // UI locally ONLY (thumb + elapsed follow
    // the finger with no IPC in between), and
    // the session is seeked ONCE on finger-up.
    // Old code seeked + refreshed + overwrote
    // progress on EVERY move, so the thumb
    // fought the finger between the finger
    // position and the session's stale position.
    // CANCEL reverts to the drag-start position
    // (interrupted drag, not a completed seek).
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                isSeeking = true
                dragStartProgress = progress
                val inset = dp(8)
                val trackW = (width.toFloat() - 2f * inset).coerceAtLeast(1f)
                progress = ((e.x - inset) / trackW).coerceIn(0f, 1f)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isSeeking) {
                    isSeeking = true
                    dragStartProgress = progress
                }
                val inset = dp(8)
                val trackW = (width.toFloat() - 2f * inset).coerceAtLeast(1f)
                progress = ((e.x - inset) / trackW).coerceIn(0f, 1f)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val inset = dp(8)
                val trackW = (width.toFloat() - 2f * inset).coerceAtLeast(1f)
                val frac = ((e.x - inset) / trackW).coerceIn(0f, 1f)
                progress = frac
                isSeeking = false
                onSeek?.invoke(frac)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                isSeeking = false
                progress = dragStartProgress
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
