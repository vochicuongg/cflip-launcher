package com.cuong.cflip.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.cuong.cflip.data.AppInfo
import com.cuong.cflip.data.CoverSettings
import com.cuong.cflip.ui.theme.CFlipTheme

/** Iterate on cover UI with zero device use. Sizes are the real cover panels. */
private fun sampleApps(): List<AppInfo> = listOf(
    "Zalo", "Messenger", "Maps", "YouTube Music", "Camera", "Clock",
    "Calendar", "Notes", "Wallet", "Settings", "Phone", "Gallery",
).map { AppInfo(packageName = "sample." + it.lowercase().replace(" ", ""), label = it, icon = null) }

@Preview(name = "Cover Flip5/6 - 720x748", device = "spec:width=720px,height=748px,dpi=306")
@Composable
fun CoverPreviewFlip5() {
    CFlipTheme {
        CoverLauncherScreen(
            apps = sampleApps(), favorites = setOf("sample.zalo"),
            settings = CoverSettings(), onAppClick = {}, onToggleFavorite = {}
        )
    }
}

@Preview(name = "Cover Flip7 - 948x1048", device = "spec:width=948px,height=1048px,dpi=345")
@Composable
fun CoverPreviewFlip7() {
    CFlipTheme {
        CoverLauncherScreen(
            apps = sampleApps(), favorites = emptySet(),
            settings = CoverSettings(), onAppClick = {}, onToggleFavorite = {}
        )
    }
}
