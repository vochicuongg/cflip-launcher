package com.cuong.cflip.data

import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    // vc149 APP GROUPS: the Play-declared app category
    // (ApplicationInfo.category, API 26+; minSdk 33 so it is always
    // present). UNDEFINED (-1) when the app declares none - the
    // grouper then falls back to its known-package list, and an app
    // with neither stays ungrouped (never forced into a group).
    val category: Int = ApplicationInfo.CATEGORY_UNDEFINED,
)
