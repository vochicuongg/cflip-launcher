package com.cuong.cflip.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.cuong.cflip.CFlipApp
import com.cuong.cflip.R
import com.cuong.cflip.display.DisplayRepository
import android.app.PendingIntent
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.MotionEvent
import android.accessibilityservice.GestureDescription
import android.graphics.Point
import android.view.Display
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import com.cuong.cflip.mirror.MirrorState

/**
 * Mirrors the main screen onto the cover display.
 *
 * vc47 ARCHITECTURE CHANGE (from full 9Flip mirror decompile):
 * 9Flip's mirror does NOT render into a separate Presentation window.
 * Its cover UI itself supplies a Surface, and the projection service
 * creates an AUTO_MIRROR virtual display at the SOURCE display's size
 * into that surface - the feed lives INSIDE the normal cover UI.
 * CFlip vc32-vc46 did the opposite: a separate Presentation window
 * on the cover, sized to the COVER display. On Samsung's FlexWindow
 * a third-party Presentation is not reliably shown (the window is
 * created without error but the cover keeps showing Samsung's own
 * cover UI, so the user sees no main UI at all - exactly the vc46
 * report). vc47 therefore drops the Presentation and renders into
 * an OVERLAY on the cover (the same TYPE_APPLICATION_OVERLAY route
 * the bubble and panel already use successfully on this cover),
 * with the virtual display sized to the MAIN display (source size,
 * like 9Flip) and the TextureView scaling it down to the cover.
 * The feed will look vertically squished (tall main UI onto a near-
 * square cover) - that is inherent to mirroring, not a bug; the
 * goal of vc47 is visibility first, aspect refinement later.
 *
 * Reality check: it mirrors whatever the MAIN display shows. Fold the phone
 * fully closed and the main display is off, so the mirror goes black - for
 * folded use, the normal cover launcher Presentation is the right tool.
 * Mirroring shines in flex/tent mode and in dual-display mode (phone open,
 * cover showing a live copy, e.g. a preview for the person opposite you).
 */
