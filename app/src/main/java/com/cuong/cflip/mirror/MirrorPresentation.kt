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

    // vc58 9FLIP SOURCE SIZE: old vc45-vc46 Presentation used
    // COVER display size as buffer, so the virtual display was
    // cover-sized, not source-sized. 9Flip session sizes its
    // virtual display to MAIN (source) size and lets TextureView
    // scale down. Service sets these before show(); if unset,
    // bufferSize() falls back to cover size (old behavior).
    var sourceW: Int = 0
    var sourceH: Int = 0
    private var statusView: android.widget.TextView? = null
    fun setStatus(text: String) {
        val v = statusView ?: return
        v.post { v.text = text }
    }

    /** Called once the drawing surface exists; the service points the virtual display at it. */
    var onSurfaceReady: ((Surface) -> Unit)? = null
    /** Called when the surface goes away; the service must release the virtual display. */
    var onSurfaceGone: (() -> Unit)? = null

    var rotationDeg: Int = 0
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // vc45: the mirror used to be a bare TextureView with no
        // control at all - combined with HIDE_COVER, that was the
        // "mất luôn AssistiveTouch" trap. Now the presentation is
        // a FrameLayout: TextureView fills it, and a "Dừng phản
        // chiếu" button sits top-right as the always-visible exit.
        // Tapping it stops ScreenMirrorService, which restores the
        // AssistiveTouch bubble (see its cleanup()).
        val root = android.widget.FrameLayout(context)
        textureView = TextureView(context).apply {
            setBackgroundColor(0xFF000000.toInt())
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    val (bw, bh) = bufferSize()
                    st.setDefaultBufferSize(bw, bh)
                    applyTransform()
                    setStatus("Mirror: Presentation surface ready")
                    onSurfaceReady?.invoke(Surface(st))
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyTransform()
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    onSurfaceGone?.invoke(); return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) { /* no-op */ }
            }
        }
        root.addView(textureView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        val stopBtn = android.widget.Button(context).apply {
            text = "Dừng phản chiếu"
            textSize = 12f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 24f
                setColor(android.graphics.Color.argb(180, 20, 20, 20))
            }
            setPadding(24, 12, 24, 12)
            setOnClickListener {
                runCatching {
                    context.applicationContext.startService(android.content.Intent(
                        context.applicationContext,
                        com.cuong.cflip.service.ScreenMirrorService::class.java)
                        .setAction(com.cuong.cflip.service.ScreenMirrorService.ACTION_STOP))
                }
            }
        }
        val status = android.widget.TextView(context).apply {
            text = "Mirror: Presentation starting..."
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFF000000.toInt())
            textSize = 12f
            setPadding(16, 16, 16, 16)
        }
        statusView = status
        root.addView(status, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            setMargins(24, 24, 0, 0)
        })
        root.addView(stopBtn, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            setMargins(0, 24, 24, 0)
        })
        setContentView(root)
    }

    /** Width/height the projection should render at for the current rotation. */
    fun bufferSize(): Pair<Int, Int> {
        // vc58: source (main) size if service set it, else cover size.
        val w = if (sourceW > 0) sourceW else display.mode.physicalWidth
        val h = if (sourceH > 0) sourceH else display.mode.physicalHeight
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
