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
    // vc63 TOUCH: cover -> main forwarding state for the overlay
    // path (Presentation path has its own copy in
    // MirrorPresentation). Same tap/swipe dispatch via
    // MyAccessibilityService.dispatchGesture.
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var touchLastX = 0f
    private var touchLastY = 0f
    private var touchStartTime = 0L
    private var touchActive = false
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
            ACTION_STOP -> { cleanupAndDisableDual(); stopSelf(); return START_NOT_STICKY }
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
                    mirrorSurface = surface
                    setStatus("Mirror: Presentation surface ready, virtual display starting...")
                    createVirtualDisplay(proj, surface)
                    setStatus("Mirror: virtual display OK ${mainWidth}x${mainHeight} - feed should show")
                    Log.i(TAG, "Mirror Presentation surface ready, virtual display started")
                }
                onSurfaceGone = {
                    mirrorSurface = null
                    virtualDisplay?.release(); virtualDisplay = null
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
                    runCatching { createOverlayMirror(proj, secondary, coverCtx) }
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
        runCatching { createOverlayMirror(proj, secondary, coverCtx) }
            .onFailure { Log.w(TAG, "Mirror overlay failed", it); showFailurePillAndStop(secondary, "Mirror failed: overlay error") }
    }

    // vc59 OVERLAY HELPER: extracted from startMirroring so
    // both the immediate fallback (Presentation not shown)
    // and the delayed fallback (Presentation shown but
    // surface never ready in 3s) can use the same overlay
    // path without duplicating the block. Params are the
    // projection, cover display and its display context;
    // main size / handler / mirror state are fields.
    private fun createOverlayMirror(proj: MediaProjection, secondary: Display, coverCtx: Context) {
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
        // vc63 TOUCH: forward overlay touches to main. Stop button
        // sits above this TextureView, so its taps still stop.
        tv.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val main = overlayViewToMain(event.x, event.y, tv) ?: return@setOnTouchListener false
                    touchStartX = main.first; touchStartY = main.second
                    touchLastX = main.first; touchLastY = main.second
                    touchStartTime = event.eventTime
                    touchActive = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!touchActive) return@setOnTouchListener false
                    val main = overlayViewToMain(event.x, event.y, tv)
                    if (main != null) { touchLastX = main.first; touchLastY = main.second }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!touchActive) return@setOnTouchListener false
                    touchActive = false
                    val main = overlayViewToMain(event.x, event.y, tv)
                    if (main != null) { touchLastX = main.first; touchLastY = main.second }
                    val durationMs = (event.eventTime - touchStartTime).coerceIn(50L, 800L)
                    dispatchOverlayGesture(durationMs)
                    true
                }
                MotionEvent.ACTION_CANCEL -> { touchActive = false; true }
                else -> false
            }
        }
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(mainWidth, mainHeight)
                applyFitTransform(tv, tv.width, tv.height)
                tv.post { applyFitTransform(tv, tv.width, tv.height) }
                val surface = Surface(st)
                mirrorSurface = surface
                handler.post { statusView.text = "Mirror: surface ready, virtual display starting..." }
                createVirtualDisplay(proj, surface)
                handler.post { statusView.text = "Mirror: virtual display OK ${mainWidth}x${mainHeight} - feed should show" }
                handler.postDelayed({ statusView.visibility = View.GONE }, 3000)
                Log.i(TAG, "Mirror overlay surface ready, virtual display started")
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) { applyFitTransform(tv, w, h) }
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                mirrorSurface = null
                virtualDisplay?.release(); virtualDisplay = null
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
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
            }
            .onFailure { e -> Log.w(TAG, "System refused mirror overlay on cover", e); showFailurePillAndStop(secondary, "Mirror failed: overlay refused: " + (e.message ?: "unknown")) }
    }

    private fun overlayViewToMain(viewX: Float, viewY: Float, tv: TextureView): Pair<Float, Float>? {
        val vw = tv.width.toFloat()
        val vh = tv.height.toFloat()
        if (vw <= 0f || vh <= 0f || mainWidth <= 0 || mainHeight <= 0) return null
        val f10 = vw / mainWidth
        val f12 = vh / mainHeight
        val fMin = kotlin.math.min(f10, f12)
        val sx = fMin / f10
        val sy = fMin / f12
        val dx = (vw - vw * sx) / 2f
        val dy = (vh - vh * sy) / 2f
        val scaledW = vw * sx
        val scaledH = vh * sy
        // vc79 TOUCH INVERSE: applyFitTransform rotates content
        // clockwise by mirrorContentDeg around the view center,
        // so a tap must be inverse-rotated back to unrotated fit
        // space before the fit check/mapping. 0° is unchanged
        // (exact old behavior); other angles fall back to
        // unrotated mapping (should not happen: service only
        // sets 0/90/180/270). 180° keeps the centered fit rect
        // intact; 90°/270° use the inverse-rotated point for the
        // rect check, which is the correct test.
        val cx = vw / 2f
        val cy = vh / 2f
        val (ux, uy) = when (mirrorContentDeg) {
            90 -> Pair(cx + (viewY - cy), cy - (viewX - cx))
            180 -> Pair(2f * cx - viewX, 2f * cy - viewY)
            270 -> Pair(cx - (viewY - cy), cy + (viewX - cx))
            else -> Pair(viewX, viewY)
        }
        if (ux < dx || ux > dx + scaledW || uy < dy || uy > dy + scaledH) return null
        val mainX = ((ux - dx) / fMin).coerceIn(0f, (mainWidth - 1).toFloat())
        val mainY = ((uy - dy) / fMin).coerceIn(0f, (mainHeight - 1).toFloat())
        return Pair(mainX, mainY)
    }

    private fun dispatchOverlayGesture(durationMs: Long) {
        val acc = MyAccessibilityService.instance ?: return
        val path = Path()
        path.moveTo(touchStartX, touchStartY)
        val dist = kotlin.math.hypot(touchLastX - touchStartX, touchLastY - touchStartY)
        if (dist < 1f) {
            path.lineTo(touchStartX + 1f, touchStartY)
        } else {
            path.lineTo(touchLastX, touchLastY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().setDisplayId(0).addStroke(stroke).build()
        acc.dispatchGesture(gesture, null, null)
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
        virtualDisplay = proj.createVirtualDisplay(
            "CFlipMirror", mainWidth, mainHeight, mainDpi,
            // vc80 REVERT vc79 PUBLIC: flags 17 (PUBLIC + AUTO_MIRROR)
            // correlated with vc79 mirror disconnect + second-attempt
            // crash, and PowerFlip's 17 is proven only from an
            // Activity context, not from a service. Back to the
            // vc62-proven AUTO_MIRROR (16) only, which did show a
            // feed. Content rotation (vc79) is kept; only the
            // unproven flag is removed, so vc80 isolates the fix
            // to the fighting-hosts root cause.
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, handler,
        )
        Log.i(TAG, "Virtual display created ${mainWidth}x${mainHeight} @${mainDpi}dpi (source size, flags AUTO_MIRROR=16)")
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
        runCatching { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(mirrorDisplayListener) }
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
