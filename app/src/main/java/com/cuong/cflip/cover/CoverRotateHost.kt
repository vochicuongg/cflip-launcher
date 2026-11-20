package com.cuong.cflip.cover

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.cuong.cflip.data.SettingsStore

/**
 * Whole-cover rotation (2.8.0, fixing the 2.7.0 bug).
 *
 * What 2.7.0 did wrong: it set `scroll.rotation` on the overlay's own
 * ScrollView. That rotates one View's content, not the display — the
 * AssistiveTouch button (a separate overlay), the Control Center panel
 * (another overlay) and any app opened on the cover all stayed in the
 * old orientation, so the user correctly reported "it only rotates
 * AssistiveTouch" (in practice: only the one rotated View moved, and
 * the rest of the cover did not follow).
 *
 * What 9Flip does (verified in rotate/ROTATE-SUMMARY.md, static only,
 * clean-room re-implementation, no copied code): put ONE invisible
 * 1x1 View on the secondary display's WindowManager with
 * `LayoutParams.screenOrientation` set. A window carrying a screen
 * orientation forces that orientation for its whole display — every
 * window on the cover follows, the way Samsung's own screen-rotation
 * setting does for a display. That is what this manager does.
 *
 * Mapping (CFlip's own, degrees -> ActivityInfo orientation):
 *   0   -> PORTRAIT, 90 -> LANDSCAPE,
 *   180 -> REVERSE_PORTRAIT, 270 -> REVERSE_LANDSCAPE.
 * AUTO (mode -1 in SettingsStore.coverRotation) is 9Flip's sensor pulse:
 * a FULL_SENSOR 1x1 view for 220ms, then removed — it lets the sensor
 * pick an orientation and then lets go, it does not hold a forced angle.
 * OFF is simply no host view at all.
 *
 * The host view is NOT_TOUCHABLE and NOT_FOCUSABLE, so it cannot steal
 * touches from the cover UI. It must be removed when the cover service
 * stops, otherwise a stale forced orientation would survive CFlip.
 */
/**
 * Runtime fallback flag (2.10.0): true when the system ignored the 1x1
 * screenOrientation host (Samsung Flex Window does this on some builds),
 * so CFlip's own windows rotate their content in sync instead of
 * leaving the cover unrotated. False means the host worked and content
 * must NOT rotate again, or it would double-rotate.
 */
object CoverRotateState {
    @Volatile var contentFallback: Boolean = false
}

object CoverRotateHostManager {
    private const val TAG = "CFlipRotateHost"

    private var wm: WindowManager? = null
    private var view: View? = null
    private var currentOrientation: Int? = null
    private var hostDisplayId: Int = -1
    private var currentType: Int? = null
    private val handler = Handler(Looper.getMainLooper())

    fun isShown(): Boolean = view != null
    fun currentOrientation(): Int? = currentOrientation

    // 3.3.0, decompile correction: 9Flip's rotate host l() does NOT use
    // appContext.createDisplayContext(). It uses the AccessibilityService
    // singleton's createDisplayContext(display 1). If that service is
    // dead, l() returns null and rotation stops — it does not silently
    // fall back to appContext. CFlip now tries the service context
    // first (MyAccessibilityService.instance), and only falls back to
    // appContext if the service is not running, logging which one was
    // used so a failed rotate can be diagnosed instead of guessed.
    private fun displayContextFor(appContext: Context, display: Display): Context {
        val svc = com.cuong.cflip.service.MyAccessibilityService.instance
        return if (svc != null) {
            Log.i(TAG, "Rotate host: using AccessibilityService display context")
            svc.createDisplayContext(display)
        } else {
            Log.w(TAG, "Rotate host: AccessibilityService not running, using appContext (9Flip would stop here)")
            appContext.createDisplayContext(display)
        }
    }

