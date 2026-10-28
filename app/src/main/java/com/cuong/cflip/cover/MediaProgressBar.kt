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
 * vc142 (user, 29/10 - chưa pull vc141, bản này chồng lên vc141):
 * - REAL AMPLITUDE: the wave is now driven by AudioWaveCapture
 *   (Visualizer on the output mix) when it works: bass hits rise tall,
 *   quiet passages go flat, because the heights are the music's real
 *   RMS amplitude, not a sine. When capture is unavailable (init
 *   throws, or the device returns exact-flat output = blocked mix),
 *   the view falls back to the synthetic 4-layer sine wave below, so
 *   the UI never goes dead. Capture is the whole device mix, not
 *   per-app - that is a platform limit, not a bug to chase.
 * - SMOOTHING: capture lands at ~10-20Hz; the view interpolates to
 *   display refresh (60/120Hz) with attack/release per bar (rise fast
 *   0.45/frame, fall slow 0.18/frame), so peaks snap up at once and
 *   settle smoothly instead of jerking.
 * - COLOUR GRADIENT: the wave no longer uses one hue family only.
 *   The art's dominant hue is the gradient start and the runner-up
 *   hue (or hue + 30 when the art is near-monochrome) is the end, so
 *   the bars sweep primary -> secondary across the played segment.
 * - TIME CURSOR, WRAPPED IN COLOUR: the old white thumb is replaced
 *   by a primary-colour core, a secondary-colour ring around it, and
 *   a soft primary glow, all from the album art. It grows slightly
 *   while the finger drags it. Seek behaviour is unchanged (drag
 *   follows the finger, session seeks once on finger-up).
 * This supersedes the vc107/vc112 HONEST LIMIT notes for the case
 * where capture works; the synthetic wave remains the fallback.
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
 * vc115 CHANGES (user, 27/10, build vc114
 * already very good):
 * - BEAT STRONGER, a bit: alternating layer
 *   beat factor goes from 0.925 +/- 0.075
 *   (0.85..1.0) to 0.90 +/- 0.10 (0.80..1.0),
 *   same 1600ms alternating cycle, same order
 *   0 -> 1 -> 2 -> 3 -> 0. Stronger, but still
 *   spread across layers, not a simultaneous
 *   pump like vc112.
 * - BRIGHTER + SLIGHTLY TRANSLUCENT: saturation
 *   boost 1.10 -> 1.20 (capped 0.98), value steps
 *   0.75/0.85/0.95/1.0 -> 0.80/0.88/0.95/1.0, and
 *   wave alpha 255 -> 220 (about 14% transparent,
 *   "mo mot chut"). The pill stays opaque (255)
 *   when the wave has collapsed, so the stopped
 *   timeline stays crisp.
 * vc114 CHANGES (user, 27/10, build vc113
 * already very good):
 * - ALTERNATING LAYER BEAT: layers no longer
 *   pulse together (vc112) and no longer have
 *   no beat at all (vc113). Each layer has its
 *   own beat factor, 0.85..1.0, with peaks
 *   staggered by a quarter cycle, in fixed
 *   order layer 0 -> 1 -> 2 -> 3 -> 0, repeating
 *   on a definite 1600ms loop (peak moves every
 *   600ms). So at any moment one layer is at
 *   its peak while the others are lower, and
 *   prominence rolls through the layers instead
 *   of the whole wave pumping at once. That is
 *   what makes it feel natural but still cyclic:
 *   the beat order and period are fixed, never
 *   random. The slow 68s shape morph (9Flip)
 *   stays underneath, so shape and beat are two
 *   separate, definite cycles.
 * - BRIGHTER GAMUT: same dominant hue family
 *   (no unrelated colours), but saturation is
 *   boosted 10% (capped at 0.95) and the value
 *   steps are raised from 0.60/0.75/0.90/1.0 to
 *   0.75/0.85/0.95/1.0, so the outermost layer
 *   is still darkest but no longer dull, and
 *   the whole wave reads fresher on the dark
 *   card. The progress pill uses the same
 *   boosted innermost colour, so wave -> pill
 *   on stop does not change hue.
 * - FASTER BEAT, One UI 9 feel: HONEST NOTE -
 *   there is no decompiled One UI 9 SystemUI
 *   waveform tempo to copy exactly, so the
 *   1600ms layer cycle is a chosen middle
 *   ground, not a claimed One UI 9 value: faster
 *   and livelier than vc113's beatless wave,
 *   but gentler than vc112's simultaneous 2Hz
 *   pulse, because the beat is spread across
 *   layers instead of pumping the whole wave.
 *   If it still feels slow / fast, the cycle is
 *   one number (1600ms) and can be retuned in
 *   one line - tell me and I will shorten it
 *   (e.g. 1200ms) or lengthen it (e.g. 2400ms).
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
    // vc124 DRAG TIME: fires on DOWN/MOVE/CANCEL with the finger
    // fraction so the panel can update the elapsed number live
    // while dragging. The session itself is still seeked ONLY on
    // finger-up via onSeek (vc113) - this callback is UI-only.
    var onSeekDragging: ((Float) -> Unit)? = null

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
                // vc142: a new track retries real capture if an
                // earlier track was blocked into fallback.
                waveCapture.resetBlocked()
                startCapture()
                invalidate()
            }
        }
    var wavePlaying: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                updateWaveAnimation()
                animateWaveHeight(if (v) 1f else 0f)
                // vc142: capture only while playing, never while
                // paused or stopped (battery + a settled wave).
                if (v) startCapture() else stopCapture()
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
    // vc142 REAL AMPLITUDE + SMOOTHING + CURSOR: the capture wrapper,
    // per-bar smoothed heights (main thread only), and the gradient
    // colours. Smoothing state is reset whenever the bar count changes
    // (played segment grows as the song progresses).
    private val waveCapture = AudioWaveCapture()
    private var smoothedBars = FloatArray(0)
    private var wavePrimaryColor: Int = Color.WHITE
    private var waveSecondaryColor: Int = Color.WHITE
    private fun startCapture() {
        if (!isAttachedToWindow || !wavePlaying) return
        if (waveCapture.captureActive) return
        waveCapture.onBars = { postInvalidate() }
        waveCapture.start()
    }
    private fun stopCapture() {
        waveCapture.stop()
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
    // vc142: top TWO hue buckets (dominant + runner-up) for the
    // gradient (primary -> secondary) and the cursor ring. The
    // runner-up must differ by more than 25 degrees from the best,
    // otherwise the gradient would be invisible on near-monochrome
    // art (the call site then falls back to hue + 30).
    private fun dominantHues(art: Bitmap): List<Triple<Float, Float, Float>> {
        val w = art.width
        val h = art.height
        if (w <= 0 || h <= 0) return emptyList()
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
        fun tripleFor(bucket: Int): Triple<Float, Float, Float> {
            val wSum = weightSum[bucket]
            return Triple(
                (hueSum[bucket] / wSum).coerceIn(0f, 360f),
                (satSum[bucket] / wSum).coerceIn(0.62f, 0.94f),
                (valSum[bucket] / wSum).coerceIn(0.88f, 1.0f)
            )
        }
        var best = -1
        var bestWeight = 0f
        for (i in 0 until 12) {
            if (weightSum[i] > bestWeight) {
                bestWeight = weightSum[i]
                best = i
            }
        }
        if (best < 0 || bestWeight <= 0f) return emptyList()
        val result = mutableListOf(tripleFor(best))
        val bestHue = hueSum[best] / bestWeight
        var second = -1
        var secondWeight = 0f
        for (i in 0 until 12) {
            if (i == best || weightSum[i] <= 0f) continue
            val hueI = hueSum[i] / weightSum[i]
            val diff = kotlin.math.abs(((hueI - bestHue + 540f) % 360f) - 180f)
            if (diff > 25f && weightSum[i] > secondWeight) {
                secondWeight = weightSum[i]
                second = i
            }
        }
        if (second >= 0) result.add(tripleFor(second))
        return result
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
    // vc142 TIME CURSOR paints: the playhead is wrapped in colour -
    // primary-colour core + secondary-colour ring + primary glow -
    // instead of the old white thumb. Colours are set in updatePalette.
    private val cursorGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val cursorRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2)
    }
    private val cursorDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val barRect = RectF()
    private val trackRect = RectF()
    private val pillRect = RectF()
    private val wavePaths = Array(4) { Path() }

    private fun hsv(hue: Float, sat: Float, value: Float): Int {
        return Color.HSVToColor(floatArrayOf(((hue % 360f) + 360f) % 360f, sat, value))
    }
    // vc142: gradient interpolation between two opaque colours, used
    // for the real bars (primary -> secondary across the segment).
    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        return Color.argb(255,
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt())
    }
    private fun updatePalette() {
        // vc108: art dominant colour wins; seed
        // hue is only the no-art fallback.
        val art = waveArt
        val doms = if (art != null) runCatching { dominantHues(art) }.getOrNull() else null
        val dom = doms?.firstOrNull()
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
            // vc117 SYSTEMUI COLOUR: no art, so
            // instead of a title-hash hue, try
            // Android's system accent (Material You,
            // derived from the wallpaper - the same
            // dynamic colour SystemUI itself uses
            // for its UI). If the resource is missing
            // on this ROM, fall back to the old
            // title-hash hue.
            val sys = runCatching {
                val hsv = FloatArray(3)
                android.graphics.Color.colorToHSV(context.getColor(android.R.color.system_accent1_500), hsv)
                hsv
            }.getOrNull()
            if (sys != null && sys[1] > 0.05f) {
                hue = sys[0]; sat = sys[1].coerceIn(0.35f, 0.98f); valFull = 0.95f
            } else {
                hue = ((waveformSeed % 360) + 360).toFloat(); sat = 0.7f; valFull = 0.95f
            }
        }
        // vc114 BRIGHTER: saturation +10%
        // (capped), value steps raised so the
        // darkest outer layer is still dark but
        // not dull. Hue unchanged, so no unrelated
        // colours appear.
        val satWave = (sat * 1.20f).coerceAtMost(0.98f)
        val primary = hsv(hue, satWave, valFull)
        wavePrimaryColor = primary
        // vc142 GRADIENT + CURSOR colours: the art's runner-up hue is
        // the gradient end and the cursor ring; near-monochrome art
        // falls back to hue + 30 (analogous, same family as vc112).
        val secondHue = doms?.getOrNull(1)?.first ?: ((hue + 30f) % 360f)
        waveSecondaryColor = hsv(secondHue, satWave, valFull)
        cursorGlowPaint.color = primary
        cursorGlowPaint.alpha = 60
        cursorRingPaint.color = waveSecondaryColor
        cursorDotPaint.color = primary
        progressPillPaint.color = primary
        val valueSteps = floatArrayOf(0.80f, 0.88f, 0.95f, 1.0f)
        for (i in 0 until 4) {
            waveLayerPaints[i].color = hsv(hue, satWave, valFull * valueSteps[i])
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
        startCapture()
    }
    override fun onDetachedFromWindow() {
        wavePhaseAnimator?.cancel()
        wavePhaseAnimator = null
        waveHeightAnimator?.cancel()
        waveHeightAnimator = null
        stopCapture()
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
        // vc142 REAL BARS: when the output-mix capture is alive, the
        // played segment is drawn as rounded bars whose heights are
        // the smoothed real amplitudes, coloured by the album-art
        // gradient primary -> secondary. Smoothing is attack/release
        // per bar (rise fast 0.45/frame, fall slow 0.18/frame), so bass
        // hits snap up at once and quiet parts settle smoothly instead
        // of jerking. When capture is unavailable the sine wave below
        // runs instead (fallback, never a dead UI).
        val capture = if (wavePlaying) waveCapture.latestBars else null
        val realMode = capture != null && waveCapture.captureActive
        if (realMode && progressX > left + radius && waveVisible > 0.02f) {
            val displayCount = ((progressX - left) / dp(6)).toInt().coerceIn(16, 64)
            if (smoothedBars.size != displayCount) smoothedBars = FloatArray(displayCount)
            val maxHalf = kotlin.math.min(h * 0.5f, dp(10))
            val slotW = (progressX - left) / displayCount
            val barW = slotW * 0.62f
            val waveAlpha = (220f * waveVisible).toInt().coerceIn(0, 220)
            for (i in 0 until displayCount) {
                val src = capture[(i * capture.size / displayCount).coerceIn(0, capture.size - 1)]
                val target = (src * waveVisible).coerceIn(0f, 1f)
                val cur = smoothedBars[i]
                smoothedBars[i] = if (target > cur) cur + (target - cur) * 0.45f else cur + (target - cur) * 0.18f
                val bh = smoothedBars[i] * maxHalf * 2f
                if (bh < 0.5f) continue
                val bx = left + slotW * i + (slotW - barW) / 2f
                barPaint.color = lerpColor(wavePrimaryColor, waveSecondaryColor,
                    if (displayCount > 1) i.toFloat() / (displayCount - 1) else 0f)
                barPaint.alpha = waveAlpha
                barRect.set(bx, centerY - bh / 2f, bx + barW, centerY + bh / 2f)
                canvas.drawRoundRect(barRect, barW / 2f, barW / 2f, barPaint)
            }
        }
        // Wave: played segment only, FOUR layers,
        // largest + darkest first, smallest +
        // dominant last. vc114 ALTERNATING BEAT:
        // each layer's amplitude is multiplied by
        // 0.90 + 0.10 * sin(beat - i * pi/2),
        // so layer peaks follow in fixed order
        // 0 -> 1 -> 2 -> 3 -> 0 on a 1600ms loop
        // (peak moves every 400ms). The beat is
        // spread across layers, not pumped into
        // all layers at once, so the wave rolls
        // instead of thumping. Opaque (255),
        // 9Flip phase from the animator above.
        // vc142: sine is the fallback only - when
        // real capture is alive the bars above
        // are drawn instead.
        if (!realMode && progressX > left + radius && waveVisible > 0.02f) {
            val baseAmp = kotlin.math.min(h * 0.5f, dp(14))
            val amp = baseAmp * waveVisible
            val waveAlpha = (220f * waveVisible).toInt().coerceIn(0, 220)
            val ampSteps = floatArrayOf(1.0f, 0.85f, 0.70f, 0.55f)
            val offsets = floatArrayOf(0f, 0.8f, 1.6f, 2.4f)
            val beatCycle = (System.currentTimeMillis() % 1600L).toFloat() / 1600f * 6.2831855f
            for (i in 0 until 4) {
                val beatFactor = 0.90f + 0.10f * kotlin.math.sin(beatCycle - i * 1.5707963f)
                buildWavePath(wavePaths[i], left, progressX, centerY, pillBottom, amp * ampSteps[i] * beatFactor, wavePhase, offsets[i])
                val paint = waveLayerPaints[i]
                val saved = paint.alpha
                paint.alpha = waveAlpha
                canvas.drawPath(wavePaths[i], paint)
                paint.alpha = saved
            }
        }
        // vc142 TIME CURSOR: the playhead is wrapped in colour - a
        // primary-colour core, a secondary-colour ring around it, and
        // a soft primary glow - instead of the old white thumb. It
        // grows slightly while the finger drags it.
        if (progressX in left..right) {
            val seekScale = if (isSeeking) 1.25f else 1f
            canvas.drawCircle(progressX, centerY, dp(12) * seekScale, cursorGlowPaint)
            canvas.drawCircle(progressX, centerY, dp(9) * seekScale, cursorRingPaint)
            canvas.drawCircle(progressX, centerY, dp(6) * seekScale, cursorDotPaint)
        }
    }

    // vc113 SEEK SMOOTHNESS: drag updates the
    // UI locally ONLY (thumb follows locally
    // + elapsed follows via onSeekDragging,
    // no IPC in between), and the session is
    // seeked ONCE on finger-up (vc124: the old
    // comment claimed elapsed followed the
    // finger, but no code did it until now).
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
                onSeekDragging?.invoke(progress)
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
                onSeekDragging?.invoke(progress)
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
                onSeekDragging?.invoke(dragStartProgress)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
