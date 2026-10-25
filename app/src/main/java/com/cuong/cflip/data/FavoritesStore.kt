package com.cuong.cflip.data

import android.content.Context

/** Persists favourite packages shown first on the cover screen. Scaffold: SharedPreferences. */
class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)

    fun getFavorites(): Set<String> = prefs.getStringSet(KEY, emptySet()) ?: emptySet()

    fun toggle(packageName: String) {
        val next = getFavorites().toMutableSet().apply {
            if (!add(packageName)) remove(packageName)
        }
        prefs.edit().putStringSet(KEY, next).apply()
    }

    companion object { private const val KEY = "fav_packages" }
}