    // vc81 IDEMPOTENT (normal-mode loop fix): tryShow runs on EVERY display
    // change, and before vc81 every call did remove() + addView() even when
    // the host was already on the same display with the same orientation.
    // That remove gap let Samsung Flex Window / the sensor move the display,
    // then lock() re-locked to the NEW (wrong) rotation, so the cover kept
    // flipping and only settled when tilted back to the system direction.
    // Now a call with the same display + orientation is a no-op (no gap, no
    // flicker). A stale view (display removed/re-added with a new ID) still
    // re-adds, because hostDisplayId differs. pulse() forces a real re-add
    // to preserve its original one-time-snap behaviour.
    private fun addHost(appContext: Context, display: Display, orientation: Int, force: Boolean = false): Result<Unit> = runCatching {
        if (display.displayId == Display.DEFAULT_DISPLAY) {
            throw IllegalArgumentException("Rotate host is cover-only, refusing default display")
        }
        // vc96 ROTATE (9Flip RotateOverlayHost, decompiled
        // 27/10): 9Flip's host is 1x1, gravity TOP|START,
        // flags NOT_FOCUSABLE|NOT_TOUCHABLE, screenOrientation
        // as passed, but its window TYPE is 2032
        // (TYPE_ACCESSIBILITY_OVERLAY) when its accessibility
        // service is alive, falling back otherwise (ub.java:
        // type = a11y != null ? 2032 : fallback). 2032 windows
        // survive the secure keyguard and secure apps where
        // 2038 (TYPE_APPLICATION_OVERLAY, what CFlip used)
        // is hidden - that is why rotation (and with it the
        // cover UI that follows it) vanished on lock and in
        // Settings. CFlip's display context is already the
        // a11y service context when alive (see displayContextFor
        // above), exactly like 9Flip's l(), so 2032 is the
        // matching type here. If the system rejects 2032, fall
        // back to 2038 in the same call so rotation never
        // regresses below vc95.
        val a11yAlive = com.cuong.cflip.service.MyAccessibilityService.instance != null
        val preferredType = if (a11yAlive) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        if (!force && view != null && currentOrientation == orientation && hostDisplayId == display.displayId && currentType == preferredType) {
            Log.i(TAG, "Rotate host already on display ${display.displayId} orientation=$orientation, skipping re-add (vc81 idempotent)")
            return@runCatching Unit
        }
        val displayContext = displayContextFor(appContext, display)
        val manager = displayContext.getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("No WindowManager for display ${display.displayId}")
        remove()
        val v = View(displayContext)
        // vc91 LOCK FIX kept for the 2038 fallback; for 2032
        // (accessibility overlay) the type itself already
        // survives lock, the flag is harmless there.
        fun paramsFor(type: Int) = WindowManager.LayoutParams(
            1, 1,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenOrientation = orientation
        }
        val addedType = try {
            manager.addView(v, paramsFor(preferredType))
            preferredType
        } catch (e: Exception) {
            Log.w(TAG, "Rotate host type $preferredType rejected, falling back to 2038", e)
            runCatching { manager.removeView(v) }
            manager.addView(v, paramsFor(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY))
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        Log.i(TAG, "Rotate host type=$addedType (a11yAlive=$a11yAlive)")
        wm = manager; view = v; currentOrientation = orientation; hostDisplayId = display.displayId; currentType = addedType
        Log.i(TAG, "Rotate host on display ${display.displayId}: orientation=$orientation")
        Unit
    }.onFailure { Log.w(TAG, "Rotate host addView failed", it) }

    /** Legacy apply, kept for compatibility: FOLLOW -> follow(), LOCK -> lock(). */
    fun apply(appContext: Context, display: Display): Result<Unit> {
        return when (SettingsStore(appContext).load().rotateMode) {
            0 -> follow(appContext, display)
            1 -> lock(appContext, display)
            else -> { remove(); Result.success(Unit) }
        }
    }

    fun show(appContext: Context, display: Display, degrees: Int): Result<Unit> {
        val orientation = when (degrees) {
            90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        return addHost(appContext, display, orientation)
    }

    // 3.3.0 FOLLOW fix: the old pulse() added FULL_SENSOR for 220ms
    // then REMOVED it. Samsung Flex Window does not latch a pulsed
    // orientation — once the host is gone, the display stays where it
    // was, so flipping the phone later did nothing. That is exactly
    // why "xoay vẫn chưa hoạt động" after 3.2.0. FOLLOW now keeps a
    // FULL_SENSOR host alive for as long as the mode is FOLLOW, so
    // the cover continuously follows the direction the phone is
    // flipped, and only stops when the user switches to LOCK or the
    // service stops. pulse() is kept only for a one-time snap.
    fun follow(appContext: Context, display: Display): Result<Unit> {
        return addHost(appContext, display, ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR)
    }

    /** Sensor pulse: FULL_SENSOR for 220ms, then remove (9Flip's h()). */
    fun pulse(appContext: Context, display: Display): Result<Unit> = runCatching {
        val r = addHost(appContext, display, ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR, force = true)
        handler.postDelayed({ remove() }, 220L)
        r.getOrThrow(); Unit
    }.onFailure { Log.w(TAG, "Rotate pulse failed", it) }

    // 3.3.0 LOCK fix: LOCK is not "remove the host and hope the system
    // stays". It is an explicit host with the CURRENT display rotation
    // as a fixed orientation, so sensor flips cannot move it.
    fun lock(appContext: Context, display: Display): Result<Unit> {
        val orientation = when (display.rotation) {
            android.view.Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            android.view.Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            android.view.Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        return addHost(appContext, display, orientation)
    }

    fun remove() {
        val v = view
        if (v != null) {
            runCatching { wm?.removeView(v) }
            view = null; wm = null; currentOrientation = null
        }
        hostDisplayId = -1
        currentType = null
    }
}
