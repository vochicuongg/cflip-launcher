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
import android.graphics.PixelFormat
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.cuong.cflip.mirror.MirrorState
import com.cuong.cflip.mirror.MirrorActivity
import com.cuong.cflip.mirror.MirrorSurfaceRelay
import android.app.ActivityOptions

/**
 * vc187 ACTIVITY PORT (supersedes the vc47 overlay and vc58
 * Presentation window routes, both swept by the system on
 * the Settings trigger): the display-1 window is now a real
 * Activity (MirrorActivity), launched on the cover display
 * and handed its surface through MirrorSurfaceRelay. The
 * session core below (dual, projection, virtual display,
 * touch machine moved into the Activity) is unchanged.
 *
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
    private var mirrorSurface: Surface? = null
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
    // vc198: cover display id actually used for the Activity
    // launch (fresh at launch time, see below). -1 = not launched.
    private var launchedCoverId: Int = -1
    // vc199: one-shot display-0 transition wait for VD creation.
    private var vdWaitListener: DisplayManager.DisplayListener? = null
    private var vdCreated: Boolean = false
    private var failurePillView: View? = null
    private var failurePillWm: WindowManager? = null
    private val mirrorDisplayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != mirrorSecondaryId || !mirrorRotateFollow) return
            val sec = DisplayRepository(this@ScreenMirrorService).secondaryDisplay() ?: return
            mirrorContentDeg = computeContentDeg(sec)
            MirrorSurfaceRelay.activity?.applyRotation(mirrorContentDeg)
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
            MirrorSurfaceRelay.activity?.textureView?.surfaceTexture?.setDefaultBufferSize(w, h)
            val vd = virtualDisplay
            val surface = mirrorSurface
            if (vd != null && surface != null) {
                runCatching { vd.resize(w, h, dpi) }.onFailure {
                    Log.w(TAG, "Virtual display resize failed, recreating", it)
                    virtualDisplay?.release(); virtualDisplay = null
                    createVirtualDisplay(proj, surface)
                }
            }
            MirrorSurfaceRelay.activity?.let { act ->
                act.sourceW = w; act.sourceH = h
                act.applyRotation(mirrorContentDeg)
            }
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
        val notification: Notification = NotificationCompat.Builder(this, CFlipApp.CHANNEL_ID_MIRROR)
            .setContentTitle("CFlip Launcher")
            .setContentText("Đang phản chiếu màn hình chính lên cover")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
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
        // vc190 NO-DUAL GATE (PowerFlip parity): PowerFlip never
        // toggles dual, and CFlip's 1-2s dead touch tracked the dual
        // window (vc189's pre-launch delay did not fix it). Dual is
        // kept only as a log diagnostic — it no longer gates, and
        // the vc82 re-enable retry is deleted (it would reintroduce
        // the very window being removed).
        val dualOn = DualDisplayHelper.isDualOn()
        val gateFailed = (mainGate == null || secondary.state != Display.STATE_ON || !isCoverSized || secondary.name == "CFlipMirror")
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
            android.widget.Toast.makeText(applicationContext, "Phan chieu da dung: man phu khong san sang. dual=" + dualOn + " main=" + mainGate?.state + " cover=" + secondary.state, android.widget.Toast.LENGTH_LONG).show()
            runCatching {
                val coverCtx = createDisplayContext(secondary)
                android.widget.Toast.makeText(coverCtx, "Phan chieu da dung: man phu khong san sang. dual=" + dualOn + " main=" + mainGate?.state, android.widget.Toast.LENGTH_LONG).show()
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
                    text = "Mirror stopped: dual=$dualOn main=${mainGate?.state} cover=${secondary.state} ${secW}x$secH"
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
        // vc184: fresh session, stops are unintentional until proven otherwise.

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
        // only (the overlay fallback is gone in vc187).
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
        // vc187 ACTIVITY PORT (PowerFlip survival proof 30/10,
        // log-powerflip4.txt): a real Activity on display 1
        // survived the exact Settings trigger that sweeps
        // CFlip's Presentation window (channels gone in
        // ~100ms, focus(1) -> SubHome, no onStop). The
        // session core is unchanged (dual, projection,
        // virtual display, touch machine) - only the
        // display-1 window changes type. No overlay
        // fallback in v1: if the Activity surface never
        // arrives, the 3s watchdog below cleans up with
        // a failure pill instead of hiding behind a
        // window type that gets swept the same way.
        MirrorSurfaceRelay.sourceW = mainWidth
        MirrorSurfaceRelay.sourceH = mainHeight
        MirrorSurfaceRelay.windowOrientation = mirrorWinOrient
        MirrorSurfaceRelay.contentDeg = mirrorContentDeg
        MirrorSurfaceRelay.intentionalStop = false
        MirrorSurfaceRelay.onSurfaceReady = { surface ->
            if (sessionGen == mirrorGeneration) {
                mirrorSurface = surface
                // vc199 TRANSITION WAIT (PowerFlip parity) + VD-time
                // placement check: vc198's surface-time net did not
                // stop the tunnel, so the VD source must be decided
                // at creation time, not surface time. PowerFlip waits
                // for a display-0 state transition (1500ms fallback)
                // and then 150ms before creating its VD. CFlip waited
                // a fixed 1500ms from surface-ready regardless of
                // display state, so a VD created mid-transition can
                // latch onto the wrong source. Wait for the transition
                // (or the fallback), then check placement at the latest
                // moment (right before createVirtualDisplay) and only
                // then create the VD. Both paths funnel through
                // scheduleVdCreation exactly once (vdCreated guard).
                vdCreated = false
                val listener = object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(id: Int) {}
                    override fun onDisplayRemoved(id: Int) {}
                    override fun onDisplayChanged(id: Int) {
                        if (id == Display.DEFAULT_DISPLAY) scheduleVdCreation(sessionGen, proj, surface, secondary)
                    }
                }
                vdWaitListener = listener
                runCatching { getSystemService(DisplayManager::class.java)?.registerDisplayListener(listener, handler) }
                handler.postDelayed({ scheduleVdCreation(sessionGen, proj, surface, secondary) }, 1500)
            }
        }
        MirrorSurfaceRelay.onSurfaceGone = {
            if (sessionGen == mirrorGeneration) {
                mirrorSurface = null
                virtualDisplay?.release(); virtualDisplay = null
            }
        }
        MirrorSurfaceRelay.onControl = { action ->
            Log.i(TAG, "Mirror control tapped: $action")
            when (action) {
                "rotate" -> {
                    mirrorContentDeg = (mirrorContentDeg + 90) % 360
                    MirrorSurfaceRelay.activity?.applyRotation(mirrorContentDeg)
                }
                "zoom_in" -> MirrorSurfaceRelay.activity?.zoomIn()
                "zoom_out" -> MirrorSurfaceRelay.activity?.zoomOut()
            }
        }
        MirrorSurfaceRelay.onDestroyed = {
            if (sessionGen == mirrorGeneration && MirrorState.active && !MirrorSurfaceRelay.intentionalStop) {
                Log.i(TAG, "Mirror Activity destroyed unintentionally, cleaning up in 1s")
                handler.postDelayed({
                    if (sessionGen == mirrorGeneration && MirrorState.active && MirrorSurfaceRelay.activity == null) {
                        cleanupAndDisableDual()
                    }
                }, 1000)
            }
        }
        // vc187 BAL: a background service cannot reliably start an
        // Activity (background-activity-launch block). The
        // AccessibilityService is exempt, so launch through it
        // when present (PowerFlip launches from its own
        // accessibility service the same way); fall back to a
        // direct start, which the just-finished permission
        // activity's foreground grace window may still allow.
        // vc189 START DELAY (PowerFlip parity): PowerFlip waits 1000ms
        // after its trigger before launching the mirror Activity.
        // vc190 CORRECTION: this pre-launch delay did NOT fix the
        // 1-2s dead touch (user report on vc189) — the window tracks
        // Activity launch / dual, not pre-launch time. Kept for
        // PowerFlip parity (harmless); the real fixes are no-dual
        // (above) and the late feed (onSurfaceReady, vc190).
        // Guarded by generation: a cleanup during the delay bumps
        // mirrorGeneration and skips the launch (cleanup also
        // cancels this post via removeCallbacks).
        handler.postDelayed({
            if (sessionGen != mirrorGeneration) return@postDelayed
            val mirrorIntent = Intent(this, MirrorActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            // vc198 POWERFLIP LAUNCH GUARD (b4/l case 0):
            // PowerFlip looks display 1 up FRESH at launch time
            // and skips the launch when it is null, instead of
            // launching on a stale display id and landing on
            // main. CFlip used the id captured at service start,
            // 1000ms earlier. Refresh it here; a missing cover
            // aborts with a pill instead of a silent main
            // placement (which fed the VD back into itself =
            // the vc197 tunnel).
            val freshCover = DisplayRepository(this).secondaryDisplay()
            if (freshCover == null) {
                Log.w(TAG, "Mirror launch aborted: cover display gone at launch time")
                cleanupAndDisableDual()
                showFailurePillAndStop(secondary, "Mirror failed: cover display gone")
                return@postDelayed
            }
            launchedCoverId = freshCover.displayId
            val mirrorOpts = ActivityOptions.makeBasic().setLaunchDisplayId(freshCover.displayId)
            val mirrorAcc = MyAccessibilityService.instance
            val launched = runCatching {
                if (mirrorAcc != null) mirrorAcc.startActivity(mirrorIntent, mirrorOpts.toBundle())
                else startActivity(mirrorIntent, mirrorOpts.toBundle())
                true
            }.onFailure { Log.w(TAG, "Mirror Activity launch failed", it) }.getOrDefault(false)
            if (!launched) {
                showFailurePillAndStop(secondary, "Mirror failed: activity launch error")
                return@postDelayed
            }
            MirrorState.active = true
            hideCoverBubble()
            Log.i(TAG, "Mirror Activity launched on cover display ${freshCover.displayId}")
            // vc187 3s SURFACE WATCHDOG (vc59 shape, no overlay
            // fallback): a launched Activity with no surface in 3s
            // means the launch was dropped (BAL) or the surface
            // died - clean up and say why, never hang black.
            handler.postDelayed({
                if (mirrorSurface == null && sessionGen == mirrorGeneration && MirrorState.active) {
                    Log.i(TAG, "Mirror Activity surface not ready in 3s, cleaning up")
                    cleanupAndDisableDual()
                    showFailurePillAndStop(secondary, "Mirror failed: activity surface not ready in 3s")
                }
            }, 3000)
        }, 1000)
    }
    // vc199: single funnel for VD creation (display-0 transition
    // event or 1500ms fallback, whichever first). Unregisters the
    // wait listener, waits PowerFlip's extra 150ms, re-validates
    // the session, checks placement at the latest moment (an
    // Activity reparented to main after surface-ready would
    // otherwise slip past the surface-time net), then creates
    // the VD. If placement is wrong, refuse the VD with a pill
    // instead of showing another tunnel.
    private fun scheduleVdCreation(sessionGen: Int, proj: MediaProjection, surface: Surface, secondary: Display) {
        if (sessionGen != mirrorGeneration || vdCreated || !MirrorState.active) return
        vdCreated = true
        vdWaitListener?.let { l ->
            runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(l) }
        }
        vdWaitListener = null
        handler.postDelayed({
            if (sessionGen != mirrorGeneration || !MirrorState.active) return@postDelayed
            val actDisplayId = MirrorSurfaceRelay.activity?.display?.displayId
            if (launchedCoverId >= 0 && actDisplayId != null && actDisplayId != launchedCoverId) {
                Log.w(TAG, "Mirror Activity on wrong display $actDisplayId at VD time, expected cover $launchedCoverId - refusing VD (tunnel guard)")
                cleanupAndDisableDual()
                showFailurePillAndStop(secondary, "Mirror failed: activity on wrong display")
                return@postDelayed
            }
            createVirtualDisplay(proj, surface)
            Log.i(TAG, "Mirror virtual display started (vc199 transition wait)")
        }, 150)
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
        android.util.Log.i("CFlipMirror", "Mirror cleanupSession active=" + com.cuong.cflip.mirror.MirrorState.active)
        // vc184: every cleanup dismiss is intentional.
        // vc169: invalidate any late surface callback from the
        // session being torn down, and stop source tracking.
        mirrorGeneration++
        runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(mirrorDisplayListener) }
        runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(mirrorSourceListener) }
        vdWaitListener?.let { l -> runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(l) } }
        vdWaitListener = null
        vdCreated = false
        mirrorSecondaryId = -1
        launchedCoverId = -1
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
        virtualDisplay?.release(); virtualDisplay = null
        runCatching { projection?.stop() }
        projection = null
        // vc73: remove mirror's own rotate host (added in
        // startMirroring). Must go before foreground service
        // re-shows cover UI below, so normal RotateHost can
        // return cleanly without a duplicate host.
        runCatching {
            val rv = mirrorRotateView; val rw = mirrorRotateWm
            if (rv != null && rw != null) rw.removeView(rv)
        }
        mirrorRotateView = null; mirrorRotateWm = null
        // vc187: finish the mirror Activity host if it is the active path.
        // intentionalStop first, so its onDestroy does not fire the safety net.
        MirrorSurfaceRelay.intentionalStop = true
        runCatching { MirrorSurfaceRelay.activity?.finish() }
        MirrorSurfaceRelay.activity = null
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
        // vc187: drop the relay handoff for this session (a new
        // startMirroring re-fills it before launching again).
        MirrorSurfaceRelay.clear()
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
        // vc63 STOP ORDER: disable first, then cleanup re-shows
        // the bubble in normal mode (not hidden dual mode).
        // vc190: dual is no longer enabled by CFlip, so this
        // disable only clears a dual stuck on from an older session.
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
