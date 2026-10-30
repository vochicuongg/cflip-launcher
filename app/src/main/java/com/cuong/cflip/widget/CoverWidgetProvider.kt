package com.cuong.cflip.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.RemoteViews
import com.cuong.cflip.MainActivity
import com.cuong.cflip.R
import com.cuong.cflip.control.QuickControls
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.data.FavoritesStore
import com.cuong.cflip.display.AppLauncher
import com.cuong.cflip.display.DisplayRepository
import com.cuong.cflip.media.MediaControllerRepository
import com.cuong.cflip.media.MediaState
import com.cuong.cflip.service.NotificationState

/**
 * Combined home-screen widget (2.5.0 note: this provider is for the main
 * home screen's widget picker only. Cover widgets live inside CFlip's own
 * cover UI and do not need any external host.) It is a working
 * mini-launcher, not just a tile: live clock, notification count, up to 4
 * favourite-app shortcuts (launched toward the cover display), flashlight
 * toggle and media play/pause + next.
 */
class CoverWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, buildViews(context, id))
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TORCH -> {
                val next = !QuickControls.torchOn
                QuickControls.setTorch(context, next)
                requestUpdate(context)
            }
            ACTION_MEDIA_PLAYPAUSE, ACTION_MEDIA_NEXT -> {
                val next = intent.action == ACTION_MEDIA_NEXT
                val pending = goAsync()
                Thread {
                    try {
                        val repo = MediaControllerRepository(context)
                        repo.refresh()
                        if (next) repo.next() else repo.playPause()
                    } finally { pending.finish() }
                }.start()
            }
            ACTION_LAUNCH_APP -> {
                val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
                val coverId = DisplayRepository(context).secondaryDisplay()?.displayId
                AppLauncher.launch(context, pkg, coverId)
            }
        }
    }

    private fun buildViews(context: Context, widgetId: Int = 0): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_cover)

        // 3.5.0: distinct requestCode + cover display, like Clock/Music.
        val coverId = DisplayRepository(context).secondaryDisplay()?.displayId
        val opts = coverId?.let {
            android.app.ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle()
        }
        val openApp = PendingIntent.getActivity(
            context, 3000 + widgetId,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            opts,
        )
        views.setOnClickPendingIntent(R.id.widget_root, openApp)

        // vc210: bell is an icon in the layout now; torch/play are round icon buttons.
        views.setTextViewText(R.id.widget_notif, "${NotificationState.active.value.size}")
        views.setImageViewResource(R.id.btn_torch, R.drawable.ic_w_torch)
        views.setInt(R.id.btn_torch, "setBackgroundResource",
            if (QuickControls.torchOn) R.drawable.bg_widget_circle_active else R.drawable.bg_widget_circle)
        views.setInt(R.id.btn_torch, "setColorFilter",
            if (QuickControls.torchOn) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        views.setImageViewResource(R.id.btn_playpause,
            if (MediaState.current.value?.isPlaying == true) R.drawable.ic_w_pause else R.drawable.ic_w_play)

        fun broadcast(requestCode: Int, action: String, pkg: String? = null): PendingIntent {
            val i = Intent(context, CoverWidgetProvider::class.java).setAction(action)
            if (pkg != null) i.putExtra(EXTRA_PKG, pkg)
            return PendingIntent.getBroadcast(
                context, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        views.setOnClickPendingIntent(R.id.btn_torch, broadcast(201, ACTION_TORCH))
        views.setOnClickPendingIntent(R.id.btn_playpause, broadcast(202, ACTION_MEDIA_PLAYPAUSE))
        views.setOnClickPendingIntent(R.id.btn_next, broadcast(203, ACTION_MEDIA_NEXT))

        val slotIds = listOf(R.id.fav_slot_1, R.id.fav_slot_2, R.id.fav_slot_3, R.id.fav_slot_4)
        val iconIds = listOf(R.id.fav_icon_1, R.id.fav_icon_2, R.id.fav_icon_3, R.id.fav_icon_4)
        val labelIds = listOf(R.id.fav_label_1, R.id.fav_label_2, R.id.fav_label_3, R.id.fav_label_4)

        val byPackage = AppRepository(context).loadLaunchableApps().associateBy { it.packageName }
        val favorites = FavoritesStore(context).getFavorites().mapNotNull { byPackage[it] }.take(4)
        for (i in 0 until 4) {
            val app = favorites.getOrNull(i)
            if (app == null) {
                views.setViewVisibility(slotIds[i], View.GONE)
            } else {
                views.setViewVisibility(slotIds[i], View.VISIBLE)
                // Old bug: toBitmap() set bounds on the shared PackageManager
                // drawable itself. Copy + mutate first, like the bubble does.
                app.icon?.let { icon ->
                    val copy = icon.constantState?.newDrawable()?.mutate() ?: icon
                    views.setImageViewBitmap(iconIds[i], copy.toBitmap(96))
                }
                views.setTextViewText(labelIds[i], app.label)
                views.setOnClickPendingIntent(slotIds[i], broadcast(100 + i, ACTION_LAUNCH_APP, app.packageName))
            }
        }
        return views
    }

    private fun Drawable.toBitmap(sizePx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, sizePx, sizePx)
        draw(canvas)
        return bmp
    }

    companion object {
        const val ACTION_TORCH = "com.cuong.cflip.widget.TORCH"
        const val ACTION_MEDIA_PLAYPAUSE = "com.cuong.cflip.widget.MEDIA_PLAYPAUSE"
        const val ACTION_MEDIA_NEXT = "com.cuong.cflip.widget.MEDIA_NEXT"
        const val ACTION_LAUNCH_APP = "com.cuong.cflip.widget.LAUNCH_APP"
        const val EXTRA_PKG = "pkg"

        /** Push a fresh render (clock is self-updating via TextClock; this refreshes
         *  counts, torch state and favourites) - called by the notification listener. */
        fun requestUpdate(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, CoverWidgetProvider::class.java))
            if (ids.isNotEmpty()) CoverWidgetProvider().onUpdate(context, mgr, ids)
        }
    }
}
