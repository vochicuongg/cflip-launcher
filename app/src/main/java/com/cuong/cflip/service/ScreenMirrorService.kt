package com.cuong.cflip.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.cuong.cflip.CFlipApp
import com.cuong.cflip.R
import com.cuong.cflip.data.SettingsStore
import com.cuong.cflip.display.DisplayRepository
import com.cuong.cflip.mirror.MirrorPresentation
import com.cuong.cflip.mirror.MirrorState

/**
 * Mirrors the main screen onto the cover display.
 *
 * Flow (Android rules, cannot be shortcut): the user approves the system
 * screen-capture dialog in MainActivity's flow, this media-projection
 * foreground service takes the one-shot result, creates a virtual display
 * with VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR whose surface is the cover
 * Presentation's TextureView - so the main screen is drawn live on the cover.
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
    private var presentation: MirrorPresentation? = null
    private var mirrorSurface: Surface? = null
    private val handler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "Projection stopped by system/user")
            cleanup()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { cleanup(); stopSelf(); return START_NOT_STICKY }
            ACTION_APPLY_ROTATION -> {
                // Old bug: only the TextureView transform changed, but the
                // virtual display kept its old (portrait/landscape) size, so
                // the rotated feed was squashed. Recreate the virtual display
                // at the new buffer size on the same surface/projection.
                val pres = presentation
                val proj = projection
                val surface = mirrorSurface
                if (pres != null && proj != null && surface != null) {
                    pres.applyRotation(SettingsStore(this).load().coverRotation)
                    virtualDisplay?.release(); virtualDisplay = null
                    createVirtualDisplay(proj, pres, surface)
                } else {
                    pres?.applyRotation(SettingsStore(this).load().coverRotation)
                }
                return START_STICKY
            }
            ACTION_START -> startMirroring(intent)
        }
        return START_STICKY
    }

    private fun startMirroring(intent: Intent) {
        val notification: Notification = NotificationCompat.Builder(this, CFlipApp.CHANNEL_ID)
            .setContentTitle("CFlip Launcher")
            .setContentText("Đang phản chiếu màn hình chính lên cover")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
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

        cleanup() // in case of a restart, drop any previous session first

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm?.getMediaProjection(resultCode, resultData)
        val proj = projection
        if (proj == null) { Log.w(TAG, "getMediaProjection returned null"); stopSelf(); return }
        proj.registerCallback(projectionCallback, handler)

        val pres = MirrorPresentation(this, secondary)
        pres.applyRotation(SettingsStore(this).load().coverRotation)
        pres.onSurfaceReady = { surface -> mirrorSurface = surface; createVirtualDisplay(proj, pres, surface) }
        pres.onSurfaceGone = { mirrorSurface = null; virtualDisplay?.release(); virtualDisplay = null }
        runCatching { pres.show() }
            .onSuccess { presentation = pres; MirrorState.active = true
                Log.i(TAG, "Mirror presentation shown on display ${secondary.displayId}") }
            .onFailure { Log.w(TAG, "System refused mirror Presentation on cover", it); stopSelf() }
    }

    private fun createVirtualDisplay(proj: MediaProjection, pres: MirrorPresentation, surface: Surface) {
        val (bw, bh) = pres.bufferSize()
        val dpi = createDisplayContext(pres.display).resources.displayMetrics.densityDpi
        virtualDisplay = proj.createVirtualDisplay(
            "CFlipMirror", bw, bh, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface, null, handler,
        )
        Log.i(TAG, "Virtual display created ${bw}x$bh @${dpi}dpi")
    }

    private fun cleanup() {
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
        presentation?.dismiss(); presentation = null
        MirrorState.active = false
        runCatching {
            startService(Intent(this, CoverForegroundService::class.java).apply {
                action = CoverForegroundService.ACTION_ROTATION_CHANGED
            })
        }
    }

    override fun onDestroy() { cleanup(); super.onDestroy() }
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
