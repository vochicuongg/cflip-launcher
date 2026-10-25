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
    // 25/10/2026: AssistiveTouch lives ONLY on the secondary (cover) display.
    // The main display is custom/settings only, like 9Flip's MainActivity -
    // it never shows a bubble. Default true so the cover gets its button.
    val assistiveTouchEnabled: Boolean = true,
    // AssistiveTouch redesign 2.7.0 (9Flip size pattern, own values):
    // 0 = small 0.8, 1 = default 1.0, 2 = large 1.2; auto-dim default true.
    val assistiveTouchSize: Int = 1,
    val assistiveTouchAutoDim: Boolean = true,
    // 3.1.0: cover widgets are USER-ADDED ONLY, never forced.
    // Empty by default: no widget shows on the cover until the user
    // turns one on in MainScreen. Values: "appgrid", "clock".
    // (Music stays a Control tab control, not a widget, to avoid
    // duplicating it.) Stored as a comma-separated string.
    val coverWidgets: String = "",
    // 3.2.0 rotate, simplified per user request (decompile: 9Flip's
    // rotateMode is AUTO/MANUAL/OFF in code, but the user only wants
    // two: 0 = FOLLOW (follow the flip direction, sensor pulse),
    // 1 = LOCK (lock at the current orientation). coverRotation
    // (0/90/180/270 cycle) is retired — it only rotated one View.
    val rotateMode: Int = 1,
    // 3.2.0 panel brightness, per 9Flip decompile (p6/t6): 9Flip does
    // NOT write Settings.System for its panel. It keeps its own manual
    // brightness value (clamp 0.05–1.0) and applies it as the panel
    // window's screenBrightness, or -1 (follow system) when manual is
    // off. CFlip now does the same, so the slider actually shows on
    // the cover instead of silently failing on system brightness.
    val panelBrightnessManual: Boolean = false,
    val panelBrightness: Float = 0.72f,
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
        assistiveTouchEnabled = prefs.getBoolean("assistiveTouchEnabled", true),
        assistiveTouchSize = prefs.getInt("assistiveTouchSize", 1),
        assistiveTouchAutoDim = prefs.getBoolean("assistiveTouchAutoDim", true),
        coverWidgets = prefs.getString("coverWidgets", "") ?: "",
        rotateMode = prefs.getInt("rotateMode", 1),
        panelBrightnessManual = prefs.getBoolean("panelBrightnessManual", false),
        panelBrightness = prefs.getFloat("panelBrightness", 0.72f),
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
            .putBoolean("assistiveTouchEnabled", s.assistiveTouchEnabled)
            .putInt("assistiveTouchSize", s.assistiveTouchSize)
            .putBoolean("assistiveTouchAutoDim", s.assistiveTouchAutoDim)
            .putString("coverWidgets", s.coverWidgets)
            .putInt("rotateMode", s.rotateMode)
            .putBoolean("panelBrightnessManual", s.panelBrightnessManual)
            .putFloat("panelBrightness", s.panelBrightness)
            .apply()
    }
}
