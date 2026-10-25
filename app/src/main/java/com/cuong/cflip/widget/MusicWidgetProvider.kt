package com.cuong.cflip.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.cuong.cflip.MainActivity
import com.cuong.cflip.R
import com.cuong.cflip.media.MediaControllerRepository
import com.cuong.cflip.media.MediaState

/**
 * Cover Music (cassette) widget (new in 2.5.0): standard Android widget showing
 * the active media session's title/artist and Prev / Play-Pause / Next.
 * Controls go through MediaControllerRepository, so they need Notification
 * Access (the same permission as the cover Media tab). Tapping the title
 * opens CFlip at the Media tab.
 */
class MusicWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val repo = MediaControllerRepository(context)
        runCatching { repo.refresh() }
        val info = MediaState.current.value
        for (id in ids) {
            val views = RemoteViews(context.packageName, R.layout.widget_music)
            views.setTextViewText(R.id.music_title, info?.title ?: "Chưa có nhạc")
            views.setTextViewText(
                R.id.music_artist,
                info?.artist?.takeIf { it.isNotBlank() } ?: "Mở nhạc rồi bấm làm mới",
            )
            views.setTextViewText(
                R.id.music_playpause,
                if (info?.isPlaying == true) "⏸ Tạm dừng" else "▶ Phát",
            )
            fun broadcast(requestCode: Int, action: String): PendingIntent {
                return PendingIntent.getBroadcast(
                    context, requestCode,
                    Intent(context, MusicWidgetProvider::class.java).setAction(action),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
            views.setOnClickPendingIntent(R.id.music_prev, broadcast(301, ACTION_PREV))
            views.setOnClickPendingIntent(R.id.music_playpause, broadcast(302, ACTION_PLAYPAUSE))
            views.setOnClickPendingIntent(R.id.music_next, broadcast(303, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.music_refresh, broadcast(304, ACTION_REFRESH))
            val coverId = com.cuong.cflip.display.DisplayRepository(context).secondaryDisplay()?.displayId
            val opts = coverId?.let {
                android.app.ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle()
            }
            val open = PendingIntent.getActivity(
                context, 2000 + id,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("cflip_show_cover", true)
                    .putExtra("cflip_cover_tab", "MEDIA"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                opts,
            )
            views.setOnClickPendingIntent(R.id.music_title, open)
            manager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action !in setOf(ACTION_PREV, ACTION_PLAYPAUSE, ACTION_NEXT, ACTION_REFRESH)) return
        val pending = goAsync()
        Thread {
            try {
                val repo = MediaControllerRepository(context)
                when (action) {
                    ACTION_PREV -> repo.previous()
                    ACTION_PLAYPAUSE -> repo.playPause()
                    ACTION_NEXT -> repo.next()
                    ACTION_REFRESH -> repo.refresh()
                }
            } finally { pending.finish() }
            // Refresh after the control settles, on the main thread path.
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(
                android.content.ComponentName(context, MusicWidgetProvider::class.java))
            if (ids.isNotEmpty()) onUpdate(context, mgr, ids)
        }.start()
    }

    companion object {
        const val ACTION_PREV = "com.cuong.cflip.widget.MUSIC_PREV"
        const val ACTION_PLAYPAUSE = "com.cuong.cflip.widget.MUSIC_PLAYPAUSE"
        const val ACTION_NEXT = "com.cuong.cflip.widget.MUSIC_NEXT"
        const val ACTION_REFRESH = "com.cuong.cflip.widget.MUSIC_REFRESH"
    }
}
