package com.cuong.cflip.cover

import android.content.Context
import android.graphics.PixelFormat
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.cuong.cflip.service.MyAccessibilityService

/**
 * vc141: fullscreen transparent brightness holder for the cover display.
 *
 * Why it exists: CFlip's brightness only ever lived on CFlip's own windows.
 * The panel is fullscreen, so while it is open its screenBrightness drives
 * the visible cover brightness - but the moment it closes, the only CFlip
 * windows left are the tiny opener pill/bubble, and on this Samsung device
 * those do not hold the display brightness. Samsung's cover brightness is
 * also not Settings.System (see the vc35 note in CoverControlCenter), so
 * writing the system value cannot brighten the cover either. The result
 * the user reported on vc140: brightness up in the panel, exit the panel,
 * the cover is dark again.
 *
 * This holder is the missing fullscreen window: an empty, transparent,
 * non-touchable, non-focusable overlay that carries CFlip's manual
 * brightness while an opener is shown, so the cover stays at the chosen
 * level after the panel closes. It draws nothing and takes no touches.
 *
 * Lifecycle is tied to the openers: both opener managers call show() from
 * their show() and hide() from their hide(), the panel holds brightness
 * itself while it is open (opener hidden => holder hidden), and auto mode
 * (manual off) hides the holder entirely so Samsung's own brightness
 * rules apply untouched. Turning off Quick Panel or switching back to
 * auto brightness both remove it.
 */
object CoverBrightnessHolder {
    private var wm: WindowManager? = null
    private var holder: View? = null
    private var holderParams: WindowManager.LayoutParams? = null

    fun isShown(): Boolean = holder != null

    fun show(appContext: Context, display: Display): Result<Unit> = runCatching {
        if (display.displayId == Display.DEFAULT_DISPLAY) {
            throw IllegalArgumentException("Brightness holder is cover-only, refusing default display")
        }
        val st = com.cuong.cflip.data.SettingsStore(appContext).load()
        if (!st.panelBrightnessManual) {
            hide()
            return@runCatching
        }
        val cur = holder
        val curParams = holderParams
        val curWm = wm
        if (cur != null && curParams != null && curWm != null) {
            // Already shown (re-show): just refresh the value, never
            // add the same view twice.
            curParams.screenBrightness = st.panelBrightness.coerceIn(0.05f, 1.0f)
            runCatching { curWm.updateViewLayout(cur, curParams) }
            return@runCatching
        }
        hide()
        val a11ySvc = MyAccessibilityService.instance
        val displayContext = if (a11ySvc != null) a11ySvc.createDisplayContext(display) else appContext.createDisplayContext(display)
        val holderType = if (a11ySvc != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val manager = displayContext.getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("No WindowManager for display ${display.displayId}")
        val view = View(displayContext)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            holderType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            screenBrightness = st.panelBrightness.coerceIn(0.05f, 1.0f)
        }
        manager.addView(view, params)
        wm = manager; holder = view; holderParams = params
    }

    fun apply(appContext: Context) {
        val params = holderParams; val v = holder; val manager = wm
        if (params == null || v == null || manager == null) return
        val st = com.cuong.cflip.data.SettingsStore(appContext).load()
        if (!st.panelBrightnessManual) {
            hide()
            return
        }
        params.screenBrightness = st.panelBrightness.coerceIn(0.05f, 1.0f)
        runCatching { manager.updateViewLayout(v, params) }
    }

    fun hide() {
        val v = holder; val manager = wm
        if (v != null && manager != null) runCatching { manager.removeView(v) }
        holder = null; wm = null; holderParams = null
    }
}
