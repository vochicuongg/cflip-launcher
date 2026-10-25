package com.cuong.cflip.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Samsung cover-widget flow, decompiled from 9Flip v149 (o6/n0, t5.h.f).
 *
 * 9Flip NEVER uses requestPinAppWidget() for its cover widget. Pin is
 * the main-home launcher's dialog: it always says "Mở điện thoại để
 * tiếp tục", and the widget lands on the main home, never on the cover.
 * That is exactly the bug in the screenshots.
 *
 * 9Flip's only add flow is:
 *   openCoverWidgetSettings() -> Samsung's own activity
 *   com.samsung.android.app.aodservice /
 *   com.samsung.android.app.subui.settings.multiwidget.SubLauncherMultiWidgetSettingsActivity
 *   (flags NEW_TASK | CLEAR_TASK = 268468224, resolve-checked, else
 *   the "unsupported" toast).
 * After the user picks the widget there and folds the phone, the widget
 * is on the cover. The cover-only picker (folded phone, swipe cover
 * widgets to the end, tap +) is Samsung's own UI and lists exactly the
 * widgets registered with the Samsung sub_screen meta-data + keyguard
 * category — no CFlip code can open that picker directly, so CFlip's
 * job is to register correctly (done in the manifest + xml files) and
 * to stop directing the user to the main-home pin dialog.
 *
 * Status check (t5.h.f, simplified): a widget instance counts as a
 * cover instance when its options say appWidgetCategory == 2 (keyguard)
 * or 0, on display 1. CFlip reports the raw counts + cover counts so
 * the user can verify from the cover, without unfolding.
 */
object SamsungCoverWidgetHelper {
    private const val SAMSUNG_PKG = "com.samsung.android.app.aodservice"
    private const val SAMSUNG_ACTIVITY =
        "com.samsung.android.app.subui.settings.multiwidget.SubLauncherMultiWidgetSettingsActivity"

    fun openCoverWidgetSettings(context: Context): Boolean {
        val intent = Intent().apply {
            component = ComponentName(SAMSUNG_PKG, SAMSUNG_ACTIVITY)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            Toast.makeText(
                context,
                "Thiết bị này không hỗ trợ Cài đặt Widget màn phụ Samsung.",
                Toast.LENGTH_SHORT,
            ).show()
            return false
        }
        context.applicationContext.startActivity(intent)
        return true
    }

    data class ProviderStatus(val label: String, val total: Int, val cover: Int)

    fun status(context: Context): List<ProviderStatus> {
        val mgr = AppWidgetManager.getInstance(context)
        val providers = listOf(
            "Lưới ứng dụng" to AppGridWidgetProvider::class.java,
            "Nhạc" to MusicWidgetProvider::class.java,
            "Đồng hồ & Pin" to ClockWidgetProvider::class.java,
            "Tổng hợp" to CoverWidgetProvider::class.java,
        )
        return providers.map { (label, cls) ->
            val ids = mgr.getAppWidgetIds(ComponentName(context, cls))
            var cover = 0
            for (id in ids) {
                val opts = runCatching { mgr.getAppWidgetOptions(id) }.getOrNull()
                val cat = opts?.getInt("appWidgetCategory", -1) ?: -1
                if (cat == 2 || cat == 0) cover++
            }
            ProviderStatus(label, ids.size, cover)
        }
    }

    fun statusText(context: Context): String {
        val parts = status(context).filter { it.total > 0 }
        return if (parts.isEmpty()) {
            "Chưa thêm Widget Samsung nào. Gập máy, vuốt Widget màn phụ tới cuối, bấm +, chọn Widget CFlip — hoặc mở Cài đặt Widget Samsung để chọn."
        } else {
            parts.joinToString("; ") { "${it.label}: ${it.cover}/${it.total} trên màn phụ" }
        }
    }
}