class ScreenMirrorService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    // vc47 overlay route (Presentation removed): the feed view
    // lives in a cover overlay, like bubble/panel, not a
    // separate Presentation window.
    private var overlayView: View? = null
    private var overlayWm: WindowManager? = null
    // vc58 Presentation host (9Flip l4/p4 route): tried BEFORE
    // overlay. If set and showing, it owns the TextureView
    // surface; overlay is not created in that case.
    private var mirrorPresentation: com.cuong.cflip.mirror.MirrorPresentation? = null
    private var mirrorTexture: TextureView? = null
    private var mirrorSurface: Surface? = null
    // vc169 OVERLAY TOUCH lives in OverlayTouchForwarder below
    // (live machine, 9Flip u2/f4 model). The vc63 UP-only fields
    // are gone with dispatchOverlayGesture.
    // vc169 MIRROR GENERATION: bumped on every startMirroring
    // and every cleanupSession. Surface callbacks capture the
    // generation they belong to and ignore stale ones, so a
    // surface from a dead session can never attach to (or
    // release) a new session's virtual display (9Flip
    // snapshot-token rule, own code).
    private var mirrorGeneration = 0
    // vc169 SOURCE TRACKING: main display size/dpi last seen by
    // the source listener below. A change resizes the virtual
    // display instead of leaving a stale-size feed.
    private var sourceLastW = 0
    private var sourceLastH = 0
    private var sourceLastDpi = 0
    private var mainWidth: Int = 0
    private var mainHeight: Int = 0
    private var mainDpi: Int = 0
    // vc72: cover window orientation to carry into mirror windows
    // (Presentation + overlay). Set in startMirroring before any
    // mirror window is created; UNSPECIFIED means "not set yet".
    private var mirrorWinOrient: Int = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    // vc79 POWERFLIP CONTENT ROTATION: degrees to rotate the
    // mirror CONTENT (not the display) so the feed looks upright
    // even when dual snaps the cover back to 0°. Computed in
    // startMirroring as (desired - actual) mod 360, where desired
    // comes from mirrorWinOrient and actual from secondary.rotation.
    // 0 means "display already where the user wants it, no content
    // rotation". Kept (not removed) alongside vc72 window flags:
    // those flags are ignored in dual (vc72-vc74 proof), so the
    // content rotation is the mechanism that actually corrects
    // the feed; if the flags ever do take effect after start,
    // the feed may double-rotate and vc80 must make this dynamic.
    private var mirrorContentDeg: Int = 0
    // vc89 MIRROR CONTROLS + ROTATE IN APP/LOCK: follow = cover
    // rotation changes re-apply content rotation (so the feed
    // stays upright when the phone turns, whatever app or lock
    // screen main is showing - the listener fires on the cover
    // display, not on main's app); lock = freeze at start.
    private var mirrorRotateFollow: Boolean = true
    private var mirrorSecondaryId: Int = -1
    private var failurePillView: View? = null
    private var failurePillWm: WindowManager? = null
    private val mirrorDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != mirrorSecondaryId || !mirrorRotateFollow) return
            val sec = DisplayRepository(this@ScreenMirrorService).secondaryDisplay() ?: return
            mirrorContentDeg = computeContentDeg(sec)
            mirrorPresentation?.applyRotation(mirrorContentDeg)
            val tv = mirrorTexture
            if (tv != null && overlayView != null) applyFitTransform(tv, tv.width, tv.height)
        }
    }
    // vc169 SOURCE LISTENER (9Flip s4/l DisplayListener, own
    // code): the main display can rotate or resize while
    // mirroring (user turns the phone). The old code only
    // watched the cover display, so a main rotation left a
    // stale-size virtual display (stretched feed, wrong touch
    // mapping). On a real size/dpi change: update source size,
    // grow the TextureView buffers, resize the virtual display
    // (recreate on the current surface if resize fails), and
    // re-apply both windows' transforms with the new size.
    private val mirrorSourceListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val proj = projection ?: return
            if (!MirrorState.active) return
            val dm = getSystemService(DisplayManager::class.java) ?: return
            val main = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val m = android.util.DisplayMetrics()
            @Suppress("DEPRECATION") main.getRealMetrics(m)
            val w = m.widthPixels.coerceAtLeast(1)
            val h = m.heightPixels.coerceAtLeast(1)
            val dpi = m.densityDpi.coerceAtLeast(120)
            if (w == sourceLastW && h == sourceLastH && dpi == sourceLastDpi) return
            sourceLastW = w; sourceLastH = h; sourceLastDpi = dpi
            mainWidth = w; mainHeight = h; mainDpi = dpi
            mirrorPresentation?.textureView?.surfaceTexture?.setDefaultBufferSize(w, h)
            mirrorTexture?.surfaceTexture?.setDefaultBufferSize(w, h)
            val vd = virtualDisplay
            val surface = mirrorSurface
            if (vd != null && surface != null) {
                runCatching { vd.resize(w, h, dpi) }.onFailure {
                    Log.w(TAG, "Virtual display resize failed, recreating", it)
                    virtualDisplay?.release(); virtualDisplay = null
                    createVirtualDisplay(proj, surface)
                }
            }
            mirrorPresentation?.let { pres ->
                pres.sourceW = w; pres.sourceH = h
                pres.applyRotation(mirrorContentDeg)
            }
            val tv = mirrorTexture
            if (tv != null && overlayView != null) applyFitTransform(tv, tv.width, tv.height)
        }
    }

    private fun computeContentDeg(secondary: Display): Int {
        val desiredDeg = when (mirrorWinOrient) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> 0
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> 90
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> 180
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> 270
            else -> -1
        }
        val actualDeg = when (secondary.rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        return if (desiredDeg < 0) 0 else ((desiredDeg - actualDeg) % 360 + 360) % 360
    }
    private fun removeFailurePill() {
        runCatching {
            val v = failurePillView; val w = failurePillWm
            if (v != null && w != null) w.removeView(v)
        }
        failurePillView = null; failurePillWm = null
    }
    private fun hideCoverBubble() {
        runCatching {
            startService(Intent(this, CoverForegroundService::class.java).apply {
                action = CoverForegroundService.ACTION_HIDE_COVER
            })
        }
    }
    // vc73: mirror's own rotate host view (1x1 overlay). Removed
    // in cleanupSession. See startMirroring for why it exists.
    private var mirrorRotateView: View? = null
    private var mirrorRotateWm: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "Projection stopped by system/user")
            // vc66: was silent stopSelf, user saw "ok then
            // disconnected, nothing shown". Cleanup first
            // (bubble returns in normal mode), then show the
            // reason on the cover for 5s via the helper.
            cleanupAndDisableDual()
            val sec = DisplayRepository(this@ScreenMirrorService).secondaryDisplay()
            if (sec != null) {
                this@ScreenMirrorService.showFailurePillAndStop(sec, "Mirror stopped by system (projection onStop)")
            } else {
                stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            // vc103 STOP -> LOCK (user request 27/10):
            // neither 9Flip nor PowerFlip locks on
            // mirror stop in decompile (9Flip: SCREEN_OFF
            // -> stop, reverse direction; PowerFlip:
            // stopService + finish, no lock; CFlip
            // vc94 MainHost comment also says main
            // only follows its own timeout after stop).
            // The user wants stop to lock like before,
            // so explicit user stop (this ACTION_STOP,
            // from the cover stop column / notification)
            // now locks the whole device via the
            // accessibility service's GLOBAL_ACTION_LOCK
            // _SCREEN - no device-admin lockNow needed.
            // System stops (projection onStop, failure,
            // onDestroy) do NOT lock: they are not a
            // user stop, and locking there would lock
            // the phone when the user merely unfolded it.
            ACTION_STOP -> { cleanupAndDisableDual(); lockDeviceOnStop(); stopSelf(); return START_NOT_STICKY }
            ACTION_APPLY_ROTATION -> {
                // vc47: with the overlay route, rotation is just
                // a virtual-display recreate on the same surface
                // at source size (TextureView scales to cover).
                val proj = projection
                val surface = mirrorSurface
                if (proj != null && surface != null) {
                    virtualDisplay?.release(); virtualDisplay = null
                    createVirtualDisplay(proj, surface)
                }
                return START_STICKY
            }
            ACTION_START -> startMirroring(intent)
        }
        return START_STICKY
    }

    private fun startMirroring(intent: Intent) {
        // vc172 ORDER FIX (vc169-vc171 bug, mine, proven by
        // reading): sessionGen MUST be captured AFTER the
        // cleanupSession() call below, because cleanupSession
        // bumps mirrorGeneration. vc169 captured it BEFORE
        // (generation N), cleanup bumped to N+1, and every
        // surface callback + the watchdog compared N == N+1
        // and never ran — so the overlay sat at "waiting
        // surface..." forever on every start, and even the
        // vc171 watchdog stayed silent (its own guard failed
        // the same way). Nothing before cleanupSession used
        // sessionGen, so moving the capture here is safe.
        // vc47: mirror notification now has a STOP action
        // (like 9Flip's), so the user can stop from the shade
        // as a third exit path besides the overlay button and
        // the panel tile.
        val stopIntent = Intent(this, ScreenMirrorService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(this, 9042, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CFlipApp.CHANNEL_ID)
            .setContentTitle("CFlip Launcher")
            .setContentText("Đang phản chiếu màn hình chính lên cover")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .addAction(R.drawable.ic_launcher_foreground, "Dừng phản chiếu", stopPi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, notification)
        }

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
        if (resultCode == 0 || resultData == null) {
            Log.w(TAG, "Missing capture result - cannot start mirroring")
            stopSelf(); return
        }

        val secondary = DisplayRepository(this).secondaryDisplay()
        if (secondary == null) {
            Log.w(TAG, "No secondary display - nothing to mirror onto")
            stopSelf(); return
        }
        // vc48 GATE (tunnel diagnosis): 9Flip refuses mirror when
        // dual display is off, and CFlip must do the same. The
        // vc47 photo (748x720, cover size) shows the cover
        // mirroring ITSELF: when folded, the main display is OFF,
        // so there is no main UI to mirror, and the capture ends
        // up capturing the cover's own feed - each frame captures
        // the previous frame and squishes it again, forming the
        // stacked tunnel of stop buttons. Two guards, either one
        // aborts before any overlay or virtual display is created:
        // (1) main display must be STATE_ON; (2) the target display
        // must be cover-sized (720x748 in either orientation) and
        // must not be our own virtual display (name CFlipMirror).
        // If either fails, stop without creating a loop.
        val dmGate = getSystemService(DisplayManager::class.java)
        val mainGate = dmGate?.getDisplay(Display.DEFAULT_DISPLAY)
        val secMode = secondary.mode
        val secW = secMode.physicalWidth; val secH = secMode.physicalHeight
        val isCoverSized = (secW in 700..760 && secH in 700..760)
        // vc65 FOLDED FIX: vc48 gate required main STATE_ON, which
        // blocks folded mirror by design - folded main is OFF,
        // so vc64 folded test (feed never appeared) died here,
        // then disable-dual turned the cover off, then the
        // one-shot MediaProjection asked again on retry. 9Flip
        // gates on dual true only, not main ON (its permission
        // activity refuses on dual off, its service requires
        // dual true + STARTING). New gate: dual ON + cover ON
        // + cover-sized + not self-capture. Main state is kept
        // only as a diagnostic in log/pill/toast, not a refusal.
        // vc62 feed success was open/flex posture (main ON), not
        // folded, which is why the old gate passed there.
        val dualOn = DualDisplayHelper.isDualOn()
        // vc82 DUAL-ONLY RETRY: vc81 pill proved gate can fail on dual
        // alone (dualOn=false, main OFF diagnostic only, cover ON,
        // cover-sized, not self-capture) because dual was turned off
        // between permission grant and service start (host death,
        // posture change, or a failed disable/enable cycle). Giving up
        // immediately wastes a granted permission. If ONLY dual fails,
        // try enabling dual once more right here (synchronous transact,
        // token set on success, no settle wait needed for the gate —
        // gate checks token + cover state/size/name, not main ON).
        // If re-enable restores dual, proceed; otherwise fall through
        // to the original refusal (toast + disable + pill + stop).
        // dualOn val stays false here, but on retry success the failure
        // path below is skipped, and proceed code never uses dualOn.
        var gateFailed = (mainGate == null || !dualOn || secondary.state != Display.STATE_ON || !isCoverSized || secondary.name == "CFlipMirror")
        if (gateFailed) {
            val dualOnly = !dualOn && mainGate != null && secondary.state == Display.STATE_ON && isCoverSized && secondary.name != "CFlipMirror"
            if (dualOnly) {
                val reEnable = DualDisplayHelper.enable()
                if (reEnable.isSuccess && DualDisplayHelper.isDualOn()) {
                    Log.i(TAG, "Mirror gate dual retry succeeded, proceeding (vc82)")
                    gateFailed = false
                } else {
                    Log.w(TAG, "Mirror gate dual retry failed: " + (reEnable.exceptionOrNull()?.message ?: "dual still off"))
                }
            }
        }
        if (gateFailed) {
            Log.w(TAG, "Mirror gate refused: dualOn=$dualOn mainState=${mainGate?.state} coverState=${secondary.state} cover=${secW}x$secH name=${secondary.name}")
            // vc54 MINIMAL SUCCESS FIX: vc51-vc53 toasted the refusal
            // only via applicationContext, which shows on the MAIN
            // display. A folded user looks at the COVER, main is
            // off, so they saw nothing and reported "tap does
            // nothing" even though the service had refused for a
            // specific reason (usually main OFF). Show the same
            // refusal on the COVER too, via a cover display
            // context, so the folded user actually sees WHY it
            // stopped. Main toast is kept as well for the open
            // case (user looking at main). No gate logic changed:
            // main ON + cover ON + cover-sized + not self-capture
            // are still required, otherwise the vc47 tunnel returns.
            android.widget.Toast.makeText(applicationContext, "Phan chieu da dung: can Dual ON + man phu that. dual=" + dualOn + " main=" + mainGate?.state + " cover=" + secondary.state, android.widget.Toast.LENGTH_LONG).show()
            runCatching {
                val coverCtx = createDisplayContext(secondary)
                android.widget.Toast.makeText(coverCtx, "Phan chieu da dung: can Dual ON. dual=" + dualOn + " main=" + mainGate?.state, android.widget.Toast.LENGTH_LONG).show()
            }
            runCatching { DualDisplayHelper.disable() }
            // vc55 REFUSAL PILL: Toasts are rate-limited by Android
            // after the first few (vc46 screenshot symptom), so a
            // repeated mirror tap can refuse silently even with
            // vc54's cover Toast. A WindowManager pill is NOT
            // rate-limited: show the refusal ON THE COVER for 4s
            // so the folded user sees WHY it stopped, every time.
            val pillAdded = runCatching {
                val refusalCtx = createDisplayContext(secondary)
                val refusalWm = refusalCtx.getSystemService(WindowManager::class.java) ?: return@runCatching false
                val pill = android.widget.TextView(refusalCtx).apply {
                    text = "Mirror stopped: dualOn=$dualOn main=${mainGate?.state} cover=${secondary.state} ${secW}x$secH"
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    textSize = 14f
                    setPadding(24, 24, 24, 24)
                }
                val pillParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
                refusalWm.addView(pill, pillParams)
                handler.postDelayed({ runCatching { refusalWm.removeView(pill) }; stopSelf() }, 4000)
                true
            }.getOrDefault(false)
            if (!pillAdded) { stopSelf() }
            return
        }

        cleanupSession() // in case of a restart, do NOT disable dual here (tile just enabled it), drop any previous session first
        // vc172: capture AFTER cleanup (see the note at the
        // top of this function) — this is the live generation
        // every surface callback and watchdog compares against.
        mirrorGeneration++
        val sessionGen = mirrorGeneration

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm?.getMediaProjection(resultCode, resultData)
        val proj = projection
        if (proj == null) { Log.w(TAG, "getMediaProjection returned null"); showFailurePillAndStop(secondary, "Mirror failed: projection null"); return }
        proj.registerCallback(projectionCallback, handler)

        // vc47 source size (9Flip approach): the virtual
        // display is sized to the MAIN display, not the cover.
        // vc44-vc46 sized it to the cover (720x748-ish) and the
        // system then had to mirror a tall main display into a
        // near-square virtual display - a second reason the feed
        // could come out black/distorted on Samsung.
        // vc55 9FLIP SIZE: 9Flip session l.e() reads display 0
        // via getRealMetrics (full real resolution, not the
        // app-usable getSize which excludes system UI area).
        // CFlip vc47-vc54 used getSize, so the virtual display
        // could be smaller than the real main display and Samsung
        // then had to mirror into a mismatched size. Use real
        // metrics, dpi clamped to >=120 like 9Flip.
        val dm = getSystemService(DisplayManager::class.java)
        val main = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val realMetrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") main?.getRealMetrics(realMetrics)
        mainWidth = realMetrics.widthPixels.coerceAtLeast(1)
        mainHeight = realMetrics.heightPixels.coerceAtLeast(1)
        mainDpi = realMetrics.densityDpi.coerceAtLeast(120)
        Log.i(TAG, "Main display source size ${mainWidth}x$mainHeight @${mainDpi}dpi, cover id ${secondary.displayId}")
        // vc169: track main size/rotation from here on (see
        // mirrorSourceListener above); stale callbacks from an
        // earlier session are invalidated by the generation.
        sourceLastW = mainWidth; sourceLastH = mainHeight; sourceLastDpi = mainDpi
        runCatching { getSystemService(DisplayManager::class.java)?.registerDisplayListener(mirrorSourceListener, handler) }

        // vc58 PRESENTATION FIRST (full 9Flip decompile):
        // 9Flip mirror UI lives in a Presentation (p4) created
        // from its AccessibilityService on display 1, NOT in a
        // TYPE_APPLICATION_OVERLAY window from a service. In
        // DUAL mode Samsung shows Presentation windows on the
        // secondary display, while service overlays (which work
        // fine in normal FlexWindow cover mode for bubble/panel)
        // may be hidden behind dual mode. CFlip vc47-vc57 used
        // overlay only, after vc32-vc46 Presentation failed for
        // OTHER reasons (dual off, main off, cover-sized virtual
        // display, permission on main, NO_HISTORY) that vc51-vc57
        // have since fixed. Retry Presentation now, with those
        // fixes in place, preferring AccessibilityService context
        // like 9Flip (l4.e), falling back to service context.
        // If Presentation.show() fails or it is not showing,
        // fall through to the vc57 overlay path below - no
        // regression, overlay remains the fallback.
        val coverCtx = createDisplayContext(secondary)
        // vc72 ORIENTATION PRESERVE: RotateHost forces cover
        // orientation in normal mode via a 1x1 overlay with
        // screenOrientation, but dual can drop that overlay,
        // snapping cover back to 0° (the vc71 180° snap). Mirror
        // windows had no screenOrientation, so they showed at
        // default 0° and the snap destroyed/recreated the surface,
        // dropping the feed after a couple seconds. Capture the
        // orientation here and carry it into BOTH mirror windows
        // below, so cover stays where the user had it. Prefer
        // RotateHost's live orientation (it survives dual in
        // memory even if its view is dropped); fall back to the
        // current cover rotation mapped like lock().
        mirrorWinOrient = com.cuong.cflip.cover.CoverRotateHostManager.currentOrientation() ?: when (secondary.rotation) {
            android.view.Surface.ROTATION_90 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            android.view.Surface.ROTATION_180 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            android.view.Surface.ROTATION_270 -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        // vc79 CONTENT ROTATION (PowerFlip lesson, own code):
        // PowerFlip does not force the display at all; it rotates
        // the mirror content (View.setRotation, default 180°) and
        // freezes rotation during mirror. CFlip vc72-vc74 proved
        // display forcing is ignored in dual, so rotate content
        // instead. Desired degrees are the inverse of the vc72
        // fallback mapping above (PORTRAIT 0, LANDSCAPE 90,
        // REVERSE_PORTRAIT 180, REVERSE_LANDSCAPE 270); unknown
        // orientations (UNSPECIFIED, FULL_SENSOR, ...) mean "no
        // known desired angle" so content rotation stays 0.
        // Example: user wants 180°, dual snaps display to 0°,
        // content rotates 180° and the feed looks upright anyway.
        // If display already matches desired, diff is 0, no
        // double-rotate. Applied to Presentation (applyRotation)
        // and to the overlay fallback (applyFitTransform) below.
        mirrorContentDeg = computeContentDeg(secondary)
        mirrorSecondaryId = secondary.displayId
        // vc90: follow is OFF by user request - rotation is now a
        // manual +90 degree step (works on lock screen and in
        // apps like Settings, because it only changes the
        // TextureView transform, never the app/display rotation).
        // The display listener is kept unregistered; cleanup
        // unregister is still runCatching-safe.
        mirrorRotateFollow = false
        removeFailurePill()
        Log.i(TAG, "vc79 mirror content rotation ${mirrorContentDeg}deg (desired orient $mirrorWinOrient, actual rotation ${secondary.rotation})")
        // vc80 REMOVE MIRROR ROTATE VIEW (vc79 regression):
        // vc73/vc74 added a second 1x1 rotate host here (fixed to
        // mirrorWinOrient), while vc78 foreground tryShow ALSO
        // ensures RotateHost during mirror (follow/lock by
        // rotateMode). Two hosts with different orientations
        // fight on every display change: display oscillates,
        // user sees rotation flip opposite and loop until tilt
        // matches the fixed host, Presentation surface is
        // destroyed/recreated repeatedly (mirror disconnects),
        // and the second attempt hits corrupted state (crash).
        // Fix: mirror service adds NO rotate host at all.
        // Foreground host (vc78) is now the SOLE host during
        // mirror, so no fighting. Snap is handled by vc79
        // content rotation (mirrorContentDeg above), not by
        // forcing display. Fields mirrorRotateView/mirrorRotateWm
        // and their cleanup removal are kept (null-safe) so
        // cleanupSession needs no change; they simply stay null.
        val presTried = runCatching {
            val acc = MyAccessibilityService.instance
            val presCtx = if (acc != null) acc.createDisplayContext(secondary) else coverCtx
            val pres = com.cuong.cflip.mirror.MirrorPresentation(presCtx, secondary).apply {
                sourceW = mainWidth; sourceH = mainHeight
                windowOrientation = mirrorWinOrient
                onSurfaceReady = { surface ->
                    // vc169: ignore a surface from a dead session.
                    if (sessionGen == mirrorGeneration) {
                        mirrorSurface = surface
                        setStatus("Mirror: Presentation surface ready, virtual display starting...")
                        createVirtualDisplay(proj, surface)
                        setStatus("Mirror: virtual display OK ${mainWidth}x${mainHeight} - feed should show")
                        Log.i(TAG, "Mirror Presentation surface ready, virtual display started")
                    }
                }
                onSurfaceGone = {
                    if (sessionGen == mirrorGeneration) {
                        mirrorSurface = null
                        virtualDisplay?.release(); virtualDisplay = null
                    }
                }
            }
            // vc79: rotate CONTENT by the diff computed above.
            // Before show() the TextureView is not initialized yet,
            // so applyRotation only stores rotationDeg; the fit
            // transform applies it once the surface is available.
            pres.applyRotation(mirrorContentDeg)
            pres.show()
            mirrorPresentation = pres
            MirrorState.active = true
            hideCoverBubble()
            pres.isShowing
        }.onFailure { Log.w(TAG, "Mirror Presentation failed", it) }.getOrDefault(false)
        if (presTried) {
            Log.i(TAG, "Mirror Presentation shown on cover display ${secondary.displayId}, overlay skipped for now")
            // vc59 AUTO-FALLBACK: vc58 left the user stuck on a
            // black Presentation if its window showed but its
            // surface never attached (surface ready never fires,
            // no feed, no overlay). If after 3s no surface is
            // ready and the Presentation is still showing, drop
            // it and try the overlay path instead - mirror
            // self-heals instead of hanging. If surface became
            // ready, or the service was stopped (cleanup clears
            // mirrorPresentation), this does nothing.
            handler.postDelayed({
                if (mirrorSurface == null && mirrorPresentation?.isShowing == true) {
                    Log.i(TAG, "Mirror Presentation surface not ready in 3s, falling back to overlay")
                    runCatching { mirrorPresentation?.dismiss() }
                    mirrorPresentation = null
                    runCatching { createOverlayMirror(proj, secondary, coverCtx, sessionGen) }
                        .onFailure { Log.w(TAG, "Mirror overlay fallback failed", it); showFailurePillAndStop(secondary, "Mirror failed: overlay error") }
                }
            }, 3000)
            mirrorPresentation?.let { pres ->
                pres.onControl = { action ->
                    Log.i(TAG, "Mirror control tapped: $action")
                    when (action) {
                        "rotate" -> {
                            // vc90: one tap = +90 degrees, cycling
                            // 0 -> 90 -> 180 -> 270 -> 0. Pure content
                            // transform, so it works regardless of
                            // main app orientation lock or lock screen.
                            mirrorContentDeg = (mirrorContentDeg + 90) % 360
                            pres.applyRotation(mirrorContentDeg)
                            val tv = mirrorTexture
                            if (tv != null && overlayView != null) applyFitTransform(tv, tv.width, tv.height)
                        }
                        "zoom_in" -> pres.zoomIn()
                        "zoom_out" -> pres.zoomOut()
                    }
                }
            }
            return
        }
        Log.i(TAG, "Mirror Presentation not shown, falling back to overlay")
        // vc47 overlay: cover display context + overlay window
        // (same route as bubble/panel, which Samsung DOES show
        // on this cover). The TextureView fills the cover; its
        // buffer is the main size, TextureView scales it down.
        // vc87: wrapped so an overlay bug becomes a failure pill,
        // never a FATAL EXCEPTION that kills the service process.
        runCatching { createOverlayMirror(proj, secondary, coverCtx, sessionGen) }
            .onFailure { Log.w(TAG, "Mirror overlay failed", it); showFailurePillAndStop(secondary, "Mirror failed: overlay error") }
    }

    // vc59 OVERLAY HELPER: extracted from startMirroring so
    // both the immediate fallback (Presentation not shown)
    // and the delayed fallback (Presentation shown but
    // surface never ready in 3s) can use the same overlay
    // path without duplicating the block. Params are the
    // projection, cover display and its display context;
    // main size / handler / mirror state are fields.
    private fun createOverlayMirror(proj: MediaProjection, secondary: Display, coverCtx: Context, sessionGen: Int) {
        val wm = coverCtx.getSystemService(WindowManager::class.java)
        if (wm == null) { Log.w(TAG, "No WindowManager for cover display"); showFailurePillAndStop(secondary, "Mirror failed: no WindowManager for cover"); return }
        val root = FrameLayout(coverCtx).apply { setBackgroundColor(0xFF000000.toInt()) }
        // vc87 CRASH FIX (logcat 03:29, proven): TextureView does NOT
        // support a background drawable — this exact line threw
        // UnsupportedOperationException and killed the whole service
        // process (FATAL EXCEPTION at ScreenMirrorService.kt:480),
        // which is why the user saw disconnect + lock + crash on
        // every attempt, including the 2nd. Root FrameLayout above
        // is already black; TextureView needs no background.
        val tv = TextureView(coverCtx)
        // vc169: same 9Flip i4 opaque-surface parity as the
        // Presentation path (see MirrorPresentation).
        tv.setOpaque(true)
        root.addView(tv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // vc55 STATUS TEXT (temporary diagnostic, top-left over
        // the feed): each mirror step updates it, so the next
        // test shows exactly how far mirror got even without
        // logcat - overlay shown? projection ok? surface ready?
        // virtual display created? It will be removed once the
        // feed itself is confirmed working.
        val statusView = android.widget.TextView(coverCtx).apply {
            text = "Mirror: starting..."
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.BLACK)
            textSize = 12f
            setPadding(16, 16, 16, 16)
        }
        root.addView(statusView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            setMargins(12, 12, 12, 12)
        })
        val stopBtn = Button(coverCtx).apply {
            text = "Dừng phản chiếu"
            setOnClickListener { cleanupAndDisableDual(); stopSelf() }
        }
        root.addView(stopBtn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(12, 12, 12, 12)
        })
        // vc169 LIVE TOUCH (9Flip u2/f4 model, own code): the vc63
        // overlay touch only dispatched ONE gesture at UP time, so
        // on the overlay path every drag felt delayed and never
        // followed the finger. This forwarder mirrors the
        // Presentation path (vc103/vc104): slop-gated live stream,
        // latest-only 16/1ms segments, tap 42ms, long-press
        // timeout+80, swipe dist/2.4 clamp 120-650, prepared queue
        // cap 12, single in-flight, main handler + callbacks.
        // Second finger cancels the live stream (9Flip u2 feed
        // is single-pointer; pinch stays a Presentation extra).
        // Stop button sits above this TextureView, so its taps
        // still stop.
        val overlayTouch = OverlayTouchForwarder()
        tv.setOnTouchListener { _, event -> overlayTouch.onTouch(tv, event) }

        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(mainWidth, mainHeight)
                applyFitTransform(tv, tv.width, tv.height)
                tv.post { applyFitTransform(tv, tv.width, tv.height) }
                // vc169: ignore a surface from a dead session.
                if (sessionGen == mirrorGeneration) {
                    val surface = Surface(st)
                    mirrorSurface = surface
                    handler.post { statusView.text = "Mirror: surface ready, virtual display starting..." }
                    createVirtualDisplay(proj, surface)
                    handler.post { statusView.text = "Mirror: virtual display OK ${mainWidth}x${mainHeight} - feed should show" }
                    handler.postDelayed({ statusView.visibility = View.GONE }, 3000)
                    Log.i(TAG, "Mirror overlay surface ready, virtual display started")
                }
            }
            // vc173 FIX (touch-position bug, mine): w/h here
            // are the SURFACE TEXTURE (buffer) size, i.e. the
            // main-display size, NOT the view size.
            // applyFitTransform expects VIEW size; passing
            // buffer size rebuilt the transform as identity
            // (f10=f12=fMin=1, dx=dy=0), so the feed
            // fill-stretched the whole cover while the touch
            // mapping still assumed fit - horizontal taps
            // landed wrong, vertical drags stayed right
            // (dy=0 both ways). Use the view size like every
            // other applyFitTransform site in this file.
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) { applyFitTransform(tv, tv.width, tv.height) }
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                if (sessionGen == mirrorGeneration) {
                    mirrorSurface = null
                    virtualDisplay?.release(); virtualDisplay = null
                }
                return true
            }
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        // vc66: overlay had no KEEP_SCREEN_ON (Presentation
        // has it), so screen timeout could turn the cover off
        // and kill an overlay-path mirror. Keep it on like
        // the Presentation path.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // vc85 LOCK FIX: same show-over-lock + turn-on flags as
            // MirrorPresentation, so the overlay fallback cannot be
            // hidden behind the cover lock screen either.
            // vc171 HW FIX: a TextureView only creates its
            // SurfaceTexture inside a hardware-accelerated window.
            // Service-added overlay windows do NOT get hardware
            // acceleration by default (Activity windows do), so
            // without this flag the overlay TextureView stayed
            // surface-less forever: status stuck at "waiting
            // surface...", black feed, no virtual display. This
            // flag is the standard route for overlay windows
            // carrying a TextureView.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            // vc72: same orientation preserve as Presentation,
            // so the fallback overlay does not snap cover either.
            if (mirrorWinOrient != android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                screenOrientation = mirrorWinOrient
            }
        }
        runCatching { wm.addView(root, params) }
            .onSuccess {
                overlayView = root; overlayWm = wm; mirrorTexture = tv
                MirrorState.active = true
                hideCoverBubble()
                statusView.text = "Mirror: overlay OK, waiting surface..."
                Log.i(TAG, "Mirror overlay added on cover display ${secondary.displayId}")
                // vc171: if the surface still is not ready 3s
                // after the overlay appeared, say so plainly
                // instead of leaving a silent black screen.
                // (Before this flag fix, this is exactly where
                // the overlay got stuck.)
                handler.postDelayed({
                    if (sessionGen == mirrorGeneration && overlayView == root && mirrorSurface == null) {
                        statusView.text = "Mirror: overlay surface chưa sẵn sàng — thử tắt/mở lại phản chiếu"
                        Log.w(TAG, "Mirror overlay surface not ready in 3s")
                    }
                }, 3000)
            }
            .onFailure { e -> Log.w(TAG, "System refused mirror overlay on cover", e); showFailurePillAndStop(secondary, "Mirror failed: overlay refused: " + (e.message ?: "unknown")) }
    }

    // vc174 DIRECTION A (same as MirrorPresentation): invert
    // the overlay TextureView's actually-drawn transform
    // instead of recomputing fit params from tv size +
    // main size + mirrorContentDeg. Any divergence between
    // the drawn transform and those inputs used to land
    // touches wrong; the matrix on the view cannot diverge
    // from what the user sees.
    private fun overlayViewToMain(viewX: Float, viewY: Float, tv: TextureView): Pair<Float, Float>? {
        val vw = tv.width.toFloat()
        val vh = tv.height.toFloat()
        if (vw <= 0f || vh <= 0f || mainWidth <= 0 || mainHeight <= 0) return null
        val m = android.graphics.Matrix()
        tv.getTransform(m)
        val inv = android.graphics.Matrix()
        if (!m.invert(inv)) return null
        val pts = floatArrayOf(viewX, viewY)
        inv.mapPoints(pts)
        if (pts[0] < 0f || pts[0] > vw || pts[1] < 0f || pts[1] > vh) return null
        val mainX = (pts[0] * mainWidth / vw).coerceIn(0f, (mainWidth - 1).toFloat())
        val mainY = (pts[1] * mainHeight / vh).coerceIn(0f, (mainHeight - 1).toFloat())
        return Pair(mainX, mainY)
    }

    // vc169 OVERLAY LIVE TOUCH (9Flip u2/f4 model, own code):
    // same machine as MirrorPresentation's vc103/vc104 path,
    // minus pinch (overlay feed is single-pointer like 9Flip
    // u2) and minus the icon column. One instance per overlay
    // creation, so its state never leaks across sessions.
    private inner class OverlayTouchForwarder {
        private var active = false
        private var liveStarted = false
        private var multiFlag = false
        private var longPressFlag = false
        private var doubleTapCandidate = false
        private var replayMode = false
        private var lastUpMs = 0L
        private var slop = 0f
        private var lastViewX = 0f
        private var lastViewY = 0f
        private var totalMove = 0f
        private var lastMainX = 0f
        private var lastMainY = 0f
        private val pointList = ArrayList<Pair<Float, Float>>()
        private val chunkPoints = ArrayDeque<Pair<Float, Float>>()
        private var streamStroke: GestureDescription.StrokeDescription? = null
        private var streamLastX = 0f
        private var streamLastY = 0f
        private var streamInFlight = false
        private var pendingFinal = false
        private var generation = 0
        private var lastDispatchMs = 0L
        private var loopRunning = false
        private val preparedQueue = ArrayDeque<GestureDescription>()
        private var preparedInFlight = false

        // vc102 explicit type: a Runnable that posts itself
        // inside its own initializer must not infer its type
        // from itself (Kotlin recursive-type error, vc92/vc101).
        private val loopRunnable: Runnable = Runnable {
            loopRunning = false
            if (!active) return@Runnable
            if (chunkPoints.isNotEmpty() && !streamInFlight) {
                dispatchNextChunk()
            } else if (liveStarted && chunkPoints.isEmpty() && !pendingFinal) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastDispatchMs > 1000L && streamStroke != null && !streamInFlight) {
                    dispatchSegment(streamLastX, streamLastY, true)
                }
            }
            if (active) {
                loopRunning = true
                handler.postDelayed(loopRunnable, 25L)
            }
        }
        private fun startLoop() {
            if (!loopRunning) {
                loopRunning = true
                handler.postDelayed(loopRunnable, 25L)
            }
        }
        private fun stopLoop() {
            loopRunning = false
            handler.removeCallbacks(loopRunnable)
        }

        private val longPressRunnable: Runnable = Runnable {
            if (!active || multiFlag || liveStarted) return@Runnable
            if (totalMove > slop) return@Runnable
            val first = pointList.firstOrNull() ?: return@Runnable
            longPressFlag = true
            val timeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
            val path = Path(); path.moveTo(first.first, first.second)
            val gesture = GestureDescription.Builder().setDisplayId(0)
                .addStroke(GestureDescription.StrokeDescription(path, 0L, timeout + 80L))
                .build()
            dispatchPrepared(gesture)
        }

        private fun dispatchPrepared(gesture: GestureDescription) {
            val acc = MyAccessibilityService.instance ?: return
            if (streamStroke != null || streamInFlight || preparedInFlight) {
                android.util.Log.i("CFlipMirror", "OV PREP queued streamStroke=" + (streamStroke != null) + " inFlight=" + streamInFlight + " prepInFlight=" + preparedInFlight)
                if (preparedQueue.size >= 12) preparedQueue.removeFirst()
                preparedQueue.addLast(gesture)
                return
            }
            preparedInFlight = true
            val ok = acc.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                android.util.Log.i("CFlipMirror", "OV PREP_CB completed")
                    preparedInFlight = false
                    dispatchNextPrepared()
                }
                override fun onCancelled(g: GestureDescription?) {
                android.util.Log.i("CFlipMirror", "OV PREP_CB cancelled")
                    preparedInFlight = false
                    dispatchNextPrepared()
                }
            }, handler)
            android.util.Log.i("CFlipMirror", "OV PREP dispatched ok=" + ok)
            if (!ok) {
                preparedInFlight = false
                dispatchNextPrepared()
            }
        }
        private fun dispatchNextPrepared() {
            if (preparedInFlight) return
            if (streamStroke != null || streamInFlight) return
            val next = preparedQueue.removeFirstOrNull() ?: return
            dispatchPrepared(next)
        }

        private fun dispatchSegment(toX: Float, toY: Float, willContinue: Boolean) {
            dispatchChunk(listOf(Pair(toX, toY)), willContinue)
        }

        // vc177: 1px away from streamLast, inside source
        // bounds, so a stop-then-release final is never
        // a zero-length segment.
        private fun nudgedFinalPoint(): Pair<Float, Float> {
            if (streamLastY < mainHeight - 1) return Pair(streamLastX, streamLastY + 1f)
            if (streamLastY > 0) return Pair(streamLastX, streamLastY - 1f)
            if (streamLastX < mainWidth - 1) return Pair(streamLastX + 1f, streamLastY)
            return Pair(streamLastX - 1f, streamLastY)
        }
        private fun dispatchChunk(points: List<Pair<Float, Float>>, willContinue: Boolean) {
            if (points.isEmpty()) return
            val acc = MyAccessibilityService.instance ?: return
        // vc177 LOG-PROVEN (vc176 log 30/10): same as
        // Presentation - the system cancels a zero-length
        // continued stroke within ~2ms, the cancel clears
        // state, the later UP is ignored, no final is
        // sent, and the shade hangs. Never dispatch a
        // zero-length continuation; nudge a
        // stop-then-release final by 1px.
        var effPoints = points
        // vc178: same as Presentation - vc177 log
        // 04:02 30/10 shows a 0.58px creep segment
        // cancelled like vc176 duplicates. Drop any
        // single-point non-final segment moving <1px
        // from streamLast; nudge a final within <1px
        // to exactly 1px.
        if (effPoints.size == 1 && kotlin.math.hypot(effPoints[0].first - streamLastX, effPoints[0].second - streamLastY) < 1f) {
            if (!willContinue) {
                effPoints = listOf(nudgedFinalPoint())
                android.util.Log.i("CFlipMirror", "OV FINAL nudged to " + effPoints[0])
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
            lastDispatchMs = android.os.SystemClock.uptimeMillis()
            streamInFlight = true
            val gen = generation
            android.util.Log.i("CFlipMirror", "OV CHUNK willContinue=" + willContinue + " n=" + effPoints.size + " first=" + effPoints.first())
            val isFinalSegment = !willContinue
            val gesture = GestureDescription.Builder().setDisplayId(0).addStroke(stroke).build()
            val ok = acc.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                android.util.Log.i("CFlipMirror", "OV CHUNK_CB completed isFinal=" + isFinalSegment + " genOk=" + (gen == generation))
                    if (gen != generation) return
                    streamInFlight = false
                    if (isFinalSegment) {
                        streamStroke = null
                        replayMode = false
                        dispatchNextPrepared()
                        return
                    }
                    dispatchNextChunk()
                }
                override fun onCancelled(g: GestureDescription?) {
                android.util.Log.i("CFlipMirror", "OV CHUNK_CB cancelled")
                    if (gen != generation) return
                    streamInFlight = false
                    streamStroke = null
                    active = false
                    multiFlag = false
                    pendingFinal = false
                    chunkPoints.clear()
                    replayMode = false
                }
            }, handler)
            android.util.Log.i("CFlipMirror", "OV CHUNK dispatched ok=" + ok + " willContinue=" + willContinue)
            if (!ok) {
                android.util.Log.w("CFlipMirror", "dispatchGesture rejected, overlay touch stream dropped")
                streamInFlight = false
                streamStroke = null
                active = false
                pendingFinal = false
                chunkPoints.clear()
            }
        }
        private fun dispatchNextChunk() {
            if (chunkPoints.isEmpty()) {
                if (pendingFinal) {
                    pendingFinal = false
                    dispatchSegment(lastMainX, lastMainY, false)
                }
                return
            }
            if (replayMode) {
                val chunk = mutableListOf<Pair<Float, Float>>()
                while (chunk.size < 8 && chunkPoints.isNotEmpty()) {
                    chunk.add(chunkPoints.removeFirst())
                }
                val isFinal = pendingFinal && chunkPoints.isEmpty()
                if (isFinal) pendingFinal = false
                dispatchChunk(chunk, !isFinal)
                return
            }
            val last = chunkPoints.last()
            chunkPoints.clear()
            val isFinal = pendingFinal
            if (isFinal) pendingFinal = false
            dispatchChunk(listOf(last), !isFinal)
        }
        private fun queueSegment(toX: Float, toY: Float, final: Boolean) {
            if (final) pendingFinal = true
            android.util.Log.i("CFlipMirror", "OV QUEUE final=" + final + " pt=" + toX + "," + toY + " inFlight=" + streamInFlight)
            if (!final) {
                // Latest-only: overwrite the single queued point,
                // never replay a backlog (vc103 lesson).
                chunkPoints.clear()
                chunkPoints.addLast(Pair(toX, toY))
            } else {
                if (chunkPoints.isEmpty() || chunkPoints.last() != Pair(toX, toY)) {
                    chunkPoints.addLast(Pair(toX, toY))
                }
            }
            if (!streamInFlight) {
                dispatchNextChunk()
            }
        }
        private fun dispatchK4Replay() {
            val acc = MyAccessibilityService.instance ?: return
            if (pointList.size < 2) return
            val first = pointList.first()
            val remaining = pointList.drop(1)
            val timeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
            val holdPath = Path(); holdPath.moveTo(first.first, first.second)
            val holdStroke = GestureDescription.StrokeDescription(holdPath, 0L, timeout + 80L, true)
            streamStroke = holdStroke
            streamLastX = first.first; streamLastY = first.second
            streamInFlight = true
            replayMode = true
            pendingFinal = true
            chunkPoints.clear()
            remaining.forEach { chunkPoints.addLast(it) }
            val gesture = GestureDescription.Builder().setDisplayId(0).addStroke(holdStroke).build()
            val gen = generation
            val ok = acc.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) {
                    if (gen != generation) return
                    streamInFlight = false
                    dispatchNextChunk()
                }
                override fun onCancelled(g: GestureDescription?) {
                    android.util.Log.i("CFlipMirror", "OV K4_CB cancelled")
                    if (gen != generation) return
                    streamInFlight = false
                    streamStroke = null
                    replayMode = false
                    chunkPoints.clear()
                }
            }, handler)
            if (!ok) {
                android.util.Log.i("CFlipMirror", "OV K4 dispatched ok=false")
                streamInFlight = false
                streamStroke = null
                replayMode = false
                chunkPoints.clear()
            }
        }
        private fun dispatchU2Fallback() {
            val acc = MyAccessibilityService.instance ?: return
            val slopPx = android.view.ViewConfiguration.get(applicationContext).scaledTouchSlop.toFloat()
            if (totalMove <= slopPx || pointList.size < 2) {
                val pt = pointList.lastOrNull() ?: return
                val path = Path()
                path.moveTo(pt.first, pt.second)
                path.lineTo(pt.first, pt.second)
                val gesture = GestureDescription.Builder().setDisplayId(0)
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 42L))
                    .build()
                dispatchPrepared(gesture)
                return
            }
            var dist = 0.0
            for (i in 1 until pointList.size) {
                val a = pointList[i - 1]; val b = pointList[i]
                dist += kotlin.math.hypot(b.first - a.first, b.second - a.second)
            }
            val duration = dist.toLong().let { (it / 2.4).toLong() }.coerceIn(120L, 650L)
            val path = Path()
            val first = pointList.first()
            path.moveTo(first.first, first.second)
            for (i in 1 until pointList.size) {
                val pt = pointList[i]
                path.lineTo(pt.first, pt.second)
            }
            val gesture = GestureDescription.Builder().setDisplayId(0)
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()
            dispatchPrepared(gesture)
        }

        fun onTouch(tv: TextureView, event: MotionEvent): Boolean {
            // vc170 FIX (vc169 build break, mine): this when is a
            // statement, not the function's return value. The
            // listener lambda in MirrorPresentation returns its
            // when implicitly, but a named function must return
            // it explicitly, or Kotlin reports Missing return.
            return when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    runCatching { tv.parent?.requestDisallowInterceptTouchEvent(true) }
                    val main = overlayViewToMain(event.x, event.y, tv)
                    android.util.Log.i("CFlipMirror", "OV DOWN view=" + event.x + "," + event.y + " main=" + main)
                    if (main == null) return false
                    lastMainX = main.first; lastMainY = main.second
                    lastViewX = event.x; lastViewY = event.y
                    totalMove = 0f
                    liveStarted = false
                    slop = android.view.ViewConfiguration.get(applicationContext).scaledTouchSlop.toFloat()
                    val dt = event.eventTime - lastUpMs
                    doubleTapCandidate = dt in 1..android.view.ViewConfiguration.getDoubleTapTimeout().toLong()
                    longPressFlag = false
                    multiFlag = false
                    replayMode = false
                    pointList.clear()
                    pointList.add(Pair(main.first, main.second))
                    active = true
                    streamStroke = null
                    streamLastX = main.first; streamLastY = main.second
                    streamInFlight = false
                    pendingFinal = false
                    chunkPoints.clear()
                    generation++
                    handler.removeCallbacks(longPressRunnable)
                    handler.postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 9Flip u2: second finger cancels the live
                    // stream; the whole gesture is dead until
                    // the next DOWN (UP with multi set resets).
                    if (!active) return true
                    handler.removeCallbacks(longPressRunnable)
                    multiFlag = true
                    longPressFlag = false
                    doubleTapCandidate = false
                    if (liveStarted) queueSegment(lastMainX, lastMainY, true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!active) return false
                    if (multiFlag || event.pointerCount != 1) return true
                    totalMove += kotlin.math.hypot(event.x - lastViewX, event.y - lastViewY)
                    lastViewX = event.x; lastViewY = event.y
                    if (totalMove > slop) {
                        handler.removeCallbacks(longPressRunnable)
                    }
                    val main = overlayViewToMain(event.x, event.y, tv)
                    if (main != null) {
                        lastMainX = main.first; lastMainY = main.second
                        if (pointList.size >= 96) pointList.removeAt(1)
                        pointList.add(Pair(main.first, main.second))
                        if (!liveStarted && !longPressFlag && totalMove > slop) {
                            liveStarted = true
                            startLoop()
                            val first = pointList.first()
                            queueSegment(first.first, first.second, false)
                        } else if (liveStarted) {
                            queueSegment(main.first, main.second, false)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_POINTER_UP -> true
                MotionEvent.ACTION_UP -> {
                    if (!active) return false
                    if (multiFlag) {
                        // Cancelled gesture: reset, no tap/drag.
                        handler.removeCallbacks(longPressRunnable)
                        active = false
                        liveStarted = false
                        longPressFlag = false
                        multiFlag = false
                        doubleTapCandidate = false
                        totalMove = 0f
                        pointList.clear()
                        stopLoop()
                        return true
                    }
                    handler.removeCallbacks(longPressRunnable)
                    val main = overlayViewToMain(event.x, event.y, tv)
                    if (main != null) {
                        lastMainX = main.first; lastMainY = main.second
                        if (pointList.size >= 96) pointList.removeAt(1)
                        pointList.add(Pair(main.first, main.second))
                    }
                    if (liveStarted) {
                        queueSegment(lastMainX, lastMainY, true)
                        if (longPressFlag || totalMove > slop) {
                            lastUpMs = 0L
                        } else {
                            lastUpMs = event.eventTime
                        }
                        if (chunkPoints.isNotEmpty() && !streamInFlight) dispatchNextChunk()
                    } else if (longPressFlag) {
                        lastUpMs = 0L
                    } else if (doubleTapCandidate && totalMove > slop && pointList.size >= 2) {
                        dispatchK4Replay()
                        lastUpMs = 0L
                    } else if (totalMove <= slop) {
                        val pt = pointList.lastOrNull()
                        if (pt != null) {
                            val path = Path(); path.moveTo(pt.first, pt.second); path.lineTo(pt.first, pt.second)
                            val gesture = GestureDescription.Builder().setDisplayId(0)
                                .addStroke(GestureDescription.StrokeDescription(path, 0L, 42L))
                                .build()
                            dispatchPrepared(gesture)
                        }
                        lastUpMs = event.eventTime
                    } else if (pointList.size >= 2) {
                        dispatchU2Fallback()
                        lastUpMs = 0L
                    } else {
                        val pt = pointList.lastOrNull()
                        if (pt != null) {
                            val path = Path(); path.moveTo(pt.first, pt.second); path.lineTo(pt.first, pt.second)
                            val gesture = GestureDescription.Builder().setDisplayId(0)
                                .addStroke(GestureDescription.StrokeDescription(path, 0L, 42L))
                                .build()
                            dispatchPrepared(gesture)
                        }
                        lastUpMs = event.eventTime
                    }
                    active = false
                    liveStarted = false
                    longPressFlag = false
                    multiFlag = false
                    doubleTapCandidate = false
                    totalMove = 0f
                    pointList.clear()
                    stopLoop()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    // vc179 diagnostic only: this branch had no log.
                    android.util.Log.i("CFlipMirror", "OV COVER_CANCEL liveStarted=" + liveStarted + " active=" + active)
                    handler.removeCallbacks(longPressRunnable)
                    if (active && liveStarted) queueSegment(lastMainX, lastMainY, true)
                    active = false
                    liveStarted = false
                    longPressFlag = false
                    multiFlag = false
                    doubleTapCandidate = false
                    totalMove = 0f
                    pointList.clear()
                    stopLoop()
                    true
                }
                else -> false
            }
        }
    }

    // vc62 DISPLAY LAYER (9Flip i4.a fit mode, same math as
    // MirrorPresentation.applyTransform, for the overlay path):
    // Old overlay had NO transform, so it stretched like the
    // old Presentation. Fit preserves aspect with black bars.
    private fun applyFitTransform(tv: TextureView, viewW: Int, viewH: Int) {
        if (viewW <= 0 || viewH <= 0 || mainWidth <= 0 || mainHeight <= 0) return
        // vc63 FIT FIX (same as Presentation): view-space scale,
        // not buffer-space scale. See MirrorPresentation for the
        // full explanation - vc62 used 0.283 directly and got a
        // 204x211 feed at the top.
        val f10 = viewW.toFloat() / mainWidth
        val f12 = viewH.toFloat() / mainHeight
        val fMin = kotlin.math.min(f10, f12)
        val sx = fMin / f10
        val sy = fMin / f12
        val dx = (viewW - viewW * sx) / 2f
        val dy = (viewH - viewH * sy) / 2f
        val m = android.graphics.Matrix()
        m.setScale(sx, sy)
        m.postTranslate(dx, dy)
        // vc79: same content rotation as Presentation, so the
        // fallback overlay does not show an unrotated feed when
        // the Presentation path falls back to it. 180° preserves
        // the centered fit exactly; 90°/270° may crop a few px
        // on the near-square cover (rotated fit is slightly wider
        // than the view), which is still far better than sideways.
        if (mirrorContentDeg != 0) {
            m.postRotate(mirrorContentDeg.toFloat(), viewW / 2f, viewH / 2f)
        }
        tv.setTransform(m)
    }

    private fun createVirtualDisplay(proj: MediaProjection, surface: Surface) {
        // vc80 REVERT vc79 PUBLIC: flags 17 (PUBLIC + AUTO_MIRROR)
        // correlated with vc79 mirror disconnect + second-attempt
        // crash, and PowerFlip's 17 is proven only from an
        // Activity context, not from a service. Back to the
        // vc62-proven AUTO_MIRROR (16) only, which did show a
        // feed. Content rotation (vc79) is kept; only the
        // unproven flag is removed, so vc80 isolates the fix
        // to the fighting-hosts root cause.
        // vc169: a dead projection used to throw out of here and
        // kill the service process. Now it becomes a failure
        // pill + stop, like every other mirror failure path.
        runCatching {
            virtualDisplay = proj.createVirtualDisplay(
                "CFlipMirror", mainWidth, mainHeight, mainDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, handler,
            )
        }.onSuccess {
            Log.i(TAG, "Virtual display created ${mainWidth}x${mainHeight} @${mainDpi}dpi (source size, flags AUTO_MIRROR=16)")
        }.onFailure { e ->
            Log.w(TAG, "Virtual display create failed", e)
            virtualDisplay = null
            val sec = DisplayRepository(this).secondaryDisplay()
            if (sec != null) showFailurePillAndStop(sec, "Mirror failed: virtual display error")
            else stopSelf()
        }
    }

    // vc66 FAILURE PILL: post-gate failures used to stopSelf()
    // silently (log only), so a folded user saw "activating
    // ok, then disconnected, nothing shown" with no reason.
    // Disable dual first (pill shows in normal cover mode,
    // where overlays work, not hidden dual mode), show the
    // exact reason on the cover for 5s, then stop. If the pill
    // cannot be added, stop immediately.
    private fun showFailurePillAndStop(secondary: Display, reason: String) {
        runCatching { DualDisplayHelper.disable() }
        // vc89 PILL FIX: never stack a new pill over an old one -
        // stale pills from a previous session were the "thong bao
        // khong bien mat" in the vc88 screenshot.
        removeFailurePill()
        val added = runCatching {
            val pillCtx = createDisplayContext(secondary)
            val pillWm = pillCtx.getSystemService(WindowManager::class.java) ?: return@runCatching false
            val pill = android.widget.TextView(pillCtx).apply {
                this.text = reason
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.BLACK)
                textSize = 14f
                setPadding(24, 24, 24, 24)
            }
            val pillParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
            pillWm.addView(pill, pillParams)
            failurePillView = pill; failurePillWm = pillWm
            handler.postDelayed({ removeFailurePill(); stopSelf() }, 5000)
            true
        }.getOrDefault(false)
        if (!added) { stopSelf() }
    }

    private fun cleanupSession() {
        // vc169: invalidate any late surface callback from the
        // session being torn down, and stop source tracking.
        mirrorGeneration++
        runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(mirrorDisplayListener) }
        runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(mirrorSourceListener) }
        mirrorSecondaryId = -1
        removeFailurePill()
        // vc62 SECOND-START FIX: cancel any pending handler
        // callbacks from the previous session (notably the vc59
        // 3s Presentation->overlay fallback timer). Without this,
        // a timer posted by session 1 can fire during session 2
        // and dismiss session 2's Presentation while its surface
        // is still attaching, making the second mirror look dead.
        handler.removeCallbacksAndMessages(null)
        // vc45 restore: previously cleanup only set active=false
        // and dismissed the presentation, but never told
        // CoverForegroundService to show the bubble again. After
        // a mirror session ended (stop button, system stop, or
        // service death), the bubble stayed gone until the next
        // display/hinge event - the second half of "mất luôn
        // AssistiveTouch". Now cleanup always asks the foreground
        // service to re-show the cover UI (its tryShowOnSecondary
        // checks active==false first, so this cannot re-hide).
        mirrorSurface = null
        mirrorTexture = null
        virtualDisplay?.release(); virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null
        // vc47: remove the cover overlay (Presentation dismiss
        // is gone with the Presentation route).
        runCatching {
            val v = overlayView; val w = overlayWm
            if (v != null && w != null) w.removeView(v)
        }
        overlayView = null; overlayWm = null
        // vc73: remove mirror's own rotate host (added in
        // startMirroring). Must go before foreground service
        // re-shows cover UI below, so normal RotateHost can
        // return cleanly without a duplicate host.
        runCatching {
            val rv = mirrorRotateView; val rw = mirrorRotateWm
            if (rv != null && rw != null) rw.removeView(rv)
        }
        mirrorRotateView = null; mirrorRotateWm = null
        // vc58: dismiss Presentation host if it was the active path.
        runCatching { mirrorPresentation?.dismiss() }
        mirrorPresentation = null
        MirrorState.active = false
        // vc94: tell MainHost to finish at once (see its
        // stopReceiver) so its KEEP_SCREEN_ON on main is
        // released at stop time, not on the next 1s poll.
        runCatching {
            sendBroadcast(Intent(com.cuong.cflip.mirror.MainHostActivity.ACTION_MIRROR_STOP))
        }
        runCatching {
            startService(Intent(this, CoverForegroundService::class.java).apply {
                action = CoverForegroundService.ACTION_ROTATION_CHANGED
            })
        }
    }

    // vc103: lock via accessibility. Returns
    // false if the service is not running, in
    // which case stop falls back to vc94 behaviour
    // (MainHost finishes, main follows its timeout).
    private fun lockDeviceOnStop(): Boolean {
        return try {
            val acc = MyAccessibilityService.instance
            if (acc == null) {
                Log.w(TAG, "Mirror stop lock skipped: accessibility service not running (vc103)")
                false
            } else {
                val ok = acc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                Log.i(TAG, "Mirror stop lock via accessibility, ok=$ok (vc103)")
                ok
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mirror stop lock failed (vc103)", e)
            false
        }
    }

    private fun cleanupAndDisableDual() {
        // vc63 STOP ORDER: vc62 did cleanup (which re-shows the
        // bubble) BEFORE disabling dual, so the bubble re-show ran
        // while dual was still ON, when cover overlays are hidden
        // in dual mode, and the bubble never came back until the
        // user restarted AssistiveTouch. Disable dual first, then
        // cleanup re-shows the bubble in normal mode.
        runCatching { DualDisplayHelper.disable() }
        cleanupSession()
    }

    override fun onDestroy() { cleanupAndDisableDual(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "CFlipMirror"
        const val ACTION_START = "com.cuong.cflip.mirror.START"
        const val ACTION_STOP = "com.cuong.cflip.mirror.STOP"
        const val ACTION_APPLY_ROTATION = "com.cuong.cflip.mirror.APPLY_ROTATION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
    }
}


/**
 * vc51 Dual Display toggle, following 9Flip's mechanism in
 * principle (own code, not a copy of its obfuscated helpers).
 * 9Flip's decompile shows: dual ON = DeviceStateManager
 * ("device_state" Binder service) transact 3 with payload
 * [Binder token, int 4, int 20]; dual OFF = transact 4 with
 * empty payload (just the interface token), falling back to
 * Samsung SemWindowManager.setForcedDefaultDisplayDevice(0)
 * to restore the main display as default. The Binder token is
 * held while mirror is active; transact 4 is the explicit
 * cancel, token release alone does not cancel the override
 * (the system holds the token until cancelled or the process
 * dies). All calls are best-effort and wrapped: if Samsung
 * blocks the private API on One UI 9, enable() returns the
 * exact failure instead of silently pretending dual is on.
 */
object DualDisplayHelper {
    private var token: Binder? = null

    // vc65 FOLDED GATE: the service checks dual via this, not
    // via main display STATE_ON. Folded main is OFF by
    // definition, so a main-ON gate can never pass folded.
    fun isDualOn(): Boolean = token != null

    fun enable(): Result<String> = runCatching {
        if (token != null) return@runCatching "dual already on"
        // vc57 9FLIP ORDER: 9Flip h() calls SemWindowManager
        // .getInstance() and checks it is non-null BEFORE
        // transact 3. CFlip vc51-vc56 went straight to transact,
        // so a missing/blocked SemWindowManager could leave
        // transact silently ignored while enable() still
        // reported success, and the mirror then failed later
        // at the service gate with no clear cause. Now a null
        // SemWindowManager fails visibly here, in the panel
        // toast, instead of pretending dual is on.
        runCatching {
            val semCls = Class.forName("com.samsung.android.view.SemWindowManager")
            val semInst = semCls.getMethod("getInstance").invoke(null)
                ?: throw IllegalStateException("SemWindowManager.getInstance() returned null")
        }.getOrThrow()
        val smClass = Class.forName("android.os.ServiceManager")
        val getService = smClass.getMethod("getService", String::class.java)
        val binder = getService.invoke(null, "device_state") as? IBinder
            ?: throw IllegalStateException("device_state service unavailable")
        val tok = Binder()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("android.hardware.devicestate.IDeviceStateManager")
            data.writeStrongBinder(tok)
            data.writeInt(4)
            data.writeInt(20)
            if (!binder.transact(3, data, reply, 0)) {
                throw IllegalStateException("DeviceState transact 3 not handled")
            }
            reply.readException()
        } finally {
            data.recycle(); reply.recycle()
        }
        token = tok
        "dual display requested (state 4, flags 20)"
    }

    fun disable(): Result<String> = runCatching {
        token = null
        // Best-effort OFF: transact 4 first, then SemWindowManager
        // restore to main (0). Either failing is caught and
        // ignored here; the caller still stops the mirror.
        runCatching {
            val smClass = Class.forName("android.os.ServiceManager")
            val getService = smClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "device_state") as? IBinder
                ?: throw IllegalStateException("device_state unavailable")
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken("android.hardware.devicestate.IDeviceStateManager")
                if (!binder.transact(4, data, reply, 0)) {
                    throw IllegalStateException("transact 4 not handled")
                }
                reply.readException()
            } finally { data.recycle(); reply.recycle() }
        }
        runCatching {
            val cls = Class.forName("com.samsung.android.view.SemWindowManager")
            val inst = cls.getMethod("getInstance").invoke(null)
                ?: throw IllegalStateException("SemWindowManager null")
            cls.getMethod("setForcedDefaultDisplayDevice", Int::class.javaPrimitiveType).invoke(inst, 0)
        }
        "dual display off requested"
    }
}
