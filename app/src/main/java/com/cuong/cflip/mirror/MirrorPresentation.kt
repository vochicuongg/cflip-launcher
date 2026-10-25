package com.cuong.cflip.mirror

import android.app.Presentation
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import kotlin.math.max
import kotlin.math.min

/**
 * Shows the mirrored main-screen feed on the cover display.
 * The MediaProjection virtual display renders into this TextureView's surface;
 * rotation is applied as a TextureView transform, so the user can turn the
 * (nearly square) cover sideways and watch landscape content upright.
 *
 * Buffer size follows the cover display size, swapped for 90/270 degrees so
 * the system mirrors into a landscape target instead of squashing portrait
 * content into a portrait box.
 */
class MirrorPresentation(context: Context, display: Display) : Presentation(context, display) {

    lateinit var textureView: TextureView
        private set

    /** Called once the drawing surface exists; the service points the virtual display at it. */
    var onSurfaceReady: ((Surface) -> Unit)? = null
    /** Called when the surface goes away; the service must release the virtual display. */
    var onSurfaceGone: (() -> Unit)? = null

    var rotationDeg: Int = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        textureView = TextureView(context).apply {
            setBackgroundColor(0xFF000000.toInt())
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    val (bw, bh) = bufferSize()
                    st.setDefaultBufferSize(bw, bh)
                    applyTransform()
                    onSurfaceReady?.invoke(Surface(st))
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyTransform()
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    onSurfaceGone?.invoke(); return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) { /* no-op */ }
            }
        }
        setContentView(textureView)
    }

    /** Width/height the projection should render at for the current rotation. */
    fun bufferSize(): Pair<Int, Int> {
        val w = display.mode.physicalWidth
        val h = display.mode.physicalHeight
        return if (rotationDeg % 180 != 0) h to w else w to h
    }

    fun applyRotation(deg: Int) {
        rotationDeg = ((deg % 360) + 360) % 360
        if (::textureView.isInitialized) {
            // Keep the surface buffer in step with the new orientation, or
            // the recreated virtual display renders into a stale-size buffer.
            textureView.surfaceTexture?.let { st ->
                val (bw, bh) = bufferSize()
                st.setDefaultBufferSize(bw, bh)
            }
            applyTransform()
        }
    }

    private fun applyTransform() {
        val tv = textureView
        if (tv.width == 0 || tv.height == 0) return
        val cx = tv.width / 2f
        val cy = tv.height / 2f
        val m = Matrix()
        m.postRotate(rotationDeg.toFloat(), cx, cy)
        if (rotationDeg % 180 != 0) {
            // A rotated rect overflows the near-square cover slightly; shrink to fit.
            val s = min(tv.width, tv.height).toFloat() / max(tv.width, tv.height).toFloat()
            m.postScale(s, s, cx, cy)
        }
        tv.setTransform(m)
    }
}
