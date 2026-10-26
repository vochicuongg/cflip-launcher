package com.cuong.cflip.mirror

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.cuong.cflip.service.ScreenMirrorService

/**
 * vc32: dedicated MediaProjection permission activity, like 9Flip's
 * MirrorPermissionActivity in architecture only (own code, no copy).
 * The panel Mirror tile launches this on the main display; the system
 * capture dialog appears there, the user approves, and this activity
 * hands the one-shot result to ScreenMirrorService and finishes.
 * If the user declines, it just toasts and finishes — no mirror starts.
 */
class MirrorPermissionActivity : ComponentActivity() {
    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            // vc45: DO NOT hide the cover UI any more. vc44 (and
            // earlier) sent ACTION_HIDE_COVER here, which removed
            // the AssistiveTouch bubble - the only control that
            // could open the panel to stop the mirror again. The
            // user then saw "mất luôn AssistiveTouch": mirror on,
            // no bubble, no way back from the cover. Now the bubble
            // stays, and MirrorPresentation itself carries a
            // "Dừng phản chiếu" button as the primary exit, with
            // ScreenMirrorService restoring the bubble on stop as
            // a second path. The panel itself was already hidden
            // by the tile before this activity launched.
            // No hide intent is sent any more.
            val start = Intent(this, ScreenMirrorService::class.java).apply {
                action = ScreenMirrorService.ACTION_START
                putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, data)
            }
            // vc57 DUAL SETTLE: 9Flip does not start its projection
            // service the instant permission is granted - its dual
            // host waits for display reparenting (up to 2s) first.
            // CFlip vc51-vc56 started the service immediately on
            // approve, so the service gate could check main display
            // state before dual had finished powering it on, and
            // refuse a mirror that would have worked a second
            // later. Wait 2000ms after grant before starting the
            // service (panel already waited ~2000ms before the
            // dialog, so total settle is ~4-5s including dialog
            // time). Do NOT finish the activity until after the
            // delayed start, or the delayed runnable could be
            // lost with the activity.
            Toast.makeText(this, "Đang phản chiếu lên cover", Toast.LENGTH_SHORT).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                // vc68: if the user backed out during this 2s
                // delay, do not start a service they no longer
                // want (onDestroy below already turned dual off).
                if (!isFinishing) {
                    serviceStarted = true
                    ContextCompat.startForegroundService(this, start)
                    // vc70 BRIDGE (decompile round 4): 9Flip has no
                    // such gap because its Presentation (floating
                    // menu) is already on the cover before mirror
                    // starts, and mirror only swaps content inside
                    // it. CFlip creates Presentation/overlay after
                    // grant, and the service waits up to 3s for the
                    // surface (vc59 fallback). If this activity
                    // finishes now, the cover has no window in dual
                    // mode, the system turns it off after 1-2s, and
                    // projection onStop kills the mirror - the vc69
                    // disconnect. Stay alive 4s more with
                    // KEEP_SCREEN_ON, then finish; Presentation/
                    // overlay keeps the cover on from there. If the
                    // service failed, onDestroy/service cleanup
                    // still disable dual and restore the bubble.
                    // vc75 MAIN HOST (9Flip host activity, architecture
                    // only, no copied code): vc70 bridge just finished
                    // after 4s, leaving NO activity alive on main during
                    // mirror. 9Flip keeps its 1x1 host top-resumed on main
                    // for the whole mirror, which keeps main display ON so
                    // AUTO_MIRROR has a live main UI to capture (folded
                    // main would otherwise stay OFF and feed stays black).
                    // Launch a host-only instance on main (display 0) now,
                    // then finish this cover instance (serviceStarted is
                    // true, so onDestroy will NOT disable dual). Launch
                    // failure is ignored: mirror tries without host, like vc74.
                    // vc76: host was already launched early (see
                    // enableDualThenCapture); this is only a retry if
                    // early launch failed (flag still false). Then
                    // finish cover instance as before (serviceStarted
                    // is true, so onDestroy will NOT disable dual).
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!isFinishing) {
                            launchMainHost()
                            finishAndRemoveTask()
                        }
                    }, 4000)
                }
            }, 2000)
        } else {
            Toast.makeText(this, "Bạn chưa đồng ý phản chiếu màn hình", Toast.LENGTH_SHORT).show()
            // vc51: user declined capture - turn dual back off, do not leave system in dual mode.
            runCatching { com.cuong.cflip.service.DualDisplayHelper.disable() }
            // vc34: remove the task entirely, so this never looks like
            // re-entering the CFlip app in recents / task stack.
            // Grant path finishes inside its delayed block (vc57),
            // not here, so the delayed service start is not lost.
            finishAndRemoveTask()
        }
    }

    // vc67: guards a second enableDualThenCapture call (e.g.
    // onNewIntent arriving while the first settle delay is still
    // pending) from scheduling a duplicate capture dialog.
    private var dualCaptureScheduled = false
    // vc68: true only after the grant branch actually starts
    // the mirror service. onDestroy uses it to tell "user
    // backed out, turn dual off" apart from "mirror just
    // started, do NOT turn dual off".
    private var serviceStarted = false
    // vc69: true only while this instance is handing over to a
    // fresh instance on the cover (reparented to main case).
    // onDestroy must NOT disable dual then (the new instance
    // needs it) and must NOT restore the bubble yet (mirror
    // setup is still in progress).
    private var relaunchingOnCover = false
    // vc75 MAIN HOST: true only for the host-only instance launched
    // on main after grant (see grant branch). It never enables dual
    // and never shows a dialog; it just stays resumed on main to
    // keep main display ON for AUTO_MIRROR, polling MirrorState
    // until mirror stops, then finishes.
    private var hostOnly = false
    // vc76: static flag so relaunch (cover reparent) and grant
    // retry do not launch duplicate main hosts. Reset in onDestroy
    // of the host instance, so the next mirror attempt can launch
    // a fresh host. Launch sets it true only on success, so a
    // failed launch can be retried in the grant branch.
    companion object {
        @Volatile var mainHostLaunched: Boolean = false
    }
    // vc76: launch the main-side host activity (display 0). Best
    // effort: failure is ignored, mirror tries without host (vc74
    // behavior) and the grant branch retries once.
    private fun launchMainHost(): Boolean {
        if (mainHostLaunched) return true
        val launched = runCatching {
            val host = android.content.Intent(this, MirrorPermissionActivity::class.java)
                .putExtra("cflip_host_only", true)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()
            startActivity(host, opts)
        }.isSuccess
        if (launched) mainHostLaunched = true
        return launched
    }
    // vc76 DUAL-BASED POLLING (replaces vc75 active-based grace):
    // Host must stay alive while dual is ON, even before service
    // starts (dialog pending, grant pending) - vc75 active-based
    // grace (5s+5s) could finish the host before service ever set
    // active=true, killing dual early. Dual ON = stay, dual OFF =
    // mirror ended or declined = finish. No grace needed because
    // host is launched only after dual is ON.
    private fun startHostPolling() {
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                if (!com.cuong.cflip.service.DualDisplayHelper.isDualOn()) {
                    finishAndRemoveTask()
                } else {
                    startHostPolling()
                }
            }
        }, 1000)
    }

    // vc67 HOST-BEFORE-DUAL (9Flip decompile, architecture only,
    // no copied code): 9Flip toggles dual INSIDE a 1x1
    // NOT_TOUCH_MODAL transparent host activity that is already
    // on the cover, registers the SCREEN_OFF receiver, and only
    // then shows its cover UI. CFlip vc51-vc66 did the opposite:
    // the tile enabled dual from a panel overlay (no activity
    // alive), then waited 2000ms with nothing alive on the cover
    // in dual mode - that is the 1-2s window in the vc66 repro
    // (panel -> mirror -> wait 1-2s -> screen off completely),
    // before the permission activity even exists. New order:
    // this activity is launched on the cover FIRST (the tile no
    // longer enables dual), it enables dual itself while its own
    // window is alive on the cover, waits for dual to settle,
    // then shows the capture dialog. "dual already on" from a
    // previous attempt is not a failure: settle briefly, then
    // show the dialog. On dual failure, toast + finish without
    // a dialog; dual was never turned on, so nothing to disable.
    // Decline still disables dual (existing branch below).
    private fun enableDualThenCapture() {
        if (dualCaptureScheduled) return
        dualCaptureScheduled = true
        val dualRes = com.cuong.cflip.service.DualDisplayHelper.enable()
        if (dualRes.isFailure) {
            Toast.makeText(this, "Dual display failed: " + (dualRes.exceptionOrNull()?.message ?: "unknown"), Toast.LENGTH_LONG).show()
            finishAndRemoveTask()
            return
        }
        // vc76 EARLY HOST (9Flip host-before-dialog, architecture
        // only): vc75 launched the main host only AFTER grant + 2s
        // + service start + 4s bridge, so during dialog/grant/service
        // start there was NO main activity and dual could time out
        // at ~2s, dismissing the cover Presentation - exactly the
        // vc75 symptom (window shows then disappears after ~2s).
        // 9Flip host is alive BEFORE dialog and stays throughout.
        // Launch main host NOW, right after dual is ON, so it is
        // alive during settle + dialog + grant + service start.
        // Failure is ignored (grant branch retries once).
        launchMainHost()
        val settleMs = if (dualRes.getOrNull() == "dual already on") 500L else 2000L
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (!isFinishing) {
                // vc69: dual can reparent this activity from the
                // cover to main. A dialog launched from main would
                // appear on main (folded user sees nothing on the
                // cover, bubble gone, cover unresponsive). If this
                // activity is no longer on the cover, hand over to
                // a fresh instance launched on the cover instead.
                val cover = runCatching { com.cuong.cflip.display.DisplayRepository(this).secondaryDisplay() }.getOrNull()
                val myDisplayId = display?.displayId
                if (cover != null && myDisplayId != null && myDisplayId != cover.displayId) {
                    relaunchingOnCover = true
                    val relaunch = android.content.Intent(this, MirrorPermissionActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    val opts = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(cover.displayId).toBundle()
                    runCatching { startActivity(relaunch, opts) }
                    finishAndRemoveTask()
                } else {
                    launchCapture()
                }
            }
        }, settleMs)
    }

    // vc62 SECOND-START DEFENSE, updated vc67: if the system
    // still reuses this activity, go through the same
    // enable-then-dialog flow (guarded against duplicates),
    // not straight to launchCapture, so a reused activity
    // cannot skip the dual step.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // vc75: host-only instance must never enable dual or dialog.
        if (hostOnly) return
        enableDualThenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // vc69 HOST WINDOW (9Flip host window flags, standard
        // Android APIs only, no copied code): this activity has
        // no UI of its own, so a normal full-screen window would
        // block cover touches and hide bubble/panel below it while
        // waiting for the dialog - the vc68 "no dialog, no
        // bubble, cover unresponsive" trap. Make it a 1x1
        // transparent NOT_TOUCH_MODAL window like 9Flip's dual
        // host, so it stays alive to launch the dialog without
        // blocking the cover underneath.
        setFinishOnTouchOutside(false)
        window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
        window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
        // vc70: 1x1 window alone does not keep the cover on.
        // Add KEEP_SCREEN_ON so this bridging window holds the
        // cover on until the service's Presentation/overlay
        // (both already have KEEP) is actually showing.
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.setLayout(1, 1)
        window?.setGravity(android.view.Gravity.TOP or android.view.Gravity.START)
        // vc75 MAIN HOST: host-only instance on main skips dual +
        // dialog entirely; it just stays resumed (full-screen, KEEP)
        // and polls dual until mirror stops, then finishes.
        // Its onDestroy only resets mainHostLaunched (vc82: host never
        // disables dual, service cleanup owns dual + bubble restore).
        if (intent?.getBooleanExtra("cflip_host_only", false) == true) {
            hostOnly = true
            // vc77 FULL-SCREEN HOST: vc76 early 1x1 host kept the
            // mirror window staying (dual timeout fixed) but feed
            // stayed black - main may still be OFF because system
            // does not power main ON for a 1x1 activity when folded.
            // A full-screen transparent NOT_TOUCHABLE host on main is
            // a real resumed full UI, so system must render main
            // display (and AUTO_MIRROR can capture it), while
            // transparency + not-touchable keeps underlying main UI
            // visible and interactive. Cover permission instance
            // keeps 1x1 (this branch is host-only only).
            window?.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            startHostPolling()
            return
        }
        enableDualThenCapture()
    }

    // vc68 SEPARATION: this activity is the only place that
    // enables dual in the vc67 flow. If it dies without
    // starting the service (back press, system destroy,
    // swipe-away) and without an explicit decline (which
    // already disables dual), dual would stay ON with no
    // service and no activity, and cover overlays (panel +
    // widget) stay hidden in dual mode - that is how a mirror
    // attempt can "break" widget without touching any widget
    // code. onDestroy turns dual off in exactly that case.
    // Grant path sets serviceStarted=true before starting the
    // service, so a just-started mirror is NOT killed by its
    // own activity finishing. Decline already disabled dual,
    // so a second disable here is idempotent. Config-change
    // recreation is safe: the new instance re-enables dual
    // ("dual already on" -> brief settle -> dialog).
    override fun onDestroy() {
        // vc82 HOST NEVER OWNS DUAL: before vc82, host onDestroy fell
        // through to the !serviceStarted branch below (host never sets
        // serviceStarted, never sets relaunchingOnCover), so ANY host
        // death while dual was ON disabled dual — killing mirror
        // before the service gate (vc81 pill dualOn=false, main OFF,
        // cover ON, cover-sized: only dual failed). Host is a
        // keep-alive, not the dual owner; only the cover instance
        // (service not started, not relaunching) and service cleanup
        // may disable dual. Host death only frees the next attempt
        // to launch a fresh host.
        if (hostOnly) {
            mainHostLaunched = false
            super.onDestroy()
            return
        }
        // vc69: relaunching on cover must NOT disable dual (the
        // new instance needs it) and must NOT restore the bubble
        // yet (mirror setup is still in progress).
        if (!serviceStarted && !relaunchingOnCover) {
            runCatching { com.cuong.cflip.service.DualDisplayHelper.disable() }
            // vc69: dual off alone does not bring the bubble back.
            // Tell CoverForegroundService explicitly, like service
            // cleanup does, so a failed/backed-out mirror never
            // leaves the user with no AssistiveTouch and no cover UI.
            runCatching {
                applicationContext.startService(android.content.Intent(applicationContext, com.cuong.cflip.service.CoverForegroundService::class.java).apply {
                    action = com.cuong.cflip.service.CoverForegroundService.ACTION_ROTATION_CHANGED
                })
            }
        }
        super.onDestroy()
    }

    private fun launchCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            Toast.makeText(this, "Thiết bị không hỗ trợ phản chiếu", Toast.LENGTH_SHORT).show()
            finishAndRemoveTask(); return
        }
        // vc47 config intent (9Flip approach): on Android 14+
        // the capture intent should be built from
        // MediaProjectionConfig.createConfigForDefaultDisplay(),
        // which explicitly says "capture the DEFAULT (main)
        // display". The old no-config intent is kept as fallback
        // for API < 34. This is the modern path Samsung's One UI
        // 9 (Android 16+) expects for a mirror capture.
        val captureIntent = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val config = android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
            mpm.createScreenCaptureIntent(config)
        } else {
            mpm.createScreenCaptureIntent()
        }
        captureLauncher.launch(captureIntent)
    }
}
