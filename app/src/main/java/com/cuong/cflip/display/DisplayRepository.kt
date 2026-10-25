package com.cuong.cflip.display

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display

data class DisplayInfo(
    val id: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val isDefault: Boolean,
)

class DisplayRepository(private val context: Context) {

    private val dm get() = context.getSystemService(DisplayManager::class.java)

    fun listDisplays(): List<DisplayInfo> = dm?.displays?.map { d ->
        val (w, h) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = d.mode; b.physicalWidth to b.physicalHeight
        } else { 0 to 0 }
        DisplayInfo(d.displayId, d.name, w, h, d.displayId == Display.DEFAULT_DISPLAY)
    } ?: emptyList()

    /** The cover screen on a real Flip appears as a secondary display when folded.
     *  On an emulator, use Developer Options > Simulate secondary displays. */
    fun secondaryDisplay(): Display? {
        val ids = dm?.displays?.map { it.displayId } ?: return null
        val id = DisplayPicker.pickSecondaryId(ids) ?: return null
        return dm?.getDisplay(id)
    }

    /** Ask the system whether starting [intent] on [displayId] is even allowed.
     *  On Samsung cover displays this is often false for third-party apps - that
     *  result is exactly what the MultiStar widget route (see widget/) works around. */
    fun isStartAllowed(displayId: Int, intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val am = context.getSystemService(ActivityManager::class.java) ?: return false
        return runCatching { am.isActivityStartAllowedOnDisplay(context, displayId, intent) }
            .getOrDefault(false)
    }
}
