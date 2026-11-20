package com.cuong.cflip

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class CFlipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID, "CFlip Launcher", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Cover launcher foreground service" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        // vc189 MIRROR SILENT CHANNEL: the mirror foreground
        // notification must exist (Android kills MediaProjection
        // without a foreground service notification), but it must
        // not sit on the screen. IMPORTANCE_MIN shows no status
        // bar icon, no peek, no sound - shade-only. A NEW id is
        // required: reusing cflip_fg would keep its LOW importance
        // on devices where the channel already exists.
        val mirrorChannel = NotificationChannel(
            CHANNEL_ID_MIRROR, "Phản chiếu", NotificationManager.IMPORTANCE_MIN
        ).apply { description = "Mirror foreground service (silent, no status bar icon)" }
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(mirrorChannel)
    }
    companion object { const val CHANNEL_ID = "cflip_fg"; const val CHANNEL_ID_MIRROR = "cflip_mirror_min" }
}
