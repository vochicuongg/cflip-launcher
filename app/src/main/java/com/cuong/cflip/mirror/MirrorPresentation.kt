package com.cuong.cflip.mirror

import android.app.Presentation
import android.content.Context
import android.graphics.Matrix
import android.graphics.Path
import android.view.MotionEvent
import android.accessibilityservice.GestureDescription
import com.cuong.cflip.service.MyAccessibilityService
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
    // vc72: window orientation set by the service before show()
    // (same value as RotateHost / current cover rotation).
    // Applied to this window in onCreate so the cover does not
    // snap to 0° when dual drops the RotateHost overlay.
    // vc79: content rotation ADDED (service sets rotationDeg via
    // applyRotation to the diff desired-actual). Window flags
    // alone were ignored in dual (vc72-vc74 proof), so content
    // rotation is what actually corrects the feed; the window
    // flag is kept as-is, and if it ever takes effect after
    // start the feed may double-rotate (vc80 must make this
    // dynamic in that case).
    var windowOrientation: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var statusView: android.widget.TextView? = null
    fun setStatus(text: String) {
        val v = statusView ?: return
        v.post {
            v.text = text
            v.visibility = android.view.View.VISIBLE
            // vc89 NOTIFICATION FIX: the diagnostic status used to
            // sit over the feed forever (vc88 screenshot). Once the
            // feed is confirmed OK, hide it after 3s.
            if (text.contains("virtual display OK")) {
                v.postDelayed({ v.visibility = android.view.View.GONE }, 3000)
            }
        }
    }

    // vc89 MIRROR CONTROLS (9Flip h5, own code): zoom = crop-to-fill
    // toggle (i4.d: crop flag + pan 0.5, fit = min scale centred),
    // rotate follow/lock = whether cover-rotation changes re-apply
    // content rotation (service listener) or freeze it.
    // vc90: follow/lock buttons are GONE (user: rotate did not
    // work, replace with one tap = +90 degrees). The service
    // keeps mirrorContentDeg; this Presentation only renders it.
    var onControl: ((String) -> Unit)? = null
    // vc90 ZOOM LEVELS (user: zoom in/out was "delay rat nang"):
    // vc89 zoom was a toggle, so a second tap on Phogn to did
    // NOTHING (already cropped) and looked like a hang. Now
    // every tap steps: 0 = fit, 1 = crop-to-fill (9Flip i4.d),
    // 2 = crop x1.3, 3 = crop x1.6, clamped. Each tap changes
    // the transform immediately (no virtual-display recreate),
    // so the response is visible on that tap.
    private var zoomLevel: Int = 0
    private var panFraction: Float = 0.5f
    fun zoomIn() {
        if (zoomLevel < 3) { zoomLevel++; applyTransform() }
    }
    fun zoomOut() {
        if (zoomLevel > 0) { zoomLevel--; applyTransform() }
    }
    fun hideStatus() {
        statusView?.post { statusView?.visibility = android.view.View.GONE }
    }
    private data class FitParams(val fMin: Float, val sx: Float, val sy: Float, val dx: Float, val dy: Float, val scaledW: Float, val scaledH: Float)
    private fun fitParams(vw: Float, vh: Float): FitParams? {
        val sw = sourceW; val sh = sourceH
        if (vw <= 0f || vh <= 0f || sw <= 0 || sh <= 0) return null
        val f10 = vw / sw
        val f12 = vh / sh
        // 9Flip i4.a: fit = min, crop = f10 if source portrait else f12.
        // vc90 levels: 0 = fit, 1 = crop fill, 2/3 = crop x1.3/x1.6.
        val cropBase = if (sw <= sh) f10 else f12
        val fMin = when (zoomLevel) {
            0 -> min(f10, f12)
            1 -> cropBase
            2 -> cropBase * 1.3f
            else -> cropBase * 1.6f
        }
        val sx = fMin / f10
        val sy = fMin / f12
        val scaledW = vw * sx
        val scaledH = vh * sy
        val dx = if (scaledW > vw) -(scaledW - vw) * panFraction else (vw - scaledW) / 2f
        val dy = if (scaledH > vh) -(scaledH - vh) * panFraction else (vh - scaledH) / 2f
        return FitParams(fMin, sx, sy, dx, dy, scaledW, scaledH)
    }

    /** Called once the drawing surface exists; the service points the virtual display at it. */
    var onSurfaceReady: ((Surface) -> Unit)? = null
    /** Called when the surface goes away; the service must release the virtual display. */
    var onSurfaceGone: (() -> Unit)? = null

    var rotationDeg: Int = 0
        private set

    // vc88 TOUCH: live streaming, 9Flip-style (own code, not a copy).
    // 9Flip (decompiled f4/g5, 27/10) does NOT wait for finger-up:
    // it maps each cover point through the inverse fit matrix
    // (g5 case 0: rectF.contains guard, inverse matrix, then
    // clamp to source size), then streams stroke segments through
    // the accessibility service with willContinue=true, chaining
    // each next segment on the previous stroke's completion
    // callback (f4 queue, single in-flight gesture, chunks of
    // ~120-220ms), and only the final segment has
    // willContinue=false. Tap = 16ms first segment (f4.d),
    // long-press = 80ms + longPressTimeout segment, also
    // willContinue=true (k4), pinch = two 180ms strokes from a
    // centre point outward/inward (y4). CFlip vc63 sent ONE
    // stroke only at UP time, so scroll/drag only reacted on
    // lift and a held finger never produced a live long-press.
    // vc88 streams instead: DOWN sends the first segment
    // immediately (willContinue=true, so main sees a held down
    // and long-press fires naturally while the user still holds),
    // each MOVE queues the latest point (coalesced, like 9Flip
    // chunking), each segment completion sends the next queued
    // point, and UP sends the final segment (willContinue=false).
    // Pinch: second finger starts pinch tracking and ends the
    // single-finger stream; the two-finger path is dispatched as
    // one GestureDescription with two strokes when the pinch
    // lifts (after the single stream finishes, so the service
    // never has two gestures in flight at once).
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchLastX = 0f
    private var touchLastY = 0f
    private var touchStartTime = 0L
    private var touchActive = false
    private var streamStroke: GestureDescription.StrokeDescription? = null
    private var streamLastX = 0f
    private var streamLastY = 0f
    private var streamInFlight = false
    private var streamPendingX: Float? = null
    private var streamPendingY: Float? = null
    private var streamPendingFinal = false
    private var streamGeneration = 0
    private var pinchActive = false
    private var pinchStart1X = 0f
    private var pinchStart1Y = 0f
    private var pinchStart2X = 0f
    private var pinchStart2Y = 0f
    private var pinchLast1X = 0f
    private var pinchLast1Y = 0f
    private var pinchLast2X = 0f
    private var pinchLast2Y = 0f
    private var pinchStartTime = 0L
    private var pinchPending: GestureDescription? = null

    // Map a cover view point to main coords via the inverse of
    // the fit transform (view = main*fMin + offset). Returns null
    // for touches in the black bars, which have no main point.
    // vc79 TOUCH INVERSE: applyTransform rotates content
    // clockwise by rotationDeg around the view center, so a tap
    // must be inverse-rotated back to unrotated fit space first.
    // 0° is unchanged (exact old behavior); other angles fall
    // back to unrotated mapping (should not happen: service only
    // sets 0/90/180/270).
    private fun mirrorViewToMain(viewX: Float, viewY: Float): Pair<Float, Float>? {
        if (!::textureView.isInitialized) return null
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        val sw = sourceW; val sh = sourceH
        val fp = fitParams(vw, vh) ?: return null
        val fMin = fp.fMin
        val dx = fp.dx
        val dy = fp.dy
        val scaledW = fp.scaledW
        val scaledH = fp.scaledH
        val cx = vw / 2f
        val cy = vh / 2f
        val (ux, uy) = when (rotationDeg) {
            90 -> Pair(cx + (viewY - cy), cy - (viewX - cx))
            180 -> Pair(2f * cx - viewX, 2f * cy - viewY)
            270 -> Pair(cx - (viewY - cy), cy + (viewX - cx))
            else -> Pair(viewX, viewY)
        }
        if (ux < dx || ux > dx + scaledW || uy < dy || uy > dy + scaledH) return null
        val mainX = ((ux - dx) / fMin).coerceIn(0f, (sw - 1).toFloat())
        val mainY = ((uy - dy) / fMin).coerceIn(0f, (sh - 1).toFloat())
        return Pair(mainX, mainY)
    }

    private fun dispatchStreamSegment(toX: Float, toY: Float, willContinue: Boolean) {
        val acc = MyAccessibilityService.instance
        if (acc == null) {
            // vc90 TOUCH DIAGNOSTIC: never fail silently again.
            // If the service is not bound, the user sees WHY on
            // the feed itself instead of "cham khong an" with no
            // reason. The vc90 config fix (canPerformGestures)
            // needs a re-bind: toggle Accessibility off/on once.
            setStatus("Cảm ứng cần bật Accessibility cho CFlip (Cài đặt > Trợ năng, tắt/bật lại 1 lần)")
            return
        }
        val path = Path()
        path.moveTo(streamLastX, streamLastY)
        if (toX != streamLastX || toY != streamLastY) path.lineTo(toX, toY)
        val stroke = if (streamStroke == null) {
            GestureDescription.StrokeDescription(path, 0L, 50L, willContinue)
        } else {
            streamStroke!!.continueStroke(path, 0L, 50L, willContinue)
        }
        streamStroke = stroke
        streamLastX = toX
        streamLastY = toY
        streamInFlight = true
        val gen = streamGeneration
        val isFinalSegment = !willContinue
        val gesture = GestureDescription.Builder().setDisplayId(0).addStroke(stroke).build()
        hideStatus()
        val ok = acc.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (gen != streamGeneration) return
                streamInFlight = false
                // vc89 TOUCH BUG (vc88 test: touches did nothing):
                // the final segment's completion must NOT call
                // onStreamSegmentDone, or the final point queued
                // by UP would be discarded and the finger would
                // stay down on main forever (tap never lands).
                if (isFinalSegment) {
                    streamStroke = null
                    val pinch = pinchPending
                    pinchPending = null
                    if (pinch != null) {
                        val a2 = MyAccessibilityService.instance
                        if (a2 != null) a2.dispatchGesture(pinch, null, null)
                    }
                    return
                }
                onStreamSegmentDone()
            }
            override fun onCancelled(g: GestureDescription?) {
                if (gen != streamGeneration) return
                streamInFlight = false
                streamStroke = null
                touchActive = false
                pinchActive = false
                streamPendingX = null
                streamPendingY = null
                streamPendingFinal = false
                pinchPending = null
            }
        }, null)
        if (!ok) {
            android.util.Log.w("CFlipMirror", "dispatchGesture rejected, touch stream dropped")
            streamInFlight = false
            streamStroke = null
            touchActive = false
            streamPendingX = null
            streamPendingY = null
            streamPendingFinal = false
        }
    }

    private fun onStreamSegmentDone() {
        if (streamPendingFinal) {
            val px = streamPendingX
            val py = streamPendingY
            streamPendingFinal = false
            streamPendingX = null
            streamPendingY = null
            if (px != null && py != null) {
                dispatchStreamSegment(px, py, false)
            } else {
                streamStroke = null
            }
            return
        }
        val px = streamPendingX
        val py = streamPendingY
        if (px != null && py != null) {
            streamPendingX = null
            streamPendingY = null
            dispatchStreamSegment(px, py, true)
        }
    }

    private fun queueStreamSegment(toX: Float, toY: Float, final: Boolean) {
        if (final) streamPendingFinal = true
        streamPendingX = toX
        streamPendingY = toY
        if (!streamInFlight) {
            val px = streamPendingX
            val py = streamPendingY
            if (px == null || py == null) return
            streamPendingX = null
            streamPendingY = null
            val isFinal = streamPendingFinal
            if (isFinal) streamPendingFinal = false
            dispatchStreamSegment(px, py, !isFinal)
        }
    }

    private fun buildPinchGesture(durationMs: Long): GestureDescription {
        val path1 = Path()
        path1.moveTo(pinchStart1X, pinchStart1Y)
        if (pinchLast1X != pinchStart1X || pinchLast1Y != pinchStart1Y) path1.lineTo(pinchLast1X, pinchLast1Y)
        val path2 = Path()
        path2.moveTo(pinchStart2X, pinchStart2Y)
        if (pinchLast2X != pinchStart2X || pinchLast2Y != pinchStart2Y) path2.lineTo(pinchLast2X, pinchLast2Y)
        return GestureDescription.Builder().setDisplayId(0)
            .addStroke(GestureDescription.StrokeDescription(path1, 0L, durationMs))
            .addStroke(GestureDescription.StrokeDescription(path2, 0L, durationMs))
            .build()
    }

    private fun endPinch(durationMs: Long) {
        val gesture = buildPinchGesture(durationMs)
        if (streamInFlight || streamStroke != null) {
            pinchPending = gesture
        } else {
            val acc = MyAccessibilityService.instance
            if (acc != null) acc.dispatchGesture(gesture, null, null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // vc85 LOCK FIX (PowerFlip lesson, own code): vc84 test showed
        // the dialog fine, then the screen LOCKED immediately after
        // agree — the feed window never got to stay visible. A
        // Presentation on the cover must be allowed to show over the
        // keyguard and to turn the cover on, exactly like PowerFlip's
        // mirror Activity (setShowWhenLocked + setTurnScreenOn +
        // KEEP_SCREEN_ON). Without these flags the cover falls back
        // to its lock screen the moment the system dialog dismisses,
        // and the feed sits behind lock. FLAG_SHOW_WHEN_LOCKED /
        // FLAG_TURN_SCREEN_ON are deprecated for Activities (use the
        // Activity setters there) but remain the only route for a
        // Presentation window, which has no such setters.
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        // vc72: carry the captured orientation into this window.
        if (windowOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            window?.attributes?.let { attrs ->
                attrs.screenOrientation = windowOrientation
                window?.attributes = attrs
            }
        }
        // vc45: the mirror used to be a bare TextureView with no
        // control at all - combined with HIDE_COVER, that was the
        // "mất luôn AssistiveTouch" trap. Now the presentation is
        // a FrameLayout: TextureView fills it, and a "Dừng phản
        // chiếu" button sits top-right as the always-visible exit.
        // Tapping it stops ScreenMirrorService, which restores the
        // AssistiveTouch bubble (see its cleanup()).
        val root = android.widget.FrameLayout(context).apply { setBackgroundColor(0xFF000000.toInt()) }
        // vc87 CRASH FIX (logcat 03:29, proven): TextureView does NOT
        // support a background drawable — setBackgroundColor() throws
        // UnsupportedOperationException on this device, which is why
        // pres.show() always failed ("Presentation not shown") and
        // the service then crashed in the overlay fallback too.
        // The root FrameLayout above is already black, so the
        // TextureView needs no background of its own.
        textureView = TextureView(context).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    val (bw, bh) = bufferSize()
                    st.setDefaultBufferSize(bw, bh)
                    applyTransform()
                    // vc62: at surface-available time the view may
                    // not be laid out yet (width/height 0, so
                    // applyTransform above returns early). Re-apply
                    // after layout, and onSizeChanged also calls it.
                    textureView.post { applyTransform() }
                    setStatus("Mirror: Presentation surface ready")
                    onSurfaceReady?.invoke(Surface(st))
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyTransform()
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    onSurfaceGone?.invoke(); return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) { /* no-op */ }
            }
            // vc63 TOUCH: forward cover touches to main. Stop button
            // and status views sit above this TextureView, so taps
            // on them still go to the button, not to main.
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        val main = mirrorViewToMain(event.x, event.y) ?: return@setOnTouchListener false
                        touchStartX = main.first; touchStartY = main.second
                        touchLastX = main.first; touchLastY = main.second
                        touchStartTime = event.eventTime
                        touchActive = true
                        pinchActive = false
                        pinchPending = null
                        streamStroke = null
                        streamLastX = main.first; streamLastY = main.second
                        streamInFlight = false
                        streamPendingX = null; streamPendingY = null
                        streamPendingFinal = false
                        streamGeneration++
                        queueStreamSegment(main.first, main.second, false)
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (!touchActive || pinchActive) return@setOnTouchListener true
                        if (event.pointerCount < 2) return@setOnTouchListener true
                        val p0 = mirrorViewToMain(event.getX(0), event.getY(0))
                        val p1 = mirrorViewToMain(event.getX(1), event.getY(1))
                        if (p0 == null || p1 == null) return@setOnTouchListener true
                        pinchActive = true
                        pinchStart1X = p0.first; pinchStart1Y = p0.second
                        pinchStart2X = p1.first; pinchStart2Y = p1.second
                        pinchLast1X = p0.first; pinchLast1Y = p0.second
                        pinchLast2X = p1.first; pinchLast2Y = p1.second
                        pinchStartTime = event.eventTime
                        queueStreamSegment(touchLastX, touchLastY, true)
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!touchActive) return@setOnTouchListener false
                        if (pinchActive) {
                            if (event.pointerCount >= 2) {
                                val p0 = mirrorViewToMain(event.getX(0), event.getY(0))
                                val p1 = mirrorViewToMain(event.getX(1), event.getY(1))
                                if (p0 != null) { pinchLast1X = p0.first; pinchLast1Y = p0.second }
                                if (p1 != null) { pinchLast2X = p1.first; pinchLast2Y = p1.second }
                            }
                            true
                        } else {
                            val main = mirrorViewToMain(event.x, event.y)
                            if (main != null) {
                                touchLastX = main.first; touchLastY = main.second
                                queueStreamSegment(main.first, main.second, false)
                            }
                            true
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (!pinchActive) return@setOnTouchListener true
                        pinchActive = false
                        touchActive = false
                        val durationMs = (event.eventTime - pinchStartTime).coerceIn(50L, 800L)
                        endPinch(durationMs)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!touchActive) return@setOnTouchListener false
                        if (pinchActive) {
                            pinchActive = false
                            touchActive = false
                            val durationMs = (event.eventTime - pinchStartTime).coerceIn(50L, 800L)
                            endPinch(durationMs)
                        } else {
                            touchActive = false
                            val main = mirrorViewToMain(event.x, event.y)
                            if (main != null) { touchLastX = main.first; touchLastY = main.second }
                            queueStreamSegment(touchLastX, touchLastY, true)
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (touchActive && !pinchActive) queueStreamSegment(touchLastX, touchLastY, true)
                        touchActive = false
                        pinchActive = false
                        true
                    }
                    else -> false
                }
            }
        }
        root.addView(textureView, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT))
        // vc90 ICON COLUMN (user request): all controls as ONE
        // vertical column of icons, placed at the RIGHT EDGE,
        // OUTSIDE the mirrored feed in fit mode (the feed is a
        // narrow centred strip, so the right black bar is free
        // space - the column does not cover content there), with
        // the STOP icon LAST. In crop-zoom mode the feed fills
        // the width, so the small column sits on the feed edge
        // instead - unavoidable, but still at the edge, not over
        // the centre. Icons are large symbols on dark circles
        // (no new drawable resources, code-only). Taps on an
        // icon are consumed by that icon and never reach main.
        // The old top-right stop button and bottom bar are gone.
        fun iconBtn(symbol: String, action: String): android.widget.TextView {
            return android.widget.TextView(context).apply {
                text = symbol
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.argb(180, 20, 20, 20))
                }
                setPadding(20, 20, 20, 20)
                isClickable = true
                setOnClickListener { onControl?.invoke(action) }
            }
        }
        val column = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        val icons = listOf(
            iconBtn("\u21bb", "rotate"),
            iconBtn("+", "zoom_in"),
            iconBtn("\u2212", "zoom_out"),
            android.widget.TextView(context).apply {
                text = "\u25a0"
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
                gravity = android.view.Gravity.CENTER
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.argb(200, 180, 30, 30))
                }
                setPadding(20, 20, 20, 20)
                isClickable = true
                setOnClickListener {
                    runCatching {
                        context.applicationContext.startService(android.content.Intent(
                            context.applicationContext,
                            com.cuong.cflip.service.ScreenMirrorService::class.java)
                            .setAction(com.cuong.cflip.service.ScreenMirrorService.ACTION_STOP))
                    }
                }
            }
        )
        for (v in icons) {
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 10, 0, 10) }
            column.addView(v, lp)
        }
        root.addView(column, android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setMargins(0, 0, 12, 0)
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

    // vc62 DISPLAY LAYER (9Flip i4.a fit mode, own code):
    // Old vc45-vc61 Presentation only rotated (identity at
    // 0 deg), so TextureView stretched the tall main buffer
    // (1080x2640) into the near-square cover (720x748) -
    // feed looked vertically squished, the "display layer
    // error" in the vc61 report. 9Flip i4.a fit mode does:
    // scale = min(viewW/sourceW, viewH/sourceH), then center
    // with (viewW-scaledW)/2, (viewH-scaledH)/2, black bars
    // from the black TextureView background. Same math here,
    // plus the rotation on top only if rotationDeg != 0
    // (vc79: service now sets it via applyRotation to the
    // diff desired-actual, so it is no longer identity when
    // dual snaps the cover).
    private fun applyTransform() {
        val tv = textureView
        if (tv.width == 0 || tv.height == 0) return
        val sw = sourceW; val sh = sourceH
        if (sw <= 0 || sh <= 0) {
            // No source size set: fall back to old rotation-only
            // transform so the view still shows something.
            val cx = tv.width / 2f; val cy = tv.height / 2f
            val m0 = Matrix()
            m0.postRotate(rotationDeg.toFloat(), cx, cy)
            tv.setTransform(m0)
            return
        }
        // vc63 FIT FIX: vc62 used buffer-space scale (0.283)
        // directly as the TextureView transform, but
        // TextureView.setTransform is applied AFTER the default
        // fill stretch (viewW/sourceW, viewH/sourceH). Total
        // scale became 0.667*0.283=0.189 by 0.283*0.283=0.080,
        // so the feed came out 204x211 at the top - exactly the
        // vc62 "very small at top" report. 9Flip i4.a uses
        // view-space scale: f10=viewW/sourceW, f12=viewH/sourceH,
        // fMin=min(f10,f12), sx=fMin/f10, sy=fMin/f12, then
        // scaledW=viewW*sx, scaledH=viewH*sy, centered. Total
        // scale is then f10*sx=fMin by f12*sy=fMin, uniform fit.
        val fp = fitParams(tv.width.toFloat(), tv.height.toFloat()) ?: return
        val sx = fp.sx
        val sy = fp.sy
        val dx = fp.dx
        val dy = fp.dy
        val m = Matrix()
        m.setScale(sx, sy)
        m.postTranslate(dx, dy)
        if (rotationDeg != 0) {
            m.postRotate(rotationDeg.toFloat(), tv.width / 2f, tv.height / 2f)
        }
        tv.setTransform(m)
    }
}
