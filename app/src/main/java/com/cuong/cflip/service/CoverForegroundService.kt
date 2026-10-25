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
import com.cuong.cflip.display.DisplayRepository

/**
 * 3.0.0: Presentation DELETED at the user's request (it was buggy and
 * annoying on the Flip5, and it hid all the widgets because it was tried
 * first). When a secondary display appears, this service now shows ONLY
 * the cover AssistiveTouch button (+ the rotate host). The Widget
 * interface is the AssistiveTouch 4-tab panel (Control / Notifications /
 * Apps / CFlip), opened by double-tapping the button. No full-screen
 * cover launcher is shown at all — Samsung's own cover stays underneath.
 */
class CoverForegroundService : Service(), DisplayManager.DisplayListener,
    android.hardware.SensorEventListener {

    private var assistiveTouchDisplayId: Int = -1
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
                assistiveTouchDisplayId = -1
                com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
                com.cuong.cflip.cover.CoverControlCenterManager.hide()
                com.cuong.cflip.cover.CoverRotateHostManager.remove()
                Log.i(TAG, "Cover AssistiveTouch hidden on request")
            }
            ACTION_ROTATION_CHANGED -> {
                tryShowOnSecondary()
            }
            else -> tryShowOnSecondary()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(this)
        getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(this)
        com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
        com.cuong.cflip.cover.CoverControlCenterManager.hide()
        com.cuong.cflip.cover.CoverRotateHostManager.remove()
        super.onDestroy()
    }

    override fun onDisplayAdded(displayId: Int) {
        Log.i(TAG, "Display added: $displayId")
        if (displayId != Display.DEFAULT_DISPLAY) tryShowOnSecondary()
    }

    override fun onDisplayRemoved(displayId: Int) {
        Log.i(TAG, "Display removed: $displayId")
        if (displayId == assistiveTouchDisplayId) {
            assistiveTouchDisplayId = -1
            com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
            com.cuong.cflip.cover.CoverControlCenterManager.hide()
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
        if (assistiveTouchDisplayId == secondary.displayId &&
            com.cuong.cflip.cover.CoverAssistiveTouchManager.isShown()) return
        // 3.0.0: NO Presentation, NO full-screen overlay launcher.
        // Only the rotate host + the AssistiveTouch button. The panel
        // (Widget) opens from the button's double-tap.
        // 3.2.0 rotate modes: FOLLOW = sensor pulse, LOCK = remove host.
        if (com.cuong.cflip.data.SettingsStore(this).load().rotateMode == 0) {
            com.cuong.cflip.cover.CoverRotateHostManager.pulse(this, secondary)
        } else {
            com.cuong.cflip.cover.CoverRotateHostManager.remove()
        }
        if (com.cuong.cflip.data.SettingsStore(this).load().assistiveTouchEnabled) {
            com.cuong.cflip.cover.CoverAssistiveTouchManager.show(this, secondary)
                .onSuccess { assistiveTouchDisplayId = secondary.displayId }
                .onFailure { Log.w(TAG, "AssistiveTouch refused on cover display", it) }
        } else {
            com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val TAG = "CFlipFG"
        const val ACTION_HIDE_COVER = "com.cuong.cflip.cover.HIDE"
        const val ACTION_ROTATION_CHANGED = "com.cuong.cflip.cover.ROTATION_CHANGED"
    }
}
