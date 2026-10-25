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
    private const val PULSE_MS = 220L

    private var wm: WindowManager? = null
    private var view: View? = null
    private val handler = Handler(Looper.getMainLooper())

    fun isShown(): Boolean = view != null

    /** Apply the saved coverRotation to this display. */
    fun apply(appContext: Context, display: Display): Result<Unit> {
        val degrees = SettingsStore(appContext).load().coverRotation
        return when (degrees) {
            -1 -> pulse(appContext, display)
            0, 90, 180, 270 -> show(appContext, display, degrees)
            else -> { remove(); Result.success(Unit) }
        }
    }

    fun show(appContext: Context, display: Display, degrees: Int): Result<Unit> = runCatching {
        if (display.displayId == Display.DEFAULT_DISPLAY) {
            throw IllegalArgumentException("Rotate host is cover-only, refusing default display")
        }
        val orientation = when (degrees) {
            90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val displayContext = appContext.createDisplayContext(display)
        val manager = displayContext.getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("No WindowManager for display ${display.displayId}")
        remove()
        val v = View(displayContext)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenOrientation = orientation
        }
        manager.addView(v, params)
        wm = manager; view = v
        Log.i(TAG, "Rotate host on display ${display.displayId}: $degrees° (orientation=$orientation)")
        Unit
    }.onFailure { Log.w(TAG, "Rotate host addView failed", it) }

    /** Sensor pulse: FULL_SENSOR for 220ms, then remove (9Flip's h()). */
    fun pulse(appContext: Context, display: Display): Result<Unit> = runCatching {
        val displayContext = appContext.createDisplayContext(display)
        val manager = displayContext.getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("No WindowManager for display ${display.displayId}")
        remove()
        val v = View(displayContext)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
        manager.addView(v, params)
        wm = manager; view = v
        handler.postDelayed({ remove() }, PULSE_MS)
        Log.i(TAG, "Rotate sensor pulse on display ${display.displayId}")
        Unit
    }.onFailure { Log.w(TAG, "Rotate pulse failed", it) }

    fun remove() {
        val v = view
        if (v != null) {
            runCatching { wm?.removeView(v) }
            view = null; wm = null
        }
    }
}
