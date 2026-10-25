package com.cuong.cflip.service

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.core.app.NotificationCompat
import com.cuong.cflip.CFlipApp
import com.cuong.cflip.R
import com.cuong.cflip.cover.CoverPresentation
import com.cuong.cflip.display.DisplayRepository

/**
 * Keeps CFlip alive while folded and automatically shows the cover
 * Presentation when a secondary display appears, dismissing it when the
 * display goes away. On Samsung firmware the system may refuse the
 * Presentation - that is logged (tag CFlipFG) instead of crashing, and the
 * Good Lock / MultiStar widget remains the sanctioned fallback route.
 */
class CoverForegroundService : Service(), DisplayManager.DisplayListener,
    android.hardware.SensorEventListener {

    private var presentation: CoverPresentation? = null
    private var presentationDisplayId: Int = -1
    private var lastHingeBucket = -1 // 0 = folded, 1 = in-between, 2 = flat/open

    override fun onCreate() {
        super.onCreate()
        getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        // Hinge angle sensor (present on Galaxy Z Flip): the exact fold moment is
        // the one moment the cover display might become visible to third-party
        // apps, so re-check (and log) the display list then - see v1.2 notes.
        val sm = getSystemService(android.hardware.SensorManager::class.java)
        val hinge = sm?.getDefaultSensor(android.hardware.Sensor.TYPE_HINGE_ANGLE)
        if (hinge != null) {
            sm.registerListener(this, hinge, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(TAG, "Hinge angle sensor registered")
        } else {
            Log.i(TAG, "No hinge angle sensor on this device")
        }
    }

    override fun onSensorChanged(event: android.hardware.SensorEvent?) {
        val angle = event?.values?.firstOrNull() ?: return
        val bucket = when { angle < 15f -> 0; angle > 165f -> 2; else -> 1 }
        if (bucket != lastHingeBucket) {
            lastHingeBucket = bucket
            Log.i(TAG, "Hinge bucket=$bucket angle=$angle - displays: ${DisplayRepository(this).listDisplays()}")
            if (bucket == 0) tryShowOnSecondary()
        }
    }

    override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) { /* no-op */ }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification: Notification = NotificationCompat.Builder(this, CFlipApp.CHANNEL_ID)
            .setContentTitle("CFlip Launcher")
            .setContentText("Cover launcher đang chạy")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        Log.i(TAG, "Displays now: ${DisplayRepository(this).listDisplays()}")
        when (intent?.action) {
            ACTION_HIDE_COVER -> {
                presentation?.dismiss(); presentation = null; presentationDisplayId = -1
                Log.i(TAG, "Cover presentation hidden on request")
            }
            ACTION_ROTATION_CHANGED -> {
                presentation?.dismiss(); presentation = null; presentationDisplayId = -1
                tryShowOnSecondary()
            }
            else -> tryShowOnSecondary()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(this)
        getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(this)
        presentation?.dismiss(); presentation = null
        super.onDestroy()
    }

    override fun onDisplayAdded(displayId: Int) {
        Log.i(TAG, "Display added: $displayId")
        if (displayId != Display.DEFAULT_DISPLAY) tryShowOnSecondary()
    }

    override fun onDisplayRemoved(displayId: Int) {
        Log.i(TAG, "Display removed: $displayId")
        if (displayId == presentationDisplayId) {
            presentation?.dismiss(); presentation = null; presentationDisplayId = -1
        }
    }

    override fun onDisplayChanged(displayId: Int) {
        // v1.2 fix: a display that gets ENABLED (rather than added) - e.g. the
        // cover waking up at fold time - only fires this callback. Log the full
        // list and retry showing the cover UI instead of ignoring it (v1 bug).
        Log.i(TAG, "Display changed: $displayId - displays: ${DisplayRepository(this).listDisplays()}")
        tryShowOnSecondary()
    }

    private fun tryShowOnSecondary() {
        if (com.cuong.cflip.mirror.MirrorState.active) {
            Log.i(TAG, "Mirror is active - leaving the cover display to ScreenMirrorService")
            return
        }
        val secondary = DisplayRepository(this).secondaryDisplay() ?: return
        if (presentation != null && presentationDisplayId == secondary.displayId) return
        presentation?.dismiss()
        runCatching {
            presentation = CoverPresentation(this, secondary).also { it.show() }
            presentationDisplayId = secondary.displayId
            Log.i(TAG, "Presentation shown on display ${secondary.displayId}")
        }.onFailure { Log.w(TAG, "System refused Presentation on cover display", it) }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val TAG = "CFlipFG"
        const val ACTION_HIDE_COVER = "com.cuong.cflip.cover.HIDE"
        const val ACTION_ROTATION_CHANGED = "com.cuong.cflip.cover.ROTATION_CHANGED"
    }
}
