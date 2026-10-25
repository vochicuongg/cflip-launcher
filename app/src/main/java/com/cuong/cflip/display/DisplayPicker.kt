package com.cuong.cflip.display

import android.view.Display

/** Pure logic, unit-testable: pick the first non-default display as the cover candidate. */
object DisplayPicker {
    fun pickSecondaryId(displayIds: List<Int>): Int? =
        displayIds.firstOrNull { it != Display.DEFAULT_DISPLAY }
}
