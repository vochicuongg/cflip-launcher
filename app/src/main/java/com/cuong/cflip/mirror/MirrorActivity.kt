package com.cuong.cflip.mirror

import androidx.activity.ComponentActivity
import android.graphics.Matrix
import android.graphics.Path
import android.view.MotionEvent
import android.accessibilityservice.GestureDescription
import com.cuong.cflip.service.MyAccessibilityService
import android.graphics.SurfaceTexture
import android.os.Bundle
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
class MirrorActivity : ComponentActivity() {

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
    // vc184 9FLIP SELF-HEAL: fired from onStop() so the
    // service can re-show an unintentionally stopped
    // Presentation instead of only cleaning up (9Flip p4
    // onStop -> l4.d(), own code, same structure).
    var onStopped: (() -> Unit)? = null

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
    // vc185: true while a touch that started in a
    // black bar has not yet entered the feed strip.
    private var touchPendingOutside = false
    private var streamStroke: GestureDescription.StrokeDescription? = null
    private var streamLastX = 0f
    private var streamLastY = 0f
    private var streamInFlight = false
    private var streamPendingX: Float? = null
    private var streamPendingY: Float? = null
    private var streamPendingFinal = false
    // vc98 CHUNKED LIVE TOUCH - SUPERSEDED BY vc103
    // (kept for history; vc103 latest-only below)
    // vc98 was based on 9Flip f4.c + u2, decompiled
    // 27/10): vc88-vc97 dispatched ONE segment per MOVE
    // point and waited for its callback before the next
    // point. Each dispatch has system + callback overhead,
    // so between segments main saw the finger STOP, then
    // JUMP to the next point - scroll momentum broke and
    // the feed felt "delay, khong theo tuong tac" even
    // though each segment was only 16ms. 9Flip batches
    // MOVE points into ONE Path (c(): up to 8 points,
    // duration clamp(n*22,120,220)ms) so the system plays
    // a continuous path with no gaps, and only the
    // completion of that chunk triggers the next chunk.
    // vc98 does the same for MOVE only: DOWN stays a
    // single 16ms segment (tap must land instantly),
    // UP stays a single final segment, MOVE points are
    // accumulated and dispatched as chunks of up to 8.
    // A chunk of 1 point keeps 16ms/1ms (no 120ms stall
    // for a slow drag), a chunk of 8 plays at ~22ms per
    // point, matching finger speed. Points <1px from the
    // last queued point are dropped as noise (9Flip d()
    // 1px threshold), they never become segments.
    private val moveChunkPoints = ArrayDeque<Pair<Float, Float>>()
    private val gestureHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // vc101 TOUCH LOOP (PowerFlip GestureDispatcher
    // f4.d, decompiled 27/10): PowerFlip does NOT
    // chain chunks on dispatch callbacks. It runs a
    // Handler loop every 25ms (postDelayed 25L) that
    // drains the accumulated point list into one
    // GestureDescription per tick, duration = clamp(
    // time since last tick, 15, 100)ms, callback null.
    // CFlip vc88-vc100 chained on callbacks, so if a
    // callback was lost (mirror exits, dispatch
    // rejected, service restart) the touch stalled
    // forever waiting for a callback that never came
    // - "khong theo tuong tac" and "vang khoi phan
    // chieu nhung van hoat dong". vc101 keeps the
    // callback chain as primary, and adds PowerFlip's
    // loop as a watchdog: every 25ms while touch is
    // active, if points are queued and no segment is
    // in flight, dispatch the next chunk. It cannot
    // double-dispatch (main thread, inFlight check,
    // points removed before dispatch). If touch is
    // paused >1s with no points, it sends one hold
    // segment (moveTo last, lineTo last, 25ms,
    // willContinue=true) so the system does not time
    // out the open stroke mid-drag, then waits again.
    private var touchLoopRunning = false
    private var touchLastDispatchMs = 0L
    private var touchWatchdogNudgeBack = false
    // vc102 BUILD FIX: same vc92 pattern - a
    // Runnable that posts itself inside its own
    // initializer makes Kotlin type-checking
    // recursive (error at line 230 in vc101).
    // Fix, as in vc93: declare the type explicitly.
    private val touchLoopRunnable: Runnable = Runnable {
        touchLoopRunning = false
        if (!touchActive) return@Runnable
        if (moveChunkPoints.isNotEmpty() && !streamInFlight) {
            dispatchNextChunk()
        } else if (touchLiveStarted && moveChunkPoints.isEmpty() && !streamPendingFinal) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - touchLastDispatchMs > 1000L && streamStroke != null && !streamInFlight) {
                // vc180: vc178 guard drops an exact-duplicate hold segment,
                // so the old call here was a no-op and an open stroke could
                // time out during a stationary hold. Send a 1px nudge instead,
                // alternating direction so net drift stays <=1px.
                val nudge = if (!touchWatchdogNudgeBack) {
                    touchWatchdogNudgeBack = true
                    nudgedFinalPoint()
                } else {
                    touchWatchdogNudgeBack = false
                    if (streamLastY > 0) Pair(streamLastX, streamLastY - 1f)
                    else if (streamLastY < sourceH - 1) Pair(streamLastX, streamLastY + 1f)
                    else if (streamLastX > 0) Pair(streamLastX - 1f, streamLastY)
                    else Pair(streamLastX + 1f, streamLastY)
                }
                android.util.Log.i("CFlipMirror", "PRES WATCHDOG nudge to " + nudge)
                dispatchStreamSegment(nudge.first, nudge.second, true)
            }
        }
        if (touchActive) {
            touchLoopRunning = true
            gestureHandler.postDelayed(touchLoopRunnable, 25L)
        }
    }
    private fun startTouchLoop() {
        if (!touchLoopRunning) {
            touchLoopRunning = true
            gestureHandler.postDelayed(touchLoopRunnable, 25L)
        }
    }
    private fun stopTouchLoop() {
        touchLoopRunning = false
        gestureHandler.removeCallbacks(touchLoopRunnable)
    }
    // vc99 TOUCH (9Flip u2, decompiled with --show-bad-code
    // 27/10, now readable): u2 does NOT only live-stream.
    // It keeps a full point list (cap 96, remove index 1
    // when full so index 0 - the DOWN point - is never
    // lost), total movement in VIEW coordinates, and a
    // slop test. On UP, if no live stream is active, it
    // does NOT drop the gesture: tap (total <= slop) is
    // one 42ms gesture (f4.v: moveTo only, 42ms), drag
    // (list >= 2) is ONE Path gesture from the whole
    // list, duration clamp(totalDistance/2.4, 120, 650)ms
    // (f4.u + u2 UP branch: velocity ~2.4 px/ms). CFlip
    // vc88-vc98 had NO such fallback: if the live stream
    // was never started (service null at DOWN, dispatch
    // rejected, stream cancelled when mirror exits) the
    // whole touch was lost, feeling like "khong theo
    // tuong tac". vc99 keeps the vc98 live chunk stream
    // as the primary path, and adds u2's UP fallback
    // only when live is NOT active, so it never doubles
    // a drag that already went live.
    private val touchPointList = ArrayList<Pair<Float, Float>>()
    private var touchDownViewX = 0f
    private var touchDownViewY = 0f
    private var touchLastViewX = 0f
    private var touchLastViewY = 0f
    private var touchTotalViewMove = 0f
    private var touchLiveStarted = false
    private var touchLastUpMs = 0L
    // vc104 9FLIP-EXACT TOUCH (u2/f4, user chose 9Flip
    // 27/10, decompiled again for this build):
    // u2 fields, own names: slop = scaledTouchSlop
    // (view px), doubleTapTimeout, longPressTimeout
    // from ViewConfiguration; k = touch valid (mapped
    // point != null), l = multi-touch flag,
    // p = long-press flag, s = live running,
    // q = double-tap candidate. DOWN does NOT
    // dispatch. MOVE starts live ONLY after total
    // view movement > slop, with the FIRST list
    // point (f4.b). UP: live -> finalize (f4.n);
    // not live + long-press -> no tap, lastUp=0;
    // not live + double-tap candidate + total >
    // slop + list>=2 -> k4 replay (hold stroke
    // timeout+80 willContinue, then 8-point chunks
    // f4.c); not live + total <= slop -> tap 42ms
    // moveTo-only (f4.v); not live + list>=2 ->
    // one Path, duration clamp(dist/2.4,120,650)
    // (f4.u). Long-press runnable (a4.a case 8):
    // if valid, not multi, total <= slop, first
    // point exists -> set long-press flag, haptic
    // (omitted in CFlip, no vibrator path here),
    // and if not live dispatch t() (moveTo first,
    // duration timeout+80, willContinue=false).
    // Deviation, documented: u2 UP with long-press
    // flag alone drops a drag after long-press.
    // vc104 keeps u2 for that case (no drag
    // replay after a timeout long-press), because
    // the user asked for 9Flip exactly, and a
    // replay after lift would not be a live
    // long-press drag anyway. Long-press drag
    // that works in 9Flip is the double-tap drag
    // replay (k4), which vc104 implements below.
    // vc180: press-drag now works live via the open hold stroke in
    // touchLongPressRunnable; the drop case remains only when that hold
    // dispatch fails and falls back to the closed long-press.
    private var touchSlopView = 0f
    private var touchDoubleTapCandidate = false
    private var touchLongPressFlag = false
    private var touchMultiFlag = false
    private var touchReplayMode = false
    // vc104 GLOBAL PREPARED QUEUE (9Flip f4.m/i):
    // prepared gestures (tap, drag, long-press,
    // k4 hold is NOT queued, it is multi-segment)
    // must never overlap a live stream or another
    // prepared gesture. Queue max 12, drop oldest
    // if full, like f4.m dropping a non-protected
    // queued gesture. Live segments are NOT queued
    // here - they use the pending-point mechanism
    // (streamPendingX/Y + dispatchNextChunk).
    private val touchPreparedQueue = ArrayDeque<GestureDescription>()
    private var touchPreparedInFlight = false
    private val touchLongPressRunnable: Runnable = Runnable {
        if (!touchActive || touchMultiFlag || touchLiveStarted) return@Runnable
        if (touchTotalViewMove > touchSlopView) return@Runnable
        val first = touchPointList.firstOrNull() ?: return@Runnable
        touchLongPressFlag = true
        // vc180 PRESS-DRAG: hold stroke stays OPEN (willContinue=true) through
        // the stream, so MOVE after the hold continues the same stroke and UP
        // drops the icon. Old vc104 code sent willContinue=false, so the stroke
        // closed at timeout+80 and MOVE was blocked by touchLongPressFlag, making
        // press-drag impossible. Fallback below keeps the old closed long-press
        // only when a live/prepared stroke is already busy or dispatch fails.
        val timeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        val acc = MyAccessibilityService.instance
        if (acc == null || streamStroke != null || streamInFlight || touchPreparedInFlight) {
            val path = Path(); path.moveTo(first.first, first.second)
            val gesture = GestureDescription.Builder().setDisplayId(0)
                .addStroke(GestureDescription.StrokeDescription(path, 0L, timeout + 80L))
                .build()
            dispatchPreparedGesture(gesture)
            return@Runnable
        }
        val path = Path(); path.moveTo(first.first, first.second)
        val holdStroke = GestureDescription.StrokeDescription(path, 0L, timeout + 80L, true)
        streamStroke = holdStroke
        streamLastX = first.first; streamLastY = first.second
        touchLiveStarted = true
        startTouchLoop()
        touchLastDispatchMs = android.os.SystemClock.uptimeMillis()
        streamInFlight = true
        val gen = streamGeneration
        val gesture = GestureDescription.Builder().setDisplayId(0).addStroke(holdStroke).build()
        val ok = acc.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                android.util.Log.i("CFlipMirror", "PRES LP_HOLD_CB completed")
                if (gen != streamGeneration) return
                streamInFlight = false
                dispatchNextChunk()
            }
            override fun onCancelled(g: GestureDescription?) {
                android.util.Log.i("CFlipMirror", "PRES LP_HOLD_CB cancelled")
                if (gen != streamGeneration) return
                streamInFlight = false
                streamStroke = null
                touchActive = false
                pinchActive = false
                streamPendingX = null
                streamPendingY = null
                streamPendingFinal = false
                moveChunkPoints.clear()
                touchReplayMode = false
                pinchPending = null
            }
        }, gestureHandler)
        android.util.Log.i("CFlipMirror", "PRES LP_HOLD dispatched ok=" + ok)
        if (!ok) {
            streamInFlight = false
            streamStroke = null
            touchLiveStarted = false
            val path2 = Path(); path2.moveTo(first.first, first.second)
            val gesture2 = GestureDescription.Builder().setDisplayId(0)
                .addStroke(GestureDescription.StrokeDescription(path2, 0L, timeout + 80L))
                .build()
            dispatchPreparedGesture(gesture2)
        }
    }
    private fun dispatchPreparedGesture(gesture: GestureDescription) {
        val acc = MyAccessibilityService.instance ?: return
        // Wait for a live stream to finish first:
        // a prepared gesture overlapping an open
        // live stroke would be rejected or would
        // appear as a second finger on main.
        if (streamStroke != null || streamInFlight || touchPreparedInFlight) {
            android.util.Log.i("CFlipMirror", "PRES PREP queued streamStroke=" + (streamStroke != null) + " inFlight=" + streamInFlight + " prepInFlight=" + touchPreparedInFlight)
            if (touchPreparedQueue.size >= 12) touchPreparedQueue.removeFirst()
            touchPreparedQueue.addLast(gesture)
            return
        }
        touchPreparedInFlight = true
        val ok = acc.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                android.util.Log.i("CFlipMirror", "PRES PREP_CB completed")
                touchPreparedInFlight = false
                dispatchNextPrepared()
            }
            override fun onCancelled(g: GestureDescription?) {
                android.util.Log.i("CFlipMirror", "PRES PREP_CB cancelled")
                touchPreparedInFlight = false
                dispatchNextPrepared()
            }
        }, gestureHandler)
        android.util.Log.i("CFlipMirror", "PRES PREP dispatched ok=" + ok)
        if (!ok) {
            touchPreparedInFlight = false
            dispatchNextPrepared()
        }
    }
    private fun dispatchNextPrepared() {
        if (touchPreparedInFlight) return
        if (streamStroke != null || streamInFlight) return
        val next = touchPreparedQueue.removeFirstOrNull() ?: return
        dispatchPreparedGesture(next)
    }
    // vc104 k4 REPLAY (9Flip k4.a, for double-tap
    // drag on UP): hold stroke first, then chunks.
    // Hold: moveTo first, duration timeout+80,
    // willContinue=true. Chunks: remaining list
    // points, 8 per chunk, duration n*22 (f4.c),
    // last chunk willContinue=false. Uses
    // streamStroke so chunks continue the hold.
    private fun dispatchK4Replay() {
        val acc = MyAccessibilityService.instance ?: return
        if (touchPointList.size < 2) return
        val first = touchPointList.first()
        val remaining = touchPointList.drop(1)
        val timeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        val holdPath = Path(); holdPath.moveTo(first.first, first.second)
        val holdStroke = GestureDescription.StrokeDescription(holdPath, 0L, timeout + 80L, true)
        streamStroke = holdStroke
        streamLastX = first.first; streamLastY = first.second
        streamInFlight = true
        touchReplayMode = true
        streamPendingFinal = true
        moveChunkPoints.clear()
        remaining.forEach { moveChunkPoints.addLast(it) }
        val gesture = GestureDescription.Builder().setDisplayId(0).addStroke(holdStroke).build()
        val gen = streamGeneration
        val ok = acc.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                if (gen != streamGeneration) return
                streamInFlight = false
                dispatchNextChunk()
            }
            override fun onCancelled(g: GestureDescription?) {
                android.util.Log.i("CFlipMirror", "PRES K4_CB cancelled")
                if (gen != streamGeneration) return
                streamInFlight = false
                streamStroke = null
                touchReplayMode = false
                moveChunkPoints.clear()
            }
        }, gestureHandler)
        if (!ok) {
            android.util.Log.i("CFlipMirror", "PRES K4 dispatched ok=false")
            streamInFlight = false
            streamStroke = null
            touchReplayMode = false
            moveChunkPoints.clear()
        }
    }
    // vc97 TOUCH REVERT (vc96 queue made it WORSE):
    // vc96 queued up to 12 points and dispatched them in
    // order, each 52ms + callback, so a fast swipe lagged
    // behind the finger by up to ~1s - exactly the
    // "delay, khong theo tuong tac" in the vc96 test.
    // 9Flip's LIVE touch (f4.d/o/n, decompiled 27/10) does
    // the opposite: it keeps only the LATEST pending point
    // (a4Var.f1239c), and if a segment is already in flight
    // it just updates that pending point, it does NOT queue
    // a new segment. The 12-deep queue in f4.m is for
    // pre-built gestures, not for live finger movement.
    // So vc97 goes back to latest-only coalescing, with
    // 9Flip d() durations: 16ms for a new/moved segment,
    // 1ms for an unmoved/final continuation. No history
    // points as separate segments (history is already old
    // by delivery time; dispatching it in order only adds
    // lag behind the current finger position).
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
    // vc174 DIRECTION A (9Flip g5 model, own code): map through
    // the INVERSE of the TextureView's actually-drawn transform
    // instead of recomputing fit params. The old code rebuilt
    // the fit from current size/zoom/rotation and inverted it
    // by hand, so any place where the drawn transform and the
    // mapping inputs disagreed became a wrong position
    // (vc173 fixed one such place and still failed). The
    // matrix on the view IS what the user sees, so inverting
    // it cannot diverge from the drawing: letterbox, crop,
    // pan and rotation are all inside it already.
    // g5 equivalent: inverse-map the point, then scale the
    // stretched point (buffer stretched to view) to source
    // size. The stretched-space bounds check IS the visible
    // rect check (letterbox bars map outside [0,vw]x[0,vh]).
    private fun mirrorViewToMain(viewX: Float, viewY: Float): Pair<Float, Float>? {
        if (!::textureView.isInitialized) return null
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        val sw = sourceW; val sh = sourceH
        if (vw <= 0f || vh <= 0f || sw <= 0 || sh <= 0) return null
        val m = Matrix()
        textureView.getTransform(m)
        val inv = Matrix()
        if (!m.invert(inv)) return null
        val pts = floatArrayOf(viewX, viewY)
        inv.mapPoints(pts)
        if (pts[0] < 0f || pts[0] > vw || pts[1] < 0f || pts[1] > vh) return null
        val mainX = (pts[0] * sw / vw).coerceIn(0f, (sw - 1).toFloat())
        val mainY = (pts[1] * sh / vh).coerceIn(0f, (sh - 1).toFloat())
        return Pair(mainX, mainY)
    }

    // vc185 CLAMPED MAP: same inverse transform as
    // mirrorViewToMain, but points in the black bars
    // clamp to the nearest feed edge instead of
    // returning null. Used ONLY for MOVE on an
    // already-active stream: a swipe that leaves
    // the feed strip keeps its main finger pinned
    // at the edge (instead of stalling mid-air)
    // until UP finalizes it there. DOWN keeps the
    // strict map (a tap fully inside a bar must
    // stay inert), and UP already falls back to
    // the last mapped point.
    private fun mirrorViewToMainClamped(viewX: Float, viewY: Float): Pair<Float, Float>? {
        if (!::textureView.isInitialized) return null
        val vw = textureView.width.toFloat()
        val vh = textureView.height.toFloat()
        val sw = sourceW; val sh = sourceH
        if (vw <= 0f || vh <= 0f || sw <= 0 || sh <= 0) return null
        val m = Matrix()
        textureView.getTransform(m)
        val inv = Matrix()
        if (!m.invert(inv)) return null
        val pts = floatArrayOf(viewX, viewY)
        inv.mapPoints(pts)
        val cx = pts[0].coerceIn(0f, vw)
        val cy = pts[1].coerceIn(0f, vh)
        val mainX = (cx * sw / vw).coerceIn(0f, (sw - 1).toFloat())
        val mainY = (cy * sh / vh).coerceIn(0f, (sh - 1).toFloat())
        return Pair(mainX, mainY)
    }

    private fun dispatchStreamSegment(toX: Float, toY: Float, willContinue: Boolean) {
        dispatchStreamChunk(listOf(Pair(toX, toY)), willContinue)
    }


    // vc177: 1px away from streamLast, inside source
    // bounds, so a stop-then-release final is never a
    // zero-length segment.
    private fun nudgedFinalPoint(): Pair<Float, Float> {
        if (streamLastY < sourceH - 1) return Pair(streamLastX, streamLastY + 1f)
        if (streamLastY > 0) return Pair(streamLastX, streamLastY - 1f)
        if (streamLastX < sourceW - 1) return Pair(streamLastX + 1f, streamLastY)
        return Pair(streamLastX - 1f, streamLastY)
    }
    private fun dispatchStreamChunk(points: List<Pair<Float, Float>>, willContinue: Boolean) {
        if (points.isEmpty()) return
        val acc = MyAccessibilityService.instance
        if (acc == null) {
            // vc90 TOUCH DIAGNOSTIC: never fail silently again.
            setStatus("Cảm ứng cần bật Accessibility cho CFlip (Cài đặt > Trợ năng, tắt/bật lại 1 lần)")
            return
        }
        // vc98: one Path for the whole chunk, not one
        // segment per point. First point of the chunk
        // continues from streamLast; every later point is
        // a lineTo in the same stroke, so the system plays
        // them continuously with no callback gaps.
        // vc177 LOG-PROVEN (vc176 log 30/10): the system
        // CANCELS a continued stroke whose path has zero
        // length (same point as streamLast) within ~2ms.
        // The cancel clears CFlip state, so the later UP
        // is ignored and no final segment is ever sent:
        // a stop-then-release shade drag hangs at ~50%.
        // vc175's zero-length lineTo did NOT help: the
        // system still sees zero movement and cancels.
        // So: never dispatch a zero-length continuation
        // (a stationary finger sends no events, like a
        // real finger), and nudge a stop-then-release
        // FINAL by 1px so the system accepts the end.
        var effPoints = points
        // vc178: vc177 log 04:02 30/10 - a 0.58px creep
        // segment (934.3922,584.9121 -> 585.48865) was
        // CANCELLED the same way exact duplicates were
        // in vc176, while two earlier sub-1px segments
        // in the same drag completed by luck. So the
        // guard is no longer exact-equality: any
        // single-point non-final segment moving <1px
        // from streamLast is dropped (a stationary
        // finger sends no events; the next >=1px point
        // is measured from the unchanged streamLast,
        // so movement accumulates instead of being
        // lost). A final within <1px is nudged to
        // exactly 1px, as vc177 did for duplicates.
        if (effPoints.size == 1 && kotlin.math.hypot(effPoints[0].first - streamLastX, effPoints[0].second - streamLastY) < 1f) {
            if (!willContinue) {
                effPoints = listOf(nudgedFinalPoint())
                android.util.Log.i("CFlipMirror", "PRES FINAL nudged to " + effPoints[0])
            } else {
                return
            }
        }
        val first = effPoints.first()
        val moved = kotlin.math.hypot(first.first - streamLastX, first.second - streamLastY) >= 1f
        val segDuration = when {
            streamStroke == null -> 16L
            effPoints.size > 1 -> effPoints.size.toLong() * 22L
            !moved -> 1L
            else -> 16L
        }.coerceIn(1L, 220L)
        val path = Path()
        path.moveTo(streamLastX, streamLastY)
        // vc175: 9Flip f4.c lineTos EVERY point
        // unconditionally. CFlip skipped lineTo when
        // the point equalled streamLast, so a hold
        // segment or a stop-then-release final
        // segment became a moveTo-only path: an empty
        // continuation the system never ends. That
        // is the vc174 symptom exactly: a partial
        // shade drag stops at ~50% and hangs there,
        // and the never-closed stroke then blocks
        // every prepared tap behind it. A zero-length
        // lineTo is a valid segment.
        for (pt in effPoints) {
            path.lineTo(pt.first, pt.second)
        }
        val last = effPoints.last()
        val stroke = if (streamStroke == null) {
            GestureDescription.StrokeDescription(path, 0L, segDuration, willContinue)
        } else {
            streamStroke!!.continueStroke(path, 0L, segDuration, willContinue)
        }
        streamStroke = stroke
        streamLastX = last.first
        streamLastY = last.second
        touchLastDispatchMs = android.os.SystemClock.uptimeMillis()
        streamInFlight = true
        val gen = streamGeneration
        android.util.Log.i("CFlipMirror", "PRES CHUNK willContinue=" + willContinue + " n=" + effPoints.size + " first=" + effPoints.first())
        val isFinalSegment = !willContinue
        val gesture = GestureDescription.Builder().setDisplayId(0).addStroke(stroke).build()
        // vc98: 9Flip f4.i() passes an explicit main-looper
        // handler to dispatchGesture; CFlip passed null.
        // Explicit handler matches 9Flip and avoids the
        // callback landing on an arbitrary thread.
        val ok = acc.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) {
                android.util.Log.i("CFlipMirror", "PRES CHUNK_CB completed isFinal=" + isFinalSegment + " genOk=" + (gen == streamGeneration))
                if (gen != streamGeneration) return
                streamInFlight = false
                if (isFinalSegment) {
                    streamStroke = null
                    touchReplayMode = false
                    val pinch = pinchPending
                    pinchPending = null
                    if (pinch != null) {
                        val a2 = MyAccessibilityService.instance
                        if (a2 != null) a2.dispatchGesture(pinch, null, gestureHandler)
                    }
                    // vc104: a prepared gesture queued
                    // during live (rare, e.g. long-press
                    // runnable racing live start) now
                    // drains, since the live stroke is
                    // closed and no gesture is in flight.
                    dispatchNextPrepared()
                    return
                }
                onStreamSegmentDone()
            }
            override fun onCancelled(g: GestureDescription?) {
                android.util.Log.i("CFlipMirror", "PRES CHUNK_CB cancelled")
                if (gen != streamGeneration) return
                streamInFlight = false
                streamStroke = null
                touchActive = false
                pinchActive = false
                streamPendingX = null
                streamPendingY = null
                streamPendingFinal = false
                moveChunkPoints.clear()
                touchReplayMode = false
                pinchPending = null
            }
        }, gestureHandler)
        android.util.Log.i("CFlipMirror", "PRES CHUNK dispatched ok=" + ok + " willContinue=" + willContinue)
        if (!ok) {
            android.util.Log.w("CFlipMirror", "dispatchGesture rejected, touch stream dropped")
            streamInFlight = false
            streamStroke = null
            touchActive = false
            streamPendingX = null
            streamPendingY = null
            streamPendingFinal = false
            moveChunkPoints.clear()
        }
    }

    // vc103 LATEST-ONLY LIVE (decompile both apps,
    // 27/10): vc98 chunking was the lag source.
    // 9Flip live (f4.d/o/n) never replays old points:
    // it keeps ONE pending point (a4.f1239c), and on
    // completion builds the next segment from that
    // LATEST point only, duration 16ms if moved >=1px,
    // 1ms if not moved / final. PowerFlip loop (f4.d)
    // is the same idea at tick level: each 25ms tick
    // drains ONLY points since the last tick, never
    // a backlog. CFlip vc98 did the opposite: up to
    // 8 points in one Path, duration n*22ms (up to
    // 220ms), so main played finger positions from
    // up to 220ms ago while the real finger had
    // already moved on - smooth, but NOT following
    // the finger, and NOT "an vao dau thi tuong tac
    // nhu vay". vc103: dispatchNextChunk takes the
    // LAST queued point only and discards older
    // queued points. A single point chunk gives
    // dispatchStreamChunk duration 16/1ms, exactly
    // 9Flip d(). Prepared gestures (tap 50ms,
    // drag path, long-press) do NOT go through here
    // - they dispatch directly, so they keep their
    // full point lists.
    private fun dispatchNextChunk() {
        if (moveChunkPoints.isEmpty()) {
            if (streamPendingFinal) {
                streamPendingFinal = false
                dispatchStreamSegment(touchLastX, touchLastY, false)
            }
            return
        }
        if (touchReplayMode) {
            val chunk = mutableListOf<Pair<Float, Float>>()
            while (chunk.size < 8 && moveChunkPoints.isNotEmpty()) {
                chunk.add(moveChunkPoints.removeFirst())
            }
            val isFinal = streamPendingFinal && moveChunkPoints.isEmpty()
            if (isFinal) streamPendingFinal = false
            dispatchStreamChunk(chunk, !isFinal)
            return
        }
        val last = moveChunkPoints.last()
        moveChunkPoints.clear()
        val isFinal = streamPendingFinal
        if (isFinal) streamPendingFinal = false
        dispatchStreamChunk(listOf(last), !isFinal)
    }

    private fun onStreamSegmentDone() {
        dispatchNextChunk()
    }

    private fun queueStreamSegment(toX: Float, toY: Float, final: Boolean) {
        if (final) streamPendingFinal = true
        android.util.Log.i("CFlipMirror", "PRES QUEUE final=" + final + " pt=" + toX + "," + toY + " inFlight=" + streamInFlight)
        streamPendingX = toX
        streamPendingY = toY
        if (!final) {
            // vc103 LATEST-ONLY: do NOT append to a
            // backlog. Overwrite the single queued
            // point with the latest MOVE point. If a
            // segment is in flight, this point waits
            // as pending and is dispatched on
            // completion (or by the vc101 watchdog
            // loop if the callback is lost). Older
            // queued points are discarded, not
            // replayed - that is 9Flip f4.o and
            // PowerFlip tick-drain, and it is what
            // makes main follow the finger instead
            // of replaying where it was 200ms ago.
            // vc178: a latest point moving <1px from
            // streamLast is dropped in
            // dispatchStreamChunk (no 1ms hold segment
            // any more - vc177 log shows a 0.58px one
            // getting cancelled); movement accumulates
            // against the unchanged streamLast.
            moveChunkPoints.clear()
            moveChunkPoints.addLast(Pair(toX, toY))
        } else {
            // Final point always queued, even if unmoved
            // (a tap's UP must land as a final segment).
            if (moveChunkPoints.isEmpty() || moveChunkPoints.last() != Pair(toX, toY)) {
                moveChunkPoints.addLast(Pair(toX, toY))
            }
        }
        if (!streamInFlight) {
            dispatchNextChunk()
        }
    }

    private fun dispatchU2Fallback() {
        val acc = MyAccessibilityService.instance ?: return
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        // Tap: total view movement <= slop, single
        // 42ms moveTo gesture (f4.v).
        if (touchTotalViewMove <= slop || touchPointList.size < 2) {
            val pt = touchPointList.lastOrNull() ?: return
            val path = Path()
            path.moveTo(pt.first, pt.second)
            path.lineTo(pt.first, pt.second)
            val gesture = GestureDescription.Builder().setDisplayId(0)
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 42L))
                .build()
            // vc104: 9Flip tap is 42ms (f4.v), not
            // PowerFlip 50ms (vc101). Via prepared
            // queue so it never overlaps a live stream.
            dispatchPreparedGesture(gesture)
            return
        }
        // Drag/swipe: one Path from the whole list,
        // duration by distance / 2.4 px/ms, clamped
        // 120..650ms (u2 UP branch).
        var dist = 0.0
        for (i in 1 until touchPointList.size) {
            val a = touchPointList[i - 1]; val b = touchPointList[i]
            dist += kotlin.math.hypot(b.first - a.first, b.second - a.second)
        }
        val duration = dist.toLong().let { (it / 2.4).toLong() }.coerceIn(120L, 650L)
        val path = Path()
        val first = touchPointList.first()
        path.moveTo(first.first, first.second)
        for (i in 1 until touchPointList.size) {
            val pt = touchPointList[i]
            path.lineTo(pt.first, pt.second)
        }
        val gesture = GestureDescription.Builder().setDisplayId(0)
            .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
            .build()
        dispatchPreparedGesture(gesture)
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
            if (acc != null) acc.dispatchGesture(gesture, null, gestureHandler)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // vc187 ACTIVITY PORT: the service hands this Activity its session
        // params through the relay (source size, orientation, content
        // rotation) before launch; callbacks are bound here so the
        // touch/surface code below stays identical to the Presentation.
        MirrorSurfaceRelay.activity = this
        sourceW = MirrorSurfaceRelay.sourceW
        sourceH = MirrorSurfaceRelay.sourceH
        windowOrientation = MirrorSurfaceRelay.windowOrientation
        onSurfaceReady = MirrorSurfaceRelay.onSurfaceReady
        onSurfaceGone = MirrorSurfaceRelay.onSurfaceGone
        onControl = MirrorSurfaceRelay.onControl
        // vc184 9FLIP PARITY (p4 onCreate): a mirror
        // window must never be cancelable - BACK or an
        // outside touch must not dismiss the feed.
        onBackPressedDispatcher.addCallback(object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* vc187: BACK must never close the mirror feed */ }
        })
        // vc185 THEME PORT (9Flip p4 parity): a
        // non-floating full-screen window must fill
        // the display like p4's setLayout(-1,-1),
        // and must not dim behind itself (p4
        // clearFlags(2) = FLAG_DIM_BEHIND).
        window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
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
        // vc187: an Activity gets these through setters (plus KEEP_SCREEN_ON).
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // vc72: carry the captured orientation into this window.
        if (windowOrientation != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            requestedOrientation = windowOrientation
        }
        // vc45: the mirror used to be a bare TextureView with no
        // control at all - combined with HIDE_COVER, that was the
        // "mất luôn AssistiveTouch" trap. Now the presentation is
        // a FrameLayout: TextureView fills it, and a "Dừng phản
        // chiếu" button sits top-right as the always-visible exit.
        // Tapping it stops ScreenMirrorService, which restores the
        // AssistiveTouch bubble (see its cleanup()).
        val root = android.widget.FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        // vc87 CRASH FIX (logcat 03:29, proven): TextureView does NOT
        // support a background drawable — setBackgroundColor() throws
        // UnsupportedOperationException on this device, which is why
        // pres.show() always failed ("Presentation not shown") and
        // the service then crashed in the overlay fallback too.
        // The root FrameLayout above is already black, so the
        // TextureView needs no background of its own.
        textureView = TextureView(this).apply {
            // vc169 9Flip i4 parity: the mirror surface is opaque
            // content (black root behind it). An opaque surface
            // lets the compositor skip blending, which 9Flip gets
            // from setOpaque(true) and CFlip was missing.
            setOpaque(true)
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
                    setStatus("Mirror: Activity surface ready")
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
                        // vc104 u2 DOWN: parent must not
                        // steal the touch, then reset
                        // (u2 a(): if live running,
                        // finalize it first), map the
                        // point (g5: null outside feed
                        // rect -> touch invalid, return
                        // false), record double-tap
                        // candidate, list=[down],
                        // post long-press runnable.
                        // NO dispatch here - live
                        // starts only after slop.
                        runCatching { textureView.parent?.requestDisallowInterceptTouchEvent(true) }
                        // Note: u2 a() finalizes a previous
                        // live on DOWN, but CFlip has no
                        // global gesture queue for live
                        // segments, so finalizing here
                        // and then resetting streamStroke
                        // would turn the final segment
                        // into a new stroke (or drop it
                        // if queued). Previous live is
                        // already finalized by UP /
                        // CANCEL / POINTER_DOWN in
                        // normal sequences, so DOWN
                        // just resets, like vc102/103.
                        val main = mirrorViewToMain(event.x, event.y)
                        android.util.Log.i("CFlipMirror", "PRES DOWN view=" + event.x + "," + event.y + " main=" + main)
                        if (main == null) {
                            // vc185 PENDING DOWN: a swipe
                            // that starts in a black bar
                            // used to be dropped whole
                            // (DOWN returned false, so
                            // every later MOVE was
                            // ignored even after the
                            // finger crossed into the
                            // feed) — that is why home
                            // page swipes failed while
                            // bottom-bar gestures (whose
                            // DOWN lands inside the
                            // strip) worked. Now a bar
                            // DOWN starts a pending
                            // touch: it consumes the
                            // event, stays inert while
                            // the finger stays in the
                            // bar, and becomes a real
                            // touch at the first
                            // in-strip MOVE (see MOVE).
                            // A tap fully inside a bar
                            // still dispatches nothing
                            // (see UP).
                            touchActive = true
                            touchPendingOutside = true
                            touchDownViewX = event.x; touchDownViewY = event.y
                            touchLastViewX = event.x; touchLastViewY = event.y
                            touchTotalViewMove = 0f
                            touchLiveStarted = false
                            touchSlopView = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                            touchLongPressFlag = false
                            touchMultiFlag = false
                            touchDoubleTapCandidate = false
                            touchReplayMode = false
                            touchPointList.clear()
                            pinchActive = false
                            pinchPending = null
                            streamStroke = null
                            streamInFlight = false
                            streamPendingX = null; streamPendingY = null
                            streamPendingFinal = false
                            moveChunkPoints.clear()
                            streamGeneration++
                            gestureHandler.removeCallbacks(touchLongPressRunnable)
                            gestureHandler.postDelayed(touchLongPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                            return@setOnTouchListener true
                        }
                        touchPendingOutside = false
                        touchStartX = main.first; touchStartY = main.second
                        touchLastX = main.first; touchLastY = main.second
                        touchStartTime = event.eventTime
                        touchDownViewX = event.x; touchDownViewY = event.y
                        touchLastViewX = event.x; touchLastViewY = event.y
                        touchTotalViewMove = 0f
                        touchLiveStarted = false
                        touchSlopView = android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat()
                        val dt = event.eventTime - touchLastUpMs
                        touchDoubleTapCandidate = dt in 1..android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
                        touchLongPressFlag = false
                        touchMultiFlag = false
                        touchReplayMode = false
                        touchPointList.clear()
                        touchPointList.add(Pair(main.first, main.second))
                        touchActive = true
                        pinchActive = false
                        pinchPending = null
                        streamStroke = null
                        streamLastX = main.first; streamLastY = main.second
                        streamInFlight = false
                        streamPendingX = null; streamPendingY = null
                        streamPendingFinal = false
                        moveChunkPoints.clear()
                        streamGeneration++
                        hideStatus()
                        gestureHandler.removeCallbacks(touchLongPressRunnable)
                        gestureHandler.postDelayed(touchLongPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                        true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (!touchActive || pinchActive) return@setOnTouchListener true
                        if (touchPendingOutside) return@setOnTouchListener true
                        if (event.pointerCount < 2) return@setOnTouchListener true
                        // vc104 u2 POINTER_DOWN: cancel
                        // long-press, set multi flag,
                        // finalize live if running.
                        // Pinch itself is NOT a 9Flip
                        // feed feature (feed u2 is
                        // single-pointer; 9Flip multi
                        // is trackpad y4, not feed) -
                        // CFlip keeps its prepared
                        // pinch as an extra, after
                        // cancelling the single live
                        // stream exactly as u2 does.
                        gestureHandler.removeCallbacks(touchLongPressRunnable)
                        touchMultiFlag = true
                        touchLongPressFlag = false
                        touchDoubleTapCandidate = false
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
                            // vc104 u2 MOVE, single
                            // pointer only, not multi:
                            // total view movement
                            // accumulated; > slop
                            // cancels long-press;
                            // live starts ONLY after
                            // slop, with the FIRST
                            // list point (f4.b), then
                            // pending = latest point
                            // (f4.o). Long-press flag
                            // blocks live start, like u2.
                            if (touchMultiFlag || event.pointerCount != 1) return@setOnTouchListener true
                            touchTotalViewMove += kotlin.math.hypot(event.x - touchLastViewX, event.y - touchLastViewY)
                            touchLastViewX = event.x; touchLastViewY = event.y
                            if (touchTotalViewMove > touchSlopView) {
                                gestureHandler.removeCallbacks(touchLongPressRunnable)
                            }
                            // vc185: a pending touch
                            // becomes real at the first
                            // in-strip MOVE (the entry
                            // point is its DOWN); a
                            // bar-only MOVE stays
                            // pending. An active stream
                            // uses the clamped map so a
                            // swipe leaving the strip
                            // pins at the edge instead
                            // of stalling.
                            if (touchPendingOutside) {
                                val entry = mirrorViewToMain(event.x, event.y)
                                if (entry == null) return@setOnTouchListener true
                                android.util.Log.i("CFlipMirror", "PRES ENTER feed from pending view=" + event.x + "," + event.y + " main=" + entry)
                                touchPendingOutside = false
                                touchStartX = entry.first; touchStartY = entry.second
                                touchLastX = entry.first; touchLastY = entry.second
                                touchStartTime = event.eventTime
                                touchDownViewX = event.x; touchDownViewY = event.y
                                touchTotalViewMove = 0f
                                touchLiveStarted = false
                                val dt = event.eventTime - touchLastUpMs
                                touchDoubleTapCandidate = dt in 1..android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
                                touchLongPressFlag = false
                                touchMultiFlag = false
                                touchPointList.clear()
                                touchPointList.add(Pair(entry.first, entry.second))
                                streamStroke = null
                                streamLastX = entry.first; streamLastY = entry.second
                                streamInFlight = false
                                streamPendingX = null; streamPendingY = null
                                streamPendingFinal = false
                                moveChunkPoints.clear()
                                streamGeneration++
                                hideStatus()
                                gestureHandler.removeCallbacks(touchLongPressRunnable)
                                gestureHandler.postDelayed(touchLongPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                                return@setOnTouchListener true
                            }
                            val main = mirrorViewToMainClamped(event.x, event.y)
                            if (main != null) {
                                touchLastX = main.first; touchLastY = main.second
                                if (touchPointList.size >= 96) touchPointList.removeAt(1)
                                touchPointList.add(Pair(main.first, main.second))
                                if (!touchLiveStarted && !touchLongPressFlag && touchTotalViewMove > touchSlopView) {
                                    touchLiveStarted = true
                                    startTouchLoop()
                                    val first = touchPointList.first()
                                    queueStreamSegment(first.first, first.second, false)
                                } else if (touchLiveStarted) {
                                    queueStreamSegment(main.first, main.second, false)
                                }
                            }
                            true
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (!pinchActive) return@setOnTouchListener true
                        pinchActive = false
                        touchActive = false
                        stopTouchLoop()
                        val durationMs = 180L // vc96: 9Flip y4 pinch is fixed 180ms, not the actual hold time
                        endPinch(durationMs)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!touchActive) {
                            android.util.Log.i("CFlipMirror", "PRES UP ignored touchActive=false")
                            return@setOnTouchListener false
                        }
                        if (touchPendingOutside) {
                            // vc185: a touch that never
                            // entered the feed dispatches
                            // nothing (a tap fully inside
                            // a black bar stays inert).
                            gestureHandler.removeCallbacks(touchLongPressRunnable)
                            touchActive = false
                            touchPendingOutside = false
                            touchLiveStarted = false
                            touchLongPressFlag = false
                            touchMultiFlag = false
                            touchDoubleTapCandidate = false
                            touchTotalViewMove = 0f
                            touchPointList.clear()
                            stopTouchLoop()
                            return@setOnTouchListener true
                        }
                        if (pinchActive) {
                            pinchActive = false
                            touchActive = false
                            stopTouchLoop()
                            val durationMs = 180L // vc96: 9Flip y4 pinch is fixed 180ms, not the actual hold time
                            endPinch(durationMs)
                        } else {
                            // vc104 u2 UP, exact order:
                            // remove long-press, then
                            // live -> finalize (f4.n);
                            // not live + long-press ->
                            // lastUp=0, no tap;
                            // not live + double-tap
                            // candidate + total > slop
                            // + list>=2 -> k4 replay;
                            // not live + total <= slop
                            // -> tap 42ms (f4.v);
                            // not live + list>=2 ->
                            // one Path, dist/2.4
                            // clamp 120-650 (f4.u);
                            // list<2 -> tap last point.
                            gestureHandler.removeCallbacks(touchLongPressRunnable)
                            val main = mirrorViewToMain(event.x, event.y)
                            if (main != null) {
                                touchLastX = main.first; touchLastY = main.second
                                if (touchPointList.size >= 96) touchPointList.removeAt(1)
                                touchPointList.add(Pair(main.first, main.second))
                            }
                            if (touchLiveStarted) {
                                queueStreamSegment(touchLastX, touchLastY, true)
                                if (touchLongPressFlag || touchTotalViewMove > touchSlopView) {
                                    touchLastUpMs = 0L
                                } else {
                                    touchLastUpMs = event.eventTime
                                }
                                if (moveChunkPoints.isNotEmpty() && !streamInFlight) dispatchNextChunk()
                            } else if (touchLongPressFlag) {
                                touchLastUpMs = 0L
                            } else if (touchDoubleTapCandidate && touchTotalViewMove > touchSlopView && touchPointList.size >= 2) {
                                dispatchK4Replay()
                                touchLastUpMs = 0L
                            } else if (touchTotalViewMove <= touchSlopView) {
                                val pt = touchPointList.lastOrNull()
                                if (pt != null) {
                                    val path = Path(); path.moveTo(pt.first, pt.second); path.lineTo(pt.first, pt.second)
                                    val gesture = GestureDescription.Builder().setDisplayId(0)
                                        .addStroke(GestureDescription.StrokeDescription(path, 0L, 42L))
                                        .build()
                                    dispatchPreparedGesture(gesture)
                                }
                                touchLastUpMs = event.eventTime
                            } else if (touchPointList.size >= 2) {
                                dispatchU2Fallback()
                                touchLastUpMs = 0L
                            } else {
                                val pt = touchPointList.lastOrNull()
                                if (pt != null) {
                                    val path = Path(); path.moveTo(pt.first, pt.second); path.lineTo(pt.first, pt.second)
                                    val gesture = GestureDescription.Builder().setDisplayId(0)
                                        .addStroke(GestureDescription.StrokeDescription(path, 0L, 42L))
                                        .build()
                                    dispatchPreparedGesture(gesture)
                                }
                                touchLastUpMs = event.eventTime
                            }
                            touchActive = false
                            touchLiveStarted = false
                            touchLongPressFlag = false
                            touchMultiFlag = false
                            touchDoubleTapCandidate = false
                            touchTotalViewMove = 0f
                            touchPointList.clear()
                            stopTouchLoop()
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        // vc179 diagnostic only: this branch had no log.
                        android.util.Log.i("CFlipMirror", "PRES COVER_CANCEL liveStarted=" + touchLiveStarted + " touchActive=" + touchActive)
                        // vc104 u2 a(): if live, finalize
                        // with cancel (final segment),
                        // then clear all state.
                        gestureHandler.removeCallbacks(touchLongPressRunnable)
                        if (touchActive && !pinchActive) {
                            if (touchLiveStarted) queueStreamSegment(touchLastX, touchLastY, true)
                        }
                        touchActive = false
                        touchPendingOutside = false
                        pinchActive = false
                        touchLiveStarted = false
                        touchLongPressFlag = false
                        touchMultiFlag = false
                        touchDoubleTapCandidate = false
                        touchTotalViewMove = 0f
                        touchPointList.clear()
                        stopTouchLoop()
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
            return android.widget.TextView(this).apply {
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
                // vc91 SLOW FIX: onClick fires on finger-UP, and
                // the old stop button (direct startService) felt
                // instant while these went through onControl.
                // Fire on DOWN instead, with alpha feedback, so
                // the response is visible on the tap itself.
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.alpha = 0.6f
                            onControl?.invoke(action)
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.alpha = 1.0f
                            true
                        }
                        else -> false
                    }
                }
            }
        }
        val column = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
        }
        val icons = listOf(
            iconBtn("\u21bb", "rotate"),
            iconBtn("+", "zoom_in"),
            iconBtn("\u2212", "zoom_out"),
            android.widget.TextView(this).apply {
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
                setOnTouchListener { v, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            v.alpha = 0.6f
                            runCatching {
                                applicationContext.startService(android.content.Intent(
                                    applicationContext,
                                    com.cuong.cflip.service.ScreenMirrorService::class.java)
                                    .setAction(com.cuong.cflip.service.ScreenMirrorService.ACTION_STOP))
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            v.alpha = 1.0f
                            true
                        }
                        else -> false
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
        // vc91 ROTATE LAYOUT FIX (vc90 test: rotate broke layout):
        // the virtual display renders at SOURCE size (1080x2640)
        // into this buffer, and rotation is a TextureView
        // TRANSFORM on top of that buffer. Swapping the buffer
        // to 2640x1080 on a 90/270 rotation made the buffer no
        // longer match the virtual display, so frames came out
        // stretched/cropped and the surface had to resize (the
        // "delay" + broken layout). Buffer stays source size
        // always; rotation lives only in the transform.
        val w = if (sourceW > 0) sourceW else display.mode.physicalWidth
        val h = if (sourceH > 0) sourceH else display.mode.physicalHeight
        return w to h
    }

    fun applyRotation(deg: Int) {
        rotationDeg = ((deg % 360) + 360) % 360
        if (::textureView.isInitialized) {
            // vc91: transform ONLY. No buffer resize (see
            // bufferSize above) - that resize was the vc90
            // rotate delay + layout break. setTransform is a
            // GPU matrix change and returns immediately.
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
            // vc94 DELAY FIX (9Flip i4.a, decompiled 27/10):
            // 9Flip calls postInvalidateOnAnimation() right
            // after setTransform, so the new matrix draws on
            // the next animation frame even if the main screen
            // is static and MediaProjection sends no new frame.
            // CFlip only called setTransform, which does NOT
            // redraw by itself - on a static main screen
            // (Settings, lock) the rotate/zoom sat idle until
            // the next main frame arrived, seconds later.
            // That is the "delay rat nang" for controls, while
            // Stop (window destroy) felt instant.
            tv.postInvalidateOnAnimation()
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
        // vc94: same as above - force redraw on next frame,
        // do not wait for the next MediaProjection frame.
        tv.postInvalidateOnAnimation()
    }

    // vc184 9FLIP SELF-HEAL: log the Dialog lifecycle so a
    // freeze test shows whether the system actually stops
    // this Presentation (heal path) or only hides it (no
    // onStop, heal cannot trigger - that itself is data).
    override fun onStart() {
        super.onStart()
        android.util.Log.i("CFlipMirror", "Mirror Activity onStart")
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.i("CFlipMirror", "Mirror Activity onStop")
        onStopped?.invoke()
    }

    // vc187: the Presentation never got onStop when swept (log-11),
    // so an Activity onDestroy here is itself diagnostic. If the
    // stop was not intentional, tell the service so it can restore
    // the cover UI (cleanup, NOT a re-show loop) after 1s.
    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.i("CFlipMirror", "Mirror Activity onDestroy, intentional=" + MirrorSurfaceRelay.intentionalStop)
        if (!MirrorSurfaceRelay.intentionalStop) {
            MirrorSurfaceRelay.onDestroyed?.invoke()
        }
        if (MirrorSurfaceRelay.activity == this) MirrorSurfaceRelay.activity = null
    }
}
