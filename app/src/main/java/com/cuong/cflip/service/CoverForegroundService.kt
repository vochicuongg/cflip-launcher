package com.cuong.cflip.service

import android.app.Notification
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    // vc81 host retry: if addHost fails transiently during fold (display not
    // ready yet), tryShow used to give up until the NEXT display event — which
    // may never come if Samsung locks the cover orientation, so the host stayed
    // dead and rotation looked "off". That is fatal when the bubble is hidden
    // (AssistiveTouch toggle OFF), because the panel rotation tile — the manual
    // retry — is unreachable without the bubble. Retry up to 3 times, 500ms
    // apart, bounded; a fresh display event resets the budget.
    private val hostHandler = Handler(Looper.getMainLooper())
    private var hostRetryAttempts = 0
    private var hostRetryPending = false
    // vc95: throttle lock-bubble launches (display changes
    // fire in bursts while locked; without this, tryShow
    // would relaunch the UnlockActivity every burst).
    private var lastUnlockLaunchMs = 0L
    private val hostRetryRunnable = Runnable {
        hostRetryPending = false
        tryShowOnSecondary(isRetry = true)
    }
    private var bubbleRetryAttempts = 0
    // vc109 UNLOCK RE-SHOW FIX (user, 27/10):
    // lock hides the bubble (vc101), but NOTHING
    // listened for unlock: tryShow only ran on
    // display added/changed and hinge events, so
    // after lock -> unlock the bubble stayed
    // hidden until the next rotation (hinge /
    // display event) brought it back. Fix: listen
    // for screen on/off + user present and run
    // tryShow on each. tryShow itself checks
    // keyguard, so SCREEN_ON while still locked
    // just hides again (harmless) and USER_PRESENT
    // (keyguard dismissed) shows the bubble
    // immediately, without waiting for rotation.
    private var screenReceiver: BroadcastReceiver? = null
    private val screenReceiverImpl = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.i(TAG, "Screen/unlock event: ${intent?.action} - re-checking cover")
            tryShowOnSecondary()
        }
    }
    private val bubbleRetryRunnable: Runnable = Runnable {
        if (com.cuong.cflip.mirror.MirrorState.active && bubbleRetryAttempts < 3) {
            bubbleRetryAttempts++
            Log.i(TAG, "Bubble re-show deferred, MirrorState still active, retry $bubbleRetryAttempts/3")
            hostHandler.postDelayed(bubbleRetryRunnable, 500L)
        } else {
            bubbleRetryAttempts = 0
            tryShowOnSecondary()
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(this, Handler(Looper.getMainLooper()))
        // vc109: see screenReceiverImpl above.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { registerReceiver(screenReceiverImpl, filter) }
        screenReceiver = screenReceiverImpl
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
                // vc78 DECOUPLE (user principle, 9Flip Application
                // pattern): hiding the bubble must NOT kill features.
                // RotateHost is a feature (like 9Flip's Application
                // singleton, independent of floating menu visibility),
                // not part of the bubble. Hide bubble + panel ONLY,
                // keep RotateHost so rotation still works with the
                // bubble hidden. Host is removed only in onDestroy
                // (service stopped entirely), not on bubble hide.
                assistiveTouchDisplayId = -1
                // vc109: panel hide must NOT restore
                // the bubble here - the user hid the
                // cover on purpose.
                com.cuong.cflip.cover.CoverControlCenterManager.hideWithoutBubbleRestore()
                com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
                com.cuong.cflip.cover.CoverSwipeIndicatorManager.hide()
                Log.i(TAG, "Cover AssistiveTouch hidden on request (RotateHost kept)")
            }
            ACTION_ROTATION_CHANGED -> {
                // vc92 BUBBLE-RESTORE FIX (log 04:06-04:13 27/10:
                // mirror stops cleanly, bubble lost, only
                // lock/unlock brings it back): tryShow returns
                // early while MirrorState.active is still true,
                // and if that early return swallows the one
                // re-show request, no later display event retries
                // it - lock/unlock is the first event that does.
                // Retry the re-show up to 3x at 500ms instead of
                // dropping it, so the bubble returns without a
                // lock/unlock cycle.
                if (com.cuong.cflip.mirror.MirrorState.active) {
                    bubbleRetryAttempts = 0
                    hostHandler.postDelayed(bubbleRetryRunnable, 500L)
                } else {
                    tryShowOnSecondary()
                }
            }
            else -> tryShowOnSecondary()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        hostHandler.removeCallbacks(hostRetryRunnable)
        hostHandler.removeCallbacks(bubbleRetryRunnable)
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(this)
        getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(this)
        // vc109: panel hide must NOT restore the
        // bubble here - the service is stopping.
        com.cuong.cflip.cover.CoverControlCenterManager.hideWithoutBubbleRestore()
        com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
        com.cuong.cflip.cover.CoverSwipeIndicatorManager.hide()
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
            // vc109: display gone, panel hide must
            // NOT try to restore a bubble on it.
            com.cuong.cflip.cover.CoverControlCenterManager.hideWithoutBubbleRestore()
            com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
            com.cuong.cflip.cover.CoverSwipeIndicatorManager.hide()
        }
    }

    override fun onDisplayChanged(displayId: Int) {
        // v1.2 fix: a display that gets ENABLED (rather than added) - e.g. the
        // cover waking up at fold time - only fires this callback. Log the full
        // list and retry showing the cover UI instead of ignoring it (v1 bug).
        Log.i(TAG, "Display changed: $displayId - displays: ${DisplayRepository(this).listDisplays()}")
        tryShowOnSecondary()
    }

    private fun tryShowOnSecondary(isRetry: Boolean = false) {
        val secondary = DisplayRepository(this).secondaryDisplay() ?: return
        // vc83 MIRROR OVERRIDE of vc78 (deliberate, mirror only):
        // when mirror is active, do NOT rebuild the RotateHost here.
        // User confirmed 27/10: 180-degree cover rotation during a
        // mirror attempt is NORMAL 9Flip behaviour, not a bug, and
        // the foreground FOLLOW host (FULL_SENSOR) fighting the
        // mirror session is not wanted during mirror. Rotation in
        // normal (non-mirror) mode is untouched — vc81 fix intact.
        // Bubble behaviour is unchanged: mirror active still returns
        // here without showing the bubble.
        if (com.cuong.cflip.mirror.MirrorState.active) {
            Log.i(TAG, "Mirror active - foreground host left alone (vc83)")
            return
        }
        if (!isRetry) hostRetryAttempts = 0
        // vc78 DECOUPLE (user principle, 9Flip Application pattern):
        // RotateHost is ensured ALWAYS, even when mirror is active
        // or bubble is hidden. Previously, mirror active returned
        // early here, so if dual detached the host view, it was
        // never re-added during mirror (mirror service's own rotate
        // view was the only replacement, and it failed in dual).
        // Now every display change re-adds the host after dual
        // settles (display added/changed fires after reparent),
        // which is more likely to succeed than the service's
        // immediate add during dual transition. Bubble is shown
        // only after this, and only if mirror is NOT active.
        // 3.0.0: NO Presentation, NO full-screen overlay launcher.
        // Only the rotate host + the AssistiveTouch button. The panel
        // (Widget) opens from the button's double-tap.
        // 3.3.0 rotate modes: FOLLOW = persistent FULL_SENSOR host,
        // LOCK = fixed-orientation host at current rotation.
        // vc81: capture the host Result (before, it was ignored, so a failed
        // add silently left rotation dead). On success, clear any pending
        // retry; on failure, schedule a bounded retry (see fields above).
        // vc100 LOCK ROTATE RESTORE (vc99 test:
        // lock info is BACK, but lock no longer rotates):
        // vc98 changed the lock host to lock() (fixed
        // orientation) on the hypothesis that a
        // continuous FULL_SENSOR host displaced lock
        // widgets. vc98 still lost info, vc99 restored
        // info WITHOUT changing the host - vc99 only
        // replaced the auto-launched UnlockActivity
        // with a 2032 overlay bubble. That proves the
        // info loss was the Activity, not the host.
        // Keeping lock() on lock therefore only costs
        // rotation for no benefit: the lock screen
        // stays at whatever rotation it had when it
        // locked and never follows the sensor. vc100
        // restores follow()/lock() per settings on
        // lock exactly as when unlocked, keeping the
        // vc99 overlay bubble (no Activity) so info
        // stays. If info vanishes again on vc100, the
        // host will be proven guilty after all and
        // vc101 can revisit it - but the evidence
        // now points to the Activity, not the host.
        val hostResult = if (com.cuong.cflip.data.SettingsStore(this).load().rotateMode == 0) {
            com.cuong.cflip.cover.CoverRotateHostManager.follow(this, secondary)
        } else {
            com.cuong.cflip.cover.CoverRotateHostManager.lock(this, secondary)
        }
        if (hostResult.isSuccess) {
            hostRetryAttempts = 0
            hostRetryPending = false
            hostHandler.removeCallbacks(hostRetryRunnable)
        } else if (hostRetryAttempts < 3) {
            hostRetryAttempts++
            hostRetryPending = true
            Log.w(TAG, "Rotate host failed, retry $hostRetryAttempts/3 in 500ms")
            hostHandler.postDelayed(hostRetryRunnable, 500L)
        } else {
            hostRetryPending = false
            Log.w(TAG, "Rotate host failed after retries, giving up until next display event")
        }
        if (com.cuong.cflip.mirror.MirrorState.active) {
            Log.i(TAG, "Mirror is active - leaving bubble to ScreenMirrorService (host kept)")
            return
        }
        val settings = com.cuong.cflip.data.SettingsStore(this).load()
        // vc96 LOCK BUBBLE BUG (vc95 test: still no bubble on
        // cover when locked): the isShown guard below used to
        // run FIRST. If the overlay bubble was already shown
        // before the phone locked, tryShow returned early here
        // on every later display change, so the locked branch
        // (launch UnlockActivity) never ran - and the overlay
        // itself is hidden by the keyguard, so the user saw
        // nothing on lock. Locked check must run BEFORE the
        // isShown guard, not after it.
        // vc101 NO BUBBLE ON LOCK (user request 27/10,
        // vc100 test: lock rotation works AND lock info
        // stays): vc99 proved an overlay bubble can sit
        // on lock without hiding info, but the user now
        // wants NO AssistiveTouch on lock at all, with
        // lock rotation untouched. Rotation lives in
        // the RotateHost ensured ABOVE (follow/lock per
        // settings, vc100), which is independent of the
        // bubble - removing the bubble does not remove
        // the host. So on lock: hide bubble + panel,
        // close any lingering UnlockActivity, and return
        // WITHOUT showing an overlay bubble and WITHOUT
        // launching UnlockActivity. The bubble returns
        // on unlock via the unlocked path below (which
        // sends FINISH_UNLOCK and shows the overlay).
        // Mirror active already returned at the top, so
        // this does not touch mirror rotation.
        val kmEarly = getSystemService(android.app.KeyguardManager::class.java)
        if (kmEarly != null && kmEarly.isKeyguardLocked) {
            com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
            com.cuong.cflip.cover.CoverSwipeIndicatorManager.hide()
            com.cuong.cflip.cover.CoverControlCenterManager.hide()
            assistiveTouchDisplayId = -1
            runCatching {
                sendBroadcast(android.content.Intent(
                    com.cuong.cflip.cover.CoverUnlockActivity.ACTION_FINISH_UNLOCK))
            }
            Log.i(TAG, "Locked - bubble hidden, RotateHost kept (vc101)")
            return
        }
        // vc112 BUBBLE-DURING-PANEL FIX (user,
        // 27/10: bubble kept appearing when
        // accessing the panel and when rotating):
        // vc109 hides the bubble when the panel
        // opens, but tryShow runs again on every
        // display change / hinge / screen-on
        // event, and it saw isShown() == false
        // (bubble hidden for the panel) and showed
        // the bubble again OVER the panel. Rotation
        // is exactly such an event. Fix: while the
        // panel is active (view OR loading intro),
        // do NOT show the bubble. RotateHost was
        // already ensured above, so rotation still
        // works with the panel open. The locked
        // branch above still runs first, so lock
        // still hides the panel + bubble (vc101).
        if (com.cuong.cflip.cover.CoverControlCenterManager.isPanelActive()) {
            Log.i(TAG, "Panel active - opener left hidden (vc112, vc120: bubble or swipe bar)")
            return
        }
        if (assistiveTouchDisplayId == secondary.displayId &&
            (com.cuong.cflip.cover.CoverAssistiveTouchManager.isShown() ||
                com.cuong.cflip.cover.CoverSwipeIndicatorManager.isShown())) return
        if (!settings.assistiveTouchEnabled) {
            com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
            com.cuong.cflip.cover.CoverSwipeIndicatorManager.hide()
            return
        }
        // Unlocked: make sure a lingering lock bubble goes
        // away (user unlocked via main, not via the bubble),
        // then show the opener for the current open
        // mode (vc120: bubble vs swipe bar - never
        // both; the other one is hidden first).
        runCatching {
            sendBroadcast(android.content.Intent(
                com.cuong.cflip.cover.CoverUnlockActivity.ACTION_FINISH_UNLOCK))
        }
        if (settings.panelOpenMode == com.cuong.cflip.data.PanelOpenMode.SWIPE_INDICATOR) {
            com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
            com.cuong.cflip.cover.CoverSwipeIndicatorManager.show(this, secondary)
                .onSuccess { assistiveTouchDisplayId = secondary.displayId }
                .onFailure { Log.w(TAG, "Swipe indicator refused on cover display", it) }
        } else {
            com.cuong.cflip.cover.CoverSwipeIndicatorManager.hide()
            com.cuong.cflip.cover.CoverAssistiveTouchManager.show(this, secondary)
                .onSuccess { assistiveTouchDisplayId = secondary.displayId }
                .onFailure { Log.w(TAG, "AssistiveTouch refused on cover display", it) }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private const val TAG = "CFlipFG"
        const val ACTION_HIDE_COVER = "com.cuong.cflip.cover.HIDE"
        const val ACTION_ROTATION_CHANGED = "com.cuong.cflip.cover.ROTATION_CHANGED"
    }
}
