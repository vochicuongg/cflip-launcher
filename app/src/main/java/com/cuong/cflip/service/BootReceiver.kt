package com.cuong.cflip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cuong.cflip.data.SettingsStore

/**
 * vc150 BOOT RESTORE (user, 29/10): after a reboot - or an app
 * update, which also kills the service - bring CFlip back exactly
 * as the user left it, and nothing more:
 *
 * - Quick Panel switch ON (SettingsStore.assistiveTouchEnabled)
 *   -> start CoverForegroundService, which shows the opener
 *   (bubble or bar, per the saved mode) through its normal
 *   tryShowOnSecondary guards (cover present, not locked, no
 *   mirror, panel closed). If the phone is still locked when the
 *   broadcast arrives, the service starts but the opener stays
 *   hidden per the lock rule, and the existing screen-on/unlock
 *   trigger shows it once the user unlocks.
 * - Quick Panel switch OFF -> do nothing: no service, no opener.
 *
 * - Widget switch (coverWidgets "appgrid") needs no boot action:
 *   the setting already persists in SharedPreferences and the
 *   panel re-reads it on every open, so once the opener is back,
 *   the widget appears exactly as before. The receiver only
 *   re-checks the widget key in its log line; it does not (and
 *   must not) force the panel open at boot.
 *
 * Force-stop is deliberately NOT covered: Android blocks any
 * restart after the user force-stops an app until the user opens
 * it again - no receiver can work around that, by design.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext
        val settings = runCatching { SettingsStore(appContext).load() }.getOrNull() ?: return
        Log.i(
            "CFlipBoot",
            "boot restore: action=$action quickPanel=${settings.assistiveTouchEnabled} widgets=${settings.coverWidgets}"
        )
        if (!settings.assistiveTouchEnabled) return
        runCatching {
            appContext.startForegroundService(Intent(appContext, CoverForegroundService::class.java))
        }.onFailure { Log.w("CFlipBoot", "could not restart cover service", it) }
    }
}
