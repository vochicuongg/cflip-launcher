package com.cuong.cflip

import android.view.Display
import com.cuong.cflip.display.DisplayPicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayPickerTest {
    @Test fun picksFirstNonDefault() {
        assertEquals(2, DisplayPicker.pickSecondaryId(listOf(Display.DEFAULT_DISPLAY, 2, 3)))
    }
    @Test fun nullWhenOnlyDefault() {
        assertNull(DisplayPicker.pickSecondaryId(listOf(Display.DEFAULT_DISPLAY)))
    }
    @Test fun nullWhenEmpty() {
        assertNull(DisplayPicker.pickSecondaryId(emptyList()))
    }
}
