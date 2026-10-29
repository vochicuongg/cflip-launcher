package com.cuong.cflip.data

import android.content.pm.ApplicationInfo

/**
 * vc149 APP GROUPS (user, 29/10): the cover widget groups apps into
 * a SMALL curated set of important groups only - it never tries to
 * group every app. An app that fits no group below simply has no
 * group: it stays reachable from the full app list ("Xem tất cả ứng
 * dụng" / the Apps tab) and never lands in a catch-all group.
 *
 * 9Flip decompile note (checked 29/10, v149): 9Flip's app grid has
 * NO grouping at all - its grid is flat (NineCoverAppGridWidgetProvider
 * + RemoteViewsService, no category/folder logic found), so there was
 * no grouping model to borrow. This file is CFlip's own design on the
 * standard platform signal: ApplicationInfo.category (the category an
 * app declares in its own manifest) plus a small best-effort package
 * list for famous social apps that do not declare CATEGORY_SOCIAL.
 * A wrong hint here is harmless: the app just falls through to its
 * declared category instead of the hinted group.
 */
enum class AppGroup(val title: String) {
    SOCIAL("Mạng xã hội"),
    GAME("Game"),
    MEDIA("Giải trí"),
    WORK("Công việc"),
}

object AppGroups {
    // Famous social packages, best-effort only (see the header note).
    private val socialPackages = setOf(
        "com.facebook.katana",      // Facebook
        "com.facebook.orca",        // Messenger
        "com.zing.zalo",            // Zalo
        "org.telegram.messenger",   // Telegram
        "com.ss.android.ugc.trill", // TikTok
        "com.instagram.android",    // Instagram
        "com.instagram.barcelona",  // Threads
        "com.twitter.android",      // X
        "com.whatsapp",             // WhatsApp
        "com.discord",              // Discord
        "com.viber.voip",           // Viber
    )

    /** The group an app belongs to, or null when it fits none. */
    fun groupFor(app: AppInfo): AppGroup? {
        if (app.packageName in socialPackages) return AppGroup.SOCIAL
        return when (app.category) {
            ApplicationInfo.CATEGORY_SOCIAL -> AppGroup.SOCIAL
            ApplicationInfo.CATEGORY_GAME -> AppGroup.GAME
            ApplicationInfo.CATEGORY_VIDEO,
            ApplicationInfo.CATEGORY_AUDIO,
            ApplicationInfo.CATEGORY_IMAGE -> AppGroup.MEDIA
            ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppGroup.WORK
            else -> null
        }
    }

    /** Apps split into the curated groups, in display order, skipping
     *  empty groups. The input is already A-Z sorted, so members stay
     *  alphabetical inside each group. */
    fun groupedApps(apps: List<AppInfo>): List<Pair<AppGroup, List<AppInfo>>> {
        if (apps.isEmpty()) return emptyList()
        val byGroup = LinkedHashMap<AppGroup, MutableList<AppInfo>>()
        for (app in apps) {
            val group = groupFor(app) ?: continue
            byGroup.getOrPut(group) { mutableListOf() }.add(app)
        }
        return AppGroup.values().mapNotNull { group ->
            val members = byGroup[group]
            if (members.isNullOrEmpty()) null else group to members
        }
    }
}
