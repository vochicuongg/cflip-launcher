package com.cuong.cflip.cover

import android.media.audiofx.Visualizer

/**
 * vc142: real audio amplitude from the device output mix.
 *
 * A MediaSession gives position / duration / album art only, never audio
 * samples - that is why CFlip's wave was synthetic until now. The way a
 * third-party app CAN read real amplitude is the framework Visualizer on
 * audio session 0 (the output mix: whatever is playing - Spotify, YouTube
 * Music, Samsung Music - mixed together). It needs MODIFY_AUDIO_SETTINGS,
 * a normal permission granted at install, no runtime prompt. It is NOT
 * per-app: if two sources play at once the mix contains both, and it
 * cannot tell which app a peak came from.
 *
 * Capture runs at the Visualizer's own rate (about 10-20 snapshots per
 * second); the view interpolates between snapshots at display refresh
 * rate (60/120Hz) with attack/release smoothing, so the motion stays
 * fluid - no UI can draw 1000 frames a second, and no real visualizer
 * tries to.
 *
 * Failure modes, all handled: init/enable throws (no engine, device
 * policy) => start() returns false and the view keeps its synthetic
 * sine wave. Exact-flat output (every sample byte exactly at the
 * silence centre) for 20 straight callbacks while playing => the device
 * is blocking mix capture, so this stops itself and the view falls
 * back to the synthetic wave until the next track (the panel retries
 * on track change via resetBlocked). Real quiet music still has
 * dither/noise, so it never reads as exact-flat - quiet passages
 * correctly render as a near-flat wave.
 *
 * Threading: capture callbacks arrive off the main thread, so they only
 * publish a fresh FloatArray through a volatile field and ask the view
 * to invalidate (postInvalidate is thread-safe). The view owns all
 * smoothing state on the main thread.
 */
class AudioWaveCapture {

    companion object {
        const val BAR_COUNT = 64
        private const val FLAT_LIMIT = 20
    }

    @Volatile var latestBars: FloatArray? = null
        private set

    @Volatile var captureActive: Boolean = false
        private set

    @Volatile var blocked: Boolean = false
        private set

    var onBars: (() -> Unit)? = null

    private var visualizer: Visualizer? = null
    private var flatCallbacks = 0

    fun resetBlocked() {
        blocked = false
        flatCallbacks = 0
    }

    fun start(): Boolean {
        if (blocked) return false
        if (captureActive) return true
        return runCatching {
            val range = Visualizer.getCaptureSizeRange()
            var size = 1024
            while (size > range[1]) size /= 2
            while (size < range[0]) size *= 2
            val viz = Visualizer(0)
            viz.captureSize = size
            // AS_PLAYED keeps true amplitude: loud passages tall, quiet
            // passages flat. NORMALIZED would boost quiet parts to full
            // height and lie about the music.
            viz.scalingMode = Visualizer.SCALING_MODE_AS_PLAYED
            viz.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    v: Visualizer?,
                    waveform: ByteArray?,
                    samplingRate: Int,
                ) {
                    if (waveform == null || waveform.isEmpty()) return
                    var allFlat = true
                    for (b in waveform) {
                        if ((b.toInt() and 0xFF) != 128) {
                            allFlat = false
                            break
                        }
                    }
                    if (allFlat) {
                        flatCallbacks++
                        if (flatCallbacks >= FLAT_LIMIT) {
                            blocked = true
                            stop()
                        }
                        return
                    }
                    flatCallbacks = 0
                    val bars = FloatArray(BAR_COUNT)
                    val slice = (waveform.size / BAR_COUNT).coerceAtLeast(1)
                    for (i in 0 until BAR_COUNT) {
                        val from = (i * slice).coerceIn(0, waveform.size - 1)
                        val to = ((i + 1) * slice).coerceIn(0, waveform.size)
                        var sum = 0.0
                        var n = 0
                        for (j in from until to) {
                            val amp = ((waveform[j].toInt() and 0xFF) - 128).toDouble() / 128.0
                            sum += amp * amp
                            n++
                        }
                        val rms = if (n > 0) kotlin.math.sqrt(sum / n).toFloat() else 0f
                        bars[i] = if (rms < 0.02f) 0f else rms.coerceIn(0f, 1f)
                    }
                    latestBars = bars
                    onBars?.invoke()
                }

                override fun onFftDataCapture(
                    v: Visualizer?,
                    fft: ByteArray?,
                    samplingRate: Int,
                ) {
                    // FFT (frequency spectrum) is not used: this wave is
                    // amplitude over time, which is the waveform.
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, false)
            viz.enabled = true
            visualizer = viz
            captureActive = true
            true
        }.getOrElse {
            runCatching { visualizer?.release() }
            visualizer = null
            captureActive = false
            false
        }
    }

    fun stop() {
        runCatching { visualizer?.enabled = false }
        runCatching { visualizer?.release() }
        visualizer = null
        captureActive = false
        latestBars = null
    }
}
