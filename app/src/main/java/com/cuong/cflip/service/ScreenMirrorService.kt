package com.cuong.cflip.service

import android.app.Notification
import android.app.Service
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
import android.os.IBinder
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
            android.widget.Toast.makeText(applicationContext, "Ph\u1ea3n chi\u1ebfu \u0111\u00e3 d\u1eebng: c\u1ea7n M\u1ede M\u00e1y (m\u00e0n ch\u00ednh b\u1eadt) v\u00e0 m\u00e0n ph\u1ee5 th\u1eadt, kh\u00f4ng ph\u1ea3n chi\u1ebfu khi g\u1eadp m\u00e1y.", android.widget.Toast.LENGTH_LONG).show()
            runCatching { DualDisplayHelper.disable() }
            stopSelf(); return
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
        val dm = getSystemService(DisplayManager::class.java)
        val main = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val pt = Point()
        @Suppress("DEPRECATION") main?.getSize(pt)
        mainWidth = pt.x.coerceAtLeast(1)
        mainHeight = pt.y.coerceAtLeast(1)
        mainDpi = if (main != null) createDisplayContext(main).resources.displayMetrics.densityDpi else 320
        Log.i(TAG, "Main display source size ${mainWidth}x$mainHeight @${mainDpi}dpi, cover id ${secondary.displayId}")

        // vc47 overlay: cover display context + overlay window
        // (same route as bubble/panel, which Samsung DOES show
        // on this cover). The TextureView fills the cover; its
        // buffer is the main size, TextureView scales it down.
        val coverCtx = createDisplayContext(secondary)
        val wm = coverCtx.getSystemService(WindowManager::class.java)
        if (wm == null) { Log.w(TAG, "No WindowManager for cover display"); stopSelf(); return }
        val root = FrameLayout(coverCtx)
        val tv = TextureView(coverCtx)
        root.addView(tv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val stopBtn = Button(coverCtx).apply {
            text = "Dừng phản chiếu"
            setOnClickListener { cleanup(); stopSelf() }
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
                createVirtualDisplay(proj, surface)
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
