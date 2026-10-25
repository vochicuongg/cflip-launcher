package com.cuong.cflip.data

import android.content.Context

/**
 * Persists the packages the user hid from the cover app drawer (v1.3).
 * Hidden apps disappear from every CFlip surface because AppRepository
 * filters them out centrally. CFlip's own package can never be hidden.
 */
class HiddenAppsStore(context: Context) {
    private val prefs = context.getSharedPreferences("hidden_apps", Context.MODE_PRIVATE)
    private val ownPackage = context.packageName

    fun getHidden(): Set<String> = prefs.getStringSet(KEY, emptySet()) ?: emptySet()

    fun hide(packageName: String) {
        if (packageName == ownPackage) return
        val next = getHidden().toMutableSet().apply { add(packageName) }
        prefs.edit().putStringSet(KEY, next).apply()
    }

    fun unhide(packageName: String) {
        val next = getHidden().toMutableSet().apply { remove(packageName) }
        prefs.edit().putStringSet(KEY, next).apply()
    }

    companion object { private const val KEY = "hidden_packages" }
}
