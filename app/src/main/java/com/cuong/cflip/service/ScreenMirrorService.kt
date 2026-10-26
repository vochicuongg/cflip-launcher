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
    private var mainWidth: Int = 0
    private var mainHeight: Int = 0
    private var mainDpi: Int = 0
    private val handler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "Projection stopped by system/user")
            cleanupAndDisableDual()
            stopSelf()
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
        if (mainGate == null || mainGate.state != Display.STATE_ON || secondary.state != Display.STATE_ON || !isCoverSized || secondary.name == "CFlipMirror") {
            Log.w(TAG, "Mirror gate refused: mainState=${mainGate?.state} coverState=${secondary.state} cover=${secW}x$secH name=${secondary.name}")
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
            android.widget.Toast.makeText(applicationContext, "Ph\u1ea3n chi\u1ebfu \u0111\u00e3 d\u1eebng: c\u1ea7n M\u1ede M\u00e1y (m\u00e0n ch\u00ednh b\u1eadt) v\u00e0 m\u00e0n ph\u1ee5 th\u1eadt, kh\u00f4ng ph\u1ea3n chi\u1ebfu khi g\u1eadp m\u00e1y.", android.widget.Toast.LENGTH_LONG).show()
            runCatching {
                val coverCtx = createDisplayContext(secondary)
                android.widget.Toast.makeText(coverCtx, "Ph\u1ea3n chi\u1ebfu \u0111\u00e3 d\u1eebng: c\u1ea7n M\u1ede M\u00e1y (m\u00e0n ch\u00ednh b\u1eadt) v\u00e0 m\u00e0n ph\u1ee5 th\u1eadt.", android.widget.Toast.LENGTH_LONG).show()
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
                    text = "Mirror stopped: main must be ON + cover must be ON (open phone)"
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
        if (proj == null) { Log.w(TAG, "getMediaProjection returned null"); stopSelf(); return }
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
        val presTried = runCatching {
            val acc = MyAccessibilityService.instance
            val presCtx = if (acc != null) acc.createDisplayContext(secondary) else coverCtx
            val pres = com.cuong.cflip.mirror.MirrorPresentation(presCtx, secondary).apply {
                sourceW = mainWidth; sourceH = mainHeight
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
            pres.show()
            mirrorPresentation = pres
            MirrorState.active = true
            pres.isShowing
        }.getOrDefault(false)
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
                    createOverlayMirror(proj, secondary, coverCtx)
                }
            }, 3000)
            return
        }
        Log.i(TAG, "Mirror Presentation not shown, falling back to overlay")
        // vc47 overlay: cover display context + overlay window
        // (same route as bubble/panel, which Samsung DOES show
        // on this cover). The TextureView fills the cover; its
        // buffer is the main size, TextureView scales it down.
        createOverlayMirror(proj, secondary, coverCtx)
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
        if (wm == null) { Log.w(TAG, "No WindowManager for cover display"); stopSelf(); return }
        val root = FrameLayout(coverCtx)
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
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                st.setDefaultBufferSize(mainWidth, mainHeight)
                val surface = Surface(st)
                mirrorSurface = surface
                handler.post { statusView.text = "Mirror: surface ready, virtual display starting..." }
                createVirtualDisplay(proj, surface)
                handler.post { statusView.text = "Mirror: virtual display OK ${mainWidth}x${mainHeight} - feed should show" }
                Log.i(TAG, "Mirror overlay surface ready, virtual display started")
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                mirrorSurface = null
                virtualDisplay?.release(); virtualDisplay = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(root, params) }
            .onSuccess {
                overlayView = root; overlayWm = wm; mirrorTexture = tv
                MirrorState.active = true
                statusView.text = "Mirror: overlay OK, waiting surface..."
                Log.i(TAG, "Mirror overlay added on cover display ${secondary.displayId}")
            }
            .onFailure { Log.w(TAG, "System refused mirror overlay on cover", it); stopSelf() }
    }

    private fun createVirtualDisplay(proj: MediaProjection, surface: Surface) {
        virtualDisplay = proj.createVirtualDisplay(
            "CFlipMirror", mainWidth, mainHeight, mainDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, handler,
        )
        Log.i(TAG, "Virtual display created ${mainWidth}x${mainHeight} @${mainDpi}dpi (source size)")
    }

    private fun cleanupSession() {
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
        // vc58: dismiss Presentation host if it was the active path.
        runCatching { mirrorPresentation?.dismiss() }
        mirrorPresentation = null
        MirrorState.active = false
        runCatching {
            startService(Intent(this, CoverForegroundService::class.java).apply {
                action = CoverForegroundService.ACTION_ROTATION_CHANGED
            })
        }
    }

    private fun cleanupAndDisableDual() {
        cleanupSession()
        runCatching { DualDisplayHelper.disable() }
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
