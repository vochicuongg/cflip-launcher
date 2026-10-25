package com.cuong.cflip.mirror

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Shared on/off state for screen mirroring, so the UI and both services agree
 *  about whether the cover display is currently showing the mirror feed
 *  (ScreenMirrorService) or the normal cover launcher (CoverForegroundService). */
object MirrorState {
    var active by mutableStateOf(false)
}
