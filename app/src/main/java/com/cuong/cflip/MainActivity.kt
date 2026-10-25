package com.cuong.cflip

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.cuong.cflip.ui.CoverTab
import com.cuong.cflip.ui.MainScreen
import com.cuong.cflip.ui.theme.CFlipTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // AssistiveTouch Menu (double-tap on the cover) launches this activity
        // on the cover display, straight into the cover UI at the requested tab.
        val showCover = intent?.getBooleanExtra("cflip_show_cover", false) == true
        val tab = when (intent?.getStringExtra("cflip_cover_tab")) {
            "APPS" -> CoverTab.APPS
            "NOTIFS" -> CoverTab.NOTIFS
            "MEDIA" -> CoverTab.MEDIA
            "QUICK" -> CoverTab.QUICK
            "STYLE" -> CoverTab.STYLE
            else -> CoverTab.HOME
        }
        setContent {
            CFlipTheme { MainScreen(initialTab = tab, showCoverInitially = showCover) }
        }
    }
}
