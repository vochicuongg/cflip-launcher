package com.cuong.cflip.data

import android.content.Context

/** All personalisation for the cover launcher. No paywall - everything applies instantly. */
data class CoverSettings(
    val columns: Int = 0,               // 0 = adaptive, else 3..6 fixed columns
    val iconSizeDp: Int = 56,
    val showLabels: Boolean = true,
    val backgroundStyle: Int = 0,       // index into BackgroundStyles
    val wallpaperUri: String? = null,   // user-picked cover wallpaper (persisted URI)
    val favoritesFirst: Boolean = true,
    val clock24h: Boolean = true,
    val coverRotation: Int = 0,         // 0 / 90 / 180 / 270 degrees for cover content
    val dualDisplay: Boolean = false,   // keep cover UI on while main screen is in use
    val sortMode: Int = 0,              // 0 = A-Z, 1 = recently opened, 2 = newly installed
)

object BackgroundStyles {
    // ARGB colours used when no wallpaper image is chosen
    val colors = listOf(0xFF000000L, 0xFF0B3D5CL, 0xFF10241EL, 0xFF2A1030L, 0xFF33180AL)
    val names = listOf("Black", "Cuong Blue", "Deep Green", "Plum", "Espresso")
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): CoverSettings = CoverSettings(
        columns = prefs.getInt("columns", 0),
        iconSizeDp = prefs.getInt("iconSizeDp", 56),
        showLabels = prefs.getBoolean("showLabels", true),
        backgroundStyle = prefs.getInt("backgroundStyle", 0),
        wallpaperUri = prefs.getString("wallpaperUri", null),
        favoritesFirst = prefs.getBoolean("favoritesFirst", true),
        clock24h = prefs.getBoolean("clock24h", true),
        coverRotation = prefs.getInt("coverRotation", 0),
        dualDisplay = prefs.getBoolean("dualDisplay", false),
        sortMode = prefs.getInt("sortMode", 0),
    )

    fun save(s: CoverSettings) {
        prefs.edit()
            .putInt("columns", s.columns)
            .putInt("iconSizeDp", s.iconSizeDp)
            .putBoolean("showLabels", s.showLabels)
            .putInt("backgroundStyle", s.backgroundStyle)
            .putString("wallpaperUri", s.wallpaperUri)
            .putBoolean("favoritesFirst", s.favoritesFirst)
            .putBoolean("clock24h", s.clock24h)
            .putInt("coverRotation", s.coverRotation)
            .putBoolean("dualDisplay", s.dualDisplay)
            .putInt("sortMode", s.sortMode)
            .apply()
    }
}
