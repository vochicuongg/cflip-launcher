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
    }
    companion object { const val CHANNEL_ID = "cflip_fg" }
}
