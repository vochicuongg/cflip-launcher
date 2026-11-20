package com.cuong.cflip.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
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
 * vc246 CASSETTE (the reference app: CoverCassette): the layout is a tape — state
 * eyebrow, title/artist, a tape window (two reels + tape line), a
 * progress bar with elapsed/total, and a big mint play-pause button.
 * open layoutRes so MusicSmallWidgetProvider reuses this whole class
 * with the compact layout; broadcasts target javaClass so control
 * taps land back on the right subclass.
 */
open class MusicWidgetProvider : AppWidgetProvider() {

    protected open val layoutRes: Int = R.layout.widget_music

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val repo = MediaControllerRepository(context)
        runCatching { repo.refresh() }
        val info = MediaState.current.value
        for (id in ids) {
            val views = RemoteViews(context.packageName, layoutRes)
            views.setTextViewText(R.id.music_title, info?.title ?: "Chưa có nhạc")
            views.setTextViewText(
                R.id.music_artist,
                info?.artist?.takeIf { it.isNotBlank() } ?: "Mở nhạc rồi bấm làm mới",
            )
            // vc246 CASSETTE: state eyebrow (text, not colour alone),
            // progress bar + elapsed/total (MediaInfo carries both),
            // big mint play-pause (white icon -> black on mint).
            // Ids absent from the small layout are no-ops in RemoteViews.
            views.setTextViewText(
                R.id.music_state,
                when {
                    info == null -> "CHƯA CÓ NHẠC"
                    info.isPlaying -> "ĐANG PHÁT"
                    else -> "TẠM DỪNG"
                },
            )
            val hasProgress = info != null && info.durationMs > 0
            views.setViewVisibility(
                R.id.music_progress_row,
                if (hasProgress) View.VISIBLE else View.GONE,
            )
            if (hasProgress && info != null) {
                val pos = info.positionMs.coerceIn(0L, info.durationMs)
                views.setProgressBar(
                    R.id.music_progress, 1000,
                    (pos * 1000L / info.durationMs).toInt(), false,
                )
                views.setTextViewText(
                    R.id.music_time, "${fmtTime(pos)} / ${fmtTime(info.durationMs)}",
                )
            }
            // vc210: round icon button now - play/pause is an icon, not text.
            views.setImageViewResource(
                R.id.music_playpause,
                if (info?.isPlaying == true) R.drawable.ic_w_pause else R.drawable.ic_w_play,
            )
            views.setInt(R.id.music_playpause, "setColorFilter", 0xFF000000.toInt())
            fun broadcast(requestCode: Int, action: String): PendingIntent {
                return PendingIntent.getBroadcast(
                    context, requestCode,
                    Intent(context, javaClass).setAction(action),
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
                android.content.ComponentName(context, javaClass))
            if (ids.isNotEmpty()) onUpdate(context, mgr, ids)
        }.start()
    }

    companion object {
        const val ACTION_PREV = "com.cuong.cflip.widget.MUSIC_PREV"
        const val ACTION_PLAYPAUSE = "com.cuong.cflip.widget.MUSIC_PLAYPAUSE"
        const val ACTION_NEXT = "com.cuong.cflip.widget.MUSIC_NEXT"
        const val ACTION_REFRESH = "com.cuong.cflip.widget.MUSIC_REFRESH"

        /**
         * vc247: the music widgets had no requestUpdate at all (the
         * clock one existed but nothing ever called it) — that is
         * why they went stale and looked dead. Refresh BOTH classes:
         * each provider owns its own widget ids, so each is refreshed
         * with an instance of its own class (layoutRes + the javaClass
         * used for the control broadcasts follow the instance).
         * Cheap and loop-free: onUpdate only writes RemoteViews, it
         * never posts a notification or rebuilds the cover panel.
         */
        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val providers = listOf(
                MusicWidgetProvider() to MusicWidgetProvider::class.java,
                MusicSmallWidgetProvider() to MusicSmallWidgetProvider::class.java,
            )
            for ((provider, cls) in providers) {
                val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
                if (ids.isNotEmpty()) provider.onUpdate(context, mgr, ids)
            }
        }

        private fun fmtTime(ms: Long): String =
            "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
    }
}
