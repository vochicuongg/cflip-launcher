package com.cuong.cflip.cover
import com.cuong.cflip.R

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.cuong.cflip.control.QuickControls
import com.cuong.cflip.data.SettingsStore
import com.cuong.cflip.media.MediaControllerRepository
import com.cuong.cflip.media.MediaState
import com.cuong.cflip.service.MyAccessibilityService
import com.cuong.cflip.service.NotificationController
import com.cuong.cflip.service.NotificationState

/**
 * CFlip's own Control Center panel on the cover display (new in 2.6.0).
 *
 * Clean-room functional parity with the 9Flip panel's *functions*, not its
 * layout, icons or text: a full-screen overlay on the secondary display's
 * WindowManager (same route as CoverOverlay, no external host), with:
 * - a notification banner (latest active notification; tap opens it,
 *   X only hides it from this panel — it does NOT cancel the system
 *   notification, because that link was never verified for 9Flip either);
 * - media controls (Prev / Play-Pause / Next) from the active session;
 * - brightness slider (manual, clamped to 0.05–1.0 like 9Flip's, i.e. 13–255)
 *   and music-volume slider, applied on release, not on every step;
 * - a 5-column tile grid: torch, Wi-Fi panel, Bluetooth settings, Internet
 *   panel, NFC panel, Lock, Recents, Notifications shade, Quick Settings,
 *   Device Settings, Rotate (cycles 0/90/180/270), Sort (cycles A-Z /
 *   recently opened / newly installed), Columns (cycles auto/3/4/5/6),
 *   Labels on/off, Clear favorites, Mirror (see below).
 * - auto-dismiss after 60s, reset on every tile tap; Close button.
 *
 * Tiles that Android does not let a third-party app toggle directly
 * (Wi-Fi, Bluetooth, NFC, …) open the system panel/settings page instead
 * of pretending to toggle — that is the supported Android route.
 * Mirror cannot start from a plain overlay view because MediaProjection
 * needs a permission activity flow, so the Mirror tile only stops an
 * active mirror and otherwise tells the user to start it from the
 * cover Quick tab, where that flow already exists. No tile here opens
 * any external cover app.
 */
object CoverControlCenterManager {
    private const val TAG = "CFlipControlCenter"
    private const val AUTO_DISMISS_MS = 60_000L

    private var wm: WindowManager? = null
    private var view: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    // vc109 BUBBLE-IN-PANEL FIX (user, 27/10):
    // the bubble is a 2032 accessibility overlay,
    // the panel is a 2038 application overlay, so
    // the bubble always drew OVER the panel and
    // looked like a second bubble inside the panel.
    // Fix: hide the bubble while the panel is open
    // and restore it synchronously when the panel
    // closes. Synchronous restore (not a service
    // intent) matters: show() calls hidePanelOnly()
    // first, and an async restore posted there
    // would land AFTER the new panel hides the
    // bubble again, bringing the duplicate back.
    private var bubbleHiddenForPanel = false
    private var indicatorHiddenForPanel = false
    private var lastPanelDisplay: Display? = null
    private var lastPanelAppContext: Context? = null
    // vc110 PANEL CACHE (user, 27/10: first open
    // loads, later opens must have NO loading or
    // loading < 1s): the built panel root is kept
    // in memory after close, and re-added on the
    // next open instead of rebuilding ~100 views
    // + media IPC + settings reads. Reuse is ONLY
    // allowed when the layout decisions made at
    // build time are still true: same display +
    // size, same media existence + playing state
    // (vc108 card position + vc105 slider
    // orientation depend on those), and same
    // layout settings (columns, labels, widgets,
    // sort, rotate, background). If any of those
    // changed, the cache is discarded and the
    // panel rebuilds with loading, like the first
    // open, so speed never buys a stale layout.
    // Media text/progress, brightness/volume,
    // notifications and apps are NOT frozen:
    // reopen refreshes media + sliders instantly,
    // and the lazy notif/apps tabs are cleared so
    // the next tab switch rebuilds them fresh.
    // Tile mint/dark highlights are the one thing
    // that stays from the last open until tapped
    // (tap handlers re-read current state, so
    // function stays correct, only the highlight
    // can lag one open).
    private var cachedPanelRoot: View? = null
    private var cachedPanelDisplayId: Int = -1
    private var cachedPanelDisplayW: Int = 0
    private var cachedPanelDisplayH: Int = 0
    private var cachedMediaExists: Boolean = false
    private var cachedMediaPlaying: Boolean = false
    private var cachedSettingsKey: String = ""
    // vc111: panel cache must also track widget
    // inputs, otherwise a cached panel would show
    // a stale appgrid widget after an install /
    // pin / hide change. App list version comes
    // from AppRepository (increments on package
    // broadcast clear); favorites + hidden are
    // read per check (cheap SharedPreferences).
    private var cachedAppListVersion: Int = -1
    private var cachedFavoritesKey: String = ""
    private var cachedHiddenKey: String = ""
    // vc120: the swipe indicator is hidden for
    // the panel the same way the bubble is (see
    // indicatorHiddenForPanel below, next to
    // bubbleHiddenForPanel). No Quick Bar state
    // remains - vc117's bar is deleted (user,
    // 28/10: Quick Panel is not a bar option).
    private var cachedReopen: ((Int) -> Unit)? = null
    private var forceFreshShow: Boolean = false
    private fun clearPanelCache() {
        cachedPanelRoot = null
        cachedReopen = null
        mediaCardPositionSync = null
        cachedPanelDisplayId = -1
        cachedPanelDisplayW = 0
        cachedPanelDisplayH = 0
        cachedMediaExists = false
        cachedMediaPlaying = false
        cachedSettingsKey = ""
        cachedAppListVersion = -1
        cachedFavoritesKey = ""
        cachedHiddenKey = ""
    }
    private fun settingsKeyFor(st: com.cuong.cflip.data.CoverSettings): String {
        return listOf(
            st.columns, st.showLabels, st.backgroundStyle,
            st.wallpaperUri ?: "", st.favoritesFirst,
            st.sortMode, st.coverWidgets, st.rotateMode,
        ).joinToString("|")
    }
    // vc120: panel close restores whichever
    // opener was hidden for the panel, following
    // the CURRENT open mode (not the mode at
    // open time, so a mode switch while the panel
    // is open still lands on the right opener).
    // Guards, same rules as the foreground
    // service: never restore on lock (vc101
    // no-opener-on-lock), during mirror, or
    // when the user turned the opener off.
    // In those cases the service restores the
    // opener later (unlock / mirror stop).
    private fun restoreOpenerIfHiddenForPanel() {
        if (!bubbleHiddenForPanel && !indicatorHiddenForPanel) return
        bubbleHiddenForPanel = false
        indicatorHiddenForPanel = false
        val disp = lastPanelDisplay ?: return
        val appCtx = lastPanelAppContext ?: return
        val km = runCatching { appCtx.getSystemService(android.app.KeyguardManager::class.java) }.getOrNull()
        if (km != null && km.isKeyguardLocked) return
        if (com.cuong.cflip.mirror.MirrorState.active) return
        val st = runCatching { SettingsStore(appCtx).load() }.getOrNull() ?: return
        if (!st.assistiveTouchEnabled) return
        if (st.panelOpenMode == com.cuong.cflip.data.PanelOpenMode.SWIPE_INDICATOR) {
            runCatching { CoverSwipeIndicatorManager.show(appCtx, disp) }
                .onFailure { Log.w(TAG, "Indicator restore after panel close failed", it) }
        } else {
            runCatching { CoverAssistiveTouchManager.show(appCtx, disp) }
                .onFailure { Log.w(TAG, "Bubble restore after panel close failed", it) }
        }
    }
    private var shotFeedbackView: View? = null
    private var shotFeedbackWm: WindowManager? = null

    /** Apply the panel's own brightness (3.2.0, 9Flip p6/t6 model). */
    fun applyPanelBrightness(appContext: Context) {
        val params = panelParams; val v = view; val manager = wm
        if (params == null || v == null || manager == null) return
        val st = SettingsStore(appContext).load()
        params.screenBrightness = if (st.panelBrightnessManual) st.panelBrightness.coerceIn(0.05f, 1.0f) else -1f
        runCatching { manager.updateViewLayout(v, params) }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var mediaTickRunnable: Runnable? = null
    // vc123: live media-card position sync (see updateMediaViews).
    // Assigned at the end of buildPanel, re-assigned by cachedReopen,
    // cleared on hide / cache clear so it never touches dead views.
    private var mediaCardPositionSync: ((Boolean) -> Unit)? = null
    private var dismissRunnable: Runnable? = null
    // vc44 loading intro: shown instantly on double-tap while the
    // heavy panel builds on the next main-loop turn, so the user
    // sees "CFlip Loading" in ~1 frame instead of a frozen bubble.
    private var loadingView: View? = null
    private var loadingRunnable: Runnable? = null
    // 3.0.0: no cover overlay to restore — Presentation and the
    // full-screen overlay launcher are both gone. Closing the panel
    // leaves only the AssistiveTouch button on Samsung's own cover.

    fun isShown(): Boolean = view != null

    // vc112: panel is "active" while its view is
    // shown OR while the loading intro is up
    // (view is still null during loading). The
    // foreground service must not re-show the
    // bubble while the panel is active - that is
    // how rotation / screen-on while the panel
    // is open brought the bubble back inside the
    // panel, defeating vc109's hide-on-open.
    fun isPanelActive(): Boolean = view != null || loadingView != null
    private var panelExiting: Boolean = false
    private fun animatePanelIn(root: View) {
        val d = root.resources.displayMetrics.density
        root.alpha = 0f
        root.scaleX = 0.98f
        root.scaleY = 0.98f
        root.translationY = d * 12f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(250L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    // vc120: show() is Full Panel only now.
    // Quick Panel is an open mode (bubble vs
    // swipe bar, PanelOpenMode), not a show()
    // variant - the vc117 Quick Bar routing is
    // deleted along with the bar itself.
    fun show(appContext: Context, display: Display, initialTab: Int = 0): Result<Unit> = runCatching {
        val effectiveInitialTab = initialTab
        if (display.displayId == Display.DEFAULT_DISPLAY) {
            throw IllegalArgumentException("Control Center is cover-only, refusing default display")
        }
        hidePanelOnly()
        if (forceFreshShow) {
            forceFreshShow = false
            clearPanelCache()
        }
        // vc109: hide the bubble for this panel
        // (see fields above). Stored display /
        // context are what hidePanelOnly uses to
        // restore it synchronously on close.
        lastPanelDisplay = display
        lastPanelAppContext = appContext.applicationContext
        bubbleHiddenForPanel = CoverAssistiveTouchManager.isShown()
        if (bubbleHiddenForPanel) CoverAssistiveTouchManager.hide()
        // vc120: same for the swipe indicator -
        // panel open hides whichever opener is
        // shown; panel close restores it.
        indicatorHiddenForPanel = CoverSwipeIndicatorManager.isShown()
        if (indicatorHiddenForPanel) CoverSwipeIndicatorManager.hide()
        val displayContext = appContext.createDisplayContext(display)
        val manager = displayContext.getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("No WindowManager for display ${display.displayId}")
        // vc110 FAST PATH: cached panel, no loading
        // intro at all. Validity is checked here,
        // before any view is added.
        val cachedRoot = cachedPanelRoot
        val cachedReopenFn = cachedReopen
        if (cachedRoot != null && cachedReopenFn != null &&
            cachedPanelDisplayId == display.displayId) {
            runCatching { MediaControllerRepository(displayContext).refresh() }
            val curMedia = MediaState.current.value
            val curSettingsKey = runCatching {
                settingsKeyFor(SettingsStore(appContext).load())
            }.getOrElse { "" }
            val metrics = displayContext.resources.displayMetrics
            val curAppListVersion = com.cuong.cflip.data.AppRepository.appListVersion()
            val curFavoritesKey = runCatching {
                com.cuong.cflip.data.FavoritesStore(appContext).getFavorites().sorted().joinToString(",")
            }.getOrElse { "" }
            val curHiddenKey = runCatching {
                com.cuong.cflip.data.HiddenAppsStore(appContext).getHidden().sorted().joinToString(",")
            }.getOrElse { "" }
            val cacheValid = curMedia != null == cachedMediaExists &&
                (curMedia?.isPlaying == true) == cachedMediaPlaying &&
                curSettingsKey == cachedSettingsKey &&
                cachedSettingsKey.isNotEmpty() &&
                curAppListVersion == cachedAppListVersion &&
                curFavoritesKey == cachedFavoritesKey &&
                curHiddenKey == cachedHiddenKey &&
                metrics.widthPixels == cachedPanelDisplayW &&
                metrics.heightPixels == cachedPanelDisplayH
            if (cacheValid) {
                try {
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT,
                    ).apply { gravity = Gravity.TOP or Gravity.START }
                    cachedPanelRoot = null
                    cachedRoot.keepScreenOn = true
                    manager.addView(cachedRoot, params)
                    view = cachedRoot; panelParams = params; wm = manager
                    runCatching {
                        val st = SettingsStore(appContext).load()
                        params.screenBrightness = if (st.panelBrightnessManual) st.panelBrightness.coerceIn(0.05f, 1.0f) else -1f
                        manager.updateViewLayout(cachedRoot, params)
                    }
                    cachedReopenFn(effectiveInitialTab)
                    // vc112: cached root may still
                    // carry the exit animation end
                    // values (alpha 0, scale 0.98)
                    // from the last close - animate
                    // in resets them and gives the
                    // same smooth enter as a fresh
                    // build, instead of a pop-in or
                    // an invisible panel.
                    animatePanelIn(cachedRoot)
                    scheduleDismiss()
                    Log.i(TAG, "Control Center shown from cache on display ${display.displayId}")
                } catch (e: Exception) {
                    restoreOpenerIfHiddenForPanel()
                    throw e
                }
                return@runCatching Unit
            } else {
                clearPanelCache()
            }
        }
        // vc44: show the CFlip Loading intro IMMEDIATELY (one cheap
        // view), then build the heavy panel on the next main-loop
        // turn. Previously buildPanel() ran synchronously inside
        // show(), so between double-tap and first panel frame the
        // user stared at a frozen bubble for the whole build cost
        // (media session IPC + ~100 tile/widget views + notification
        // rows). Now the intro draws first, then the panel replaces
        // it. If build fails, the intro is removed and the failure
        // is logged + toasted instead of leaving a stuck intro.
        val density = displayContext.resources.displayMetrics.density
        val dpL = { v: Int -> (v * density).toInt() }
        val loading = LinearLayout(displayContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF0B0D0F.toInt())
            addView(TextView(displayContext).apply {
                text = "CFlip"; textSize = 28f
                setTextColor(0xFF7FE3C0.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
            })
            addView(android.widget.ProgressBar(displayContext).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(dpL(32), dpL(32)).apply {
                    setMargins(0, dpL(12), 0, dpL(8))
                    gravity = Gravity.CENTER
                }
            })
            addView(TextView(displayContext).apply {
                text = "Loading..."; textSize = 14f
                setTextColor(0xFF9AA0A6.toInt())
                gravity = Gravity.CENTER
            })
            keepScreenOn = true
        }
        val loadingParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        manager.addView(loading, loadingParams)
        wm = manager; loadingView = loading
        val r = Runnable {
            loadingRunnable = null
            runCatching {
                val root = buildPanel(displayContext, display, effectiveInitialTab)
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.START }
                root.keepScreenOn = true
                // Swap intro -> panel: remove intro first so the two
                // full-screen overlays never stack.
                runCatching { manager.removeView(loading) }
                loadingView = null
                manager.addView(root, params)
                view = root; panelParams = params
                runCatching {
                    val st = SettingsStore(appContext).load()
                    params.screenBrightness = if (st.panelBrightnessManual) st.panelBrightness.coerceIn(0.05f, 1.0f) else -1f
                    manager.updateViewLayout(root, params)
                }
                // vc112: smooth panel enter (fade +
                // scale + rise), instead of a pop-in
                // over the loading intro removal.
                animatePanelIn(root)
                scheduleDismiss()
                Log.i(TAG, "Control Center shown on display ${display.displayId}")
            }.onFailure { e ->
                runCatching { manager.removeView(loading) }
                loadingView = null
                // vc109: build failed, no panel to
                // show - give the opener back.
                restoreOpenerIfHiddenForPanel()
                Log.w(TAG, "Control Center build failed", e)
                android.widget.Toast.makeText(displayContext, "Mở CFlip thất bại, thử chạm lại", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        loadingRunnable = r
        handler.post(r)
        Unit
    }.onFailure { Log.w(TAG, "Control Center addView failed", it) }

    // vc112 SMOOTH EXIT: close animates out
    // (fade + shrink + sink, 200ms) and only
    // then removes the view and restores the
    // bubble, so the bubble never overlaps a
    // fading panel. show() still uses the instant
    // hidePanelOnly() below, so a reopen does not
    // wait for the old panel to fade out.
    fun hide() {
        val v = view
        if (v == null) {
            hidePanelOnly()
            return
        }
        if (panelExiting) return
        panelExiting = true
        val d = v.resources.displayMetrics.density
        v.animate().alpha(0f).scaleX(0.98f).scaleY(0.98f).translationY(d * 12f)
            .setDuration(200L)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                panelExiting = false
                hidePanelOnly()
            }
            .start()
    }

    // vc110: callers inside buildPanel that
    // refresh the panel on purpose (add/remove
    // widget, retry a failed list) must rebuild
    // fresh, not reuse the cache - otherwise the
    // new widget / retry would never appear.
    fun showFresh(appContext: Context, display: Display, initialTab: Int = 0): Result<Unit> {
        forceFreshShow = true
        return show(appContext, display, initialTab)
    }

    // vc109: service hide-all paths (cover hidden,
    // display removed, service destroyed) must NOT
    // restore the bubble - the service is hiding
    // everything on purpose. Normal hide() above
    // restores it; this one clears the flag first.
    fun hideWithoutBubbleRestore() {
        bubbleHiddenForPanel = false
        indicatorHiddenForPanel = false
        hidePanelOnly()
        clearPanelCache()
    }

    // vc48: transient cover message pill (screenshot feedback).
    private fun showCoverMessage(ctx: Context, display: Display, text: String) {
        runCatching {
            runCatching {
                val v = shotFeedbackView; val w = shotFeedbackWm
                if (v != null && w != null) w.removeView(v)
            }
            shotFeedbackView = null; shotFeedbackWm = null
            // vc49 fix: this method lives on the manager object,
            // NOT inside buildPanel, so it cannot see buildPanel's
            // locals appContext/dp (that was the vc48 compile error:
            // Unresolved appContext/dp). Use the caller's ctx
            // (already a cover display context) directly, and
            // compute dp from its density locally.
            val dctx = ctx
            val wm = dctx.getSystemService(WindowManager::class.java) ?: return@runCatching
            val density = dctx.resources.displayMetrics.density
            val dpF = { v: Int -> (v * density).toInt() }
            val tv = TextView(dctx).apply {
                this.text = text
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xE0000000.toInt())
                setPadding(dpF(16), dpF(12), dpF(16), dpF(12))
                gravity = Gravity.CENTER
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
            wm.addView(tv, params)
            shotFeedbackView = tv; shotFeedbackWm = wm
            handler.postDelayed(Runnable {
                runCatching { wm.removeView(tv) }
                if (shotFeedbackView === tv) { shotFeedbackView = null; shotFeedbackWm = null }
            }, 2500)
        }
    }

    private fun hidePanelOnly() {
        panelExiting = false
        view?.animate()?.cancel()
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        mediaTickRunnable?.let { handler.removeCallbacks(it) }
        mediaTickRunnable = null
        mediaCardPositionSync = null
        loadingRunnable?.let { handler.removeCallbacks(it) }
        loadingRunnable = null
        val lv = loadingView
        if (lv != null) {
            runCatching { wm?.removeView(lv) }
            loadingView = null
        }
        val v = view
        if (v != null) {
            v.keepScreenOn = false
            runCatching { wm?.removeView(v) }
            // vc110: keep the root for the next
            // open, but ONLY if it was built with
            // a reopen callback (vc110 builds).
            // Without a callback the root cannot
            // restart its media tick or reset its
            // tab, so it must not be reused.
            cachedPanelRoot = if (cachedReopen != null) v else null
            view = null; wm = null; panelParams = null
            Log.i(TAG, "Control Center hidden")
        }
        // vc109: panel gone -> bubble back, unless
        // hideWithoutBubbleRestore cleared the flag.
        restoreOpenerIfHiddenForPanel()
    }

    private fun scheduleDismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { hide() }
        dismissRunnable = r
        handler.postDelayed(r, AUTO_DISMISS_MS)
    }

    // 2.8.0: AssistiveTouch panel is now 4 tabs at the top, in CFlip's
    // own layout (not 9Flip's): Điều khiển / Thông báo / Ứng dụng / CFlip.
    // - Điều khiển: media, brightness, volume, 5-column tiles (as before).
    // - Thông báo: full active-notification list (tap opens, Xóa cancels
    //   the system notification — labelled honestly, unlike the old
    //   banner X which only hid the banner).
    // - Ứng dụng: full app grid on the cover display.
    // - CFlip: opens CFlip itself on the MAIN display for settings/custom,
    //   plus permission shortcuts. It never opens an external cover app.
    private fun buildPanel(ctx: Context, display: Display, initialTab: Int = 0): View {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val store = SettingsStore(ctx)
        // vc32: TB / CĐ nhanh previously swallowed performGlobalAction
        // failures. Hide the panel first (so the shade can show), then
        // report a failure instead of silently doing nothing. If the
        // service instance is null, Accessibility for CFlip is off.
        fun a11yAction(name: String, action: (MyAccessibilityService) -> Boolean) {
            val svc = MyAccessibilityService.instance
            if (svc == null) {
                android.widget.Toast.makeText(
                    ctx, "$name không chạy: cần bật Accessibility cho CFlip",
                    android.widget.Toast.LENGTH_SHORT).show()
            } else {
                hide()
                val ok = action(svc)
                if (!ok) {
                    android.widget.Toast.makeText(
                        ctx, "$name không thực hiện được (hệ thống từ chối)",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
        fun resetTimer() = scheduleDismiss()
        fun title(text: String): TextView = TextView(ctx).apply {
            this.text = text; textSize = 15f; setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        // Modern design system 2.9.0 (CFlip's own palette, same as MainScreen):
        // near-black background, dark cards, mint accent, rounded corners.
        // No copied 9Flip layout, icon or text — only the tab/function list.
        val mint = 0xFF7FE3C0.toInt()
        val cardColor = 0xFF16181C.toInt()
        val muted = 0xFF9AA0A6.toInt()
        fun rounded(color: Int, radiusDp: Int = 16): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(radiusDp).toFloat()
                setColor(color)
            }
        fun card(): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardColor, 16)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }
        }
        fun modernButton(text: String, primary: Boolean = false): Button = Button(ctx).apply {
            this.text = text; textSize = 13f; isAllCaps = false
            background = rounded(if (primary) mint else 0xFF23262B.toInt(), 24)
            setTextColor(if (primary) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(8))
            }
        }
        // vc34 Samsung pill slider (code only, no asset): vc33 only
        // changed tints, so the track stayed a thin Android line and
        // looked unchanged. Now the progressDrawable itself is a pill:
        // dark rounded track + white rounded progress clipped from the
        // left, with a 32dp white round thumb, like One UI QS.
        fun mintSeekBar(): SeekBar = SeekBar(ctx).apply {
            runCatching {
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(0xFF3A3F45.toInt())
                }
                val fg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(14).toFloat()
                    setColor(0xFFFFFFFF.toInt())
                }
                val clip = android.graphics.drawable.ClipDrawable(
                    fg, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
                val layers = android.graphics.drawable.LayerDrawable(arrayOf(bg, clip))
                layers.setId(0, android.R.id.background)
                layers.setId(1, android.R.id.progress)
                progressDrawable = layers
                val thumbDrawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFFFFFFFF.toInt())
                    setSize(dp(32), dp(32))
                }
                thumb = thumbDrawable
            }
            minimumHeight = dp(28)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
            // Fix 2.10.0: a SeekBar inside a ScrollView loses horizontal
            // drags to the scroll container. Tell the parent not to
            // intercept while the thumb is being dragged.
            setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true); false
            }
        }

        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF0000000.toInt())
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(12), dp(8), dp(24))
            // vc41 ScrollView root cause: ScrollView extends FrameLayout,
            // whose default child LayoutParams are MATCH_PARENT x
            // MATCH_PARENT. A programmatically created child with no
            // explicit params therefore gets MATCH_PARENT height, so
            // ScrollView thinks the content already fits and NEVER
            // scrolls - everything below one screen height was clipped,
            // which is why the Function island stayed cut off after
            // vc40 fixed only the ScrollView's own height. Explicit
            // WRAP_CONTENT height is what makes ScrollView scroll.
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        // vc40 ScrollView fix: previously ScrollView had WRAP_CONTENT
        // height, so it tried to be as tall as its content and the
        // panel (full-screen) simply CLIPPED the bottom - the Function
        // island looked hidden/obscured and could not be scrolled to.
        // Now ScrollView fills the remaining height after the tab bar
        // (weight 1) and scrolls internally, and root gets extra bottom
        // padding so the last card is never cut by the bottom edge.
        val scroll = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        scroll.addView(root)
        val controlContent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val notifContent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val appsContent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val cflipContent = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        root.addView(controlContent); root.addView(notifContent)
        root.addView(appsContent); root.addView(cflipContent)

        // Tab bar at the top.
        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(cardColor, 24)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(12), dp(12), dp(12), 0)
            }
        }
        val tabContents = listOf(controlContent, notifContent, appsContent, cflipContent)
        val tabButtons = mutableListOf<Button>()
        // vc41 lazy apps: the apps tab used to be built on EVERY panel
        // open (queryIntentActivities + loadIcon for every app +
        // getPackageInfo for every package), even though the user
        // only sees the Control tab first. That was the main panel-open
        // cost. Now the apps grid builds only on first switch to tab 2.
        var buildAppsTab: (() -> Unit)? = null
        var buildNotifTab: (() -> Unit)? = null
        fun selectTab(i: Int) {
            if (i == 1) buildNotifTab?.invoke()
            if (i == 2) buildAppsTab?.invoke()
            tabContents.forEachIndexed { idx, v -> v.visibility = if (idx == i) View.VISIBLE else View.GONE }
            tabButtons.forEachIndexed { idx, b ->
                if (idx == i) { b.background = rounded(mint, 24); b.setTextColor(0xFF000000.toInt()) }
                else { b.background = null; b.setTextColor(0xFFFFFFFF.toInt()) }
            }
            // vc112: smooth tab switch - fade the
            // newly visible tab in (150ms). At
            // build time view is still null, so
            // the initial tab does not animate.
            if (view != null) {
                val visible = tabContents[i.coerceIn(0, tabContents.size - 1)]
                visible.alpha = 0f
                visible.animate().alpha(1f).setDuration(150L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            resetTimer()
        }
        for ((i, label) in listOf("Điều khiển", "Thông báo", "Ứng dụng", "CFlip").withIndex()) {
            val b = Button(ctx).apply {
                text = label; textSize = 11f; isAllCaps = false
                setPadding(0, dp(8), 0, dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { selectTab(i) }
            }
            tabButtons.add(b); tabBar.addView(b)
        }
        tabBar.addView(Button(ctx).apply {
            text = "✕"; setOnClickListener { hide() }
            background = rounded(0xFF23262B.toInt(), 24)
            setTextColor(0xFFFFFFFF.toInt())
        })
        // vc120: full panel always has its tab bar.
        // Quick Panel is an open mode, not a
        // panel variant (vc117 bar deleted).
        outer.addView(tabBar)
        outer.addView(scroll)
        // 3.2.0: no content rotation here. Rotation is the display-level
        // host (FOLLOW pulse / LOCK remove), never a single View's
        // rotation, so the panel cannot rotate alone like in 2.7.0.
        selectTab(initialTab.coerceIn(0, 3))

        // ---- User-added Widgets (3.1.0, NOT forced) ----
        // Decompile truth: 9Flip's widgets are standard AppWidgetProviders
        // (NineCoverAppGridWidgetProvider + RemoteViewsService, and the
        // Cassette family) — the system/user places them, the app never
        // forces them onto the cover. CFlip follows that: this section
        // renders ONLY widgets the user turned on in MainScreen
        // (SettingsStore.coverWidgets, default empty). If none are on,
        // the section says so and offers to open CFlip to add one.
        var widgetCardDeferred: LinearLayout? = null
        // vc111: clock widget views, so the panel
        // cache reopen lambda can refresh the time
        // instead of showing the time from the
        // last open (vc110 panel cache froze it).
        var clockTimeView: TextView? = null
        var clockDetailView: TextView? = null
        run {
            val chosen = store.load().coverWidgets.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val widgetCard = card()
            widgetCard.addView(title("Widget đã thêm"))
            // 3.3.0 Samsung cover widgets: after the Samsung sub_screen
            // registration, CFlip widgets appear in Samsung's own cover
            // widget picker. That picker lives on the folded cover, so
            // adding from the cover needs no unfolding: fold the phone,
            // swipe the cover widgets to the end, tap +, pick a CFlip
            // widget. This text states that flow; the picker itself is
            // Samsung's UI, not CFlip's.
            widgetCard.addView(TextView(ctx).apply {
                text = "Thêm từ màn phụ (không cần mở máy): gập máy lại, vuốt Widget màn phụ tới cuối, bấm +, chọn Widget CFlip trong danh sách Widget Samsung."
                textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
            })
            // 3.4.0: status + Samsung settings, usable from the cover
            // without leaving it (status is a toast, settings is Samsung's
            // own activity — the pin dialog is gone, it can never add
            // a cover widget).
            widgetCard.addView(modernButton("Kiểm tra Widget Samsung đã thêm chưa").apply {
                setOnClickListener {
                    android.widget.Toast.makeText(
                        ctx,
                        com.cuong.cflip.widget.SamsungCoverWidgetHelper.statusText(ctx.applicationContext),
                        android.widget.Toast.LENGTH_LONG).show()
                    resetTimer()
                }
            })
            widgetCard.addView(modernButton("Mở Cài đặt Widget màn phụ Samsung").apply {
                setOnClickListener {
                    com.cuong.cflip.widget.SamsungCoverWidgetHelper.openCoverWidgetSettings(ctx.applicationContext)
                }
            })
            if (chosen.isEmpty()) {
                widgetCard.addView(TextView(ctx).apply {
                    text = "Chưa thêm Widget nào. Bấm một nút dưới đây để thêm ngay trên màn phụ — không nhảy sang màn hình chính."
                    textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
                })
                fun addWidgetOnCover(key: String, label: String) {
                    val cur = store.load()
                    val next = (cur.coverWidgets.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet() + key).sorted().joinToString(",")
                    store.save(cur.copy(coverWidgets = next))
                    // Refresh the panel in place on the cover, so the
                    // new widget card appears here, not on main home.
                    hidePanelOnly()
                    CoverControlCenterManager.showFresh(ctx.applicationContext, display, 0)
                }
                widgetCard.addView(modernButton("Thêm Widget Đồng hồ", true).apply {
                    setOnClickListener { addWidgetOnCover("clock", "Đồng hồ") }
                })
                widgetCard.addView(modernButton("Thêm Widget Lưới app").apply {
                    setOnClickListener { addWidgetOnCover("appgrid", "Lưới app") }
                })
                // 9Flip decompile (o6/n0 openCoverWidgetSettings): the
                // correct Samsung cover-widget flow is NOT pinning to
                // main home. It opens Samsung's own cover widget settings
                // (aodservice SubLauncherMultiWidgetSettingsActivity),
                // where the user adds the Widget Launcher to the cover.
                // That page lives on the main display by Samsung's
                // design — it is labelled as such, so it is not a
                // mistaken jump to main home. If the activity does not
                // exist, show 9Flip's unsupported toast equivalent.
                widgetCard.addView(modernButton("Mở Cài đặt Widget màn phụ Samsung").apply {
                    setOnClickListener {
                        val intent = Intent().apply {
                            component = android.content.ComponentName(
                                "com.samsung.android.app.aodservice",
                                "com.samsung.android.app.subui.settings.multiwidget.SubLauncherMultiWidgetSettingsActivity")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (intent.resolveActivity(ctx.packageManager) == null) {
                            android.widget.Toast.makeText(
                                ctx, "Thiết bị này không hỗ trợ Cài đặt Widget màn phụ Samsung.",
                                android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            ctx.applicationContext.startActivity(intent)
                        }
                    }
                })
            } else {
                if ("clock" in chosen) {
                    // vc111: clock views are kept in
                    // clockTimeView / clockDetailView
                    // (declared above) so reopen can
                    // refresh them; text is identical
                    // to the old anonymous version.
                    fun clockBatteryPct(): Int {
                        return runCatching {
                            val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                            val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                            val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                            if (level >= 0 && scale > 0) level * 100 / scale else -1
                        }.getOrDefault(-1)
                    }
                    val batteryPct = clockBatteryPct()
                    clockTimeView = TextView(ctx).apply {
                        text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        textSize = 28f; setTextColor(0xFFFFFFFF.toInt())
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    }
                    widgetCard.addView(clockTimeView)
                    clockDetailView = TextView(ctx).apply {
                        val battery = if (batteryPct >= 0) " · Pin $batteryPct%" else ""
                        text = java.text.SimpleDateFormat("EEE, dd/MM", java.util.Locale.getDefault()).format(java.util.Date()) +
                            " · TB: ${NotificationState.active.value.size}$battery"
                        textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
                    }
                    widgetCard.addView(clockDetailView)
                }
                if ("appgrid" in chosen) {
                    widgetCard.addView(TextView(ctx).apply {
                        text = "Ứng dụng yêu thích"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
                    })
                    // vc56 APPGRID FALLBACK: vc55 report was "widget
                    // app does not show apps". The old code dropped
                    // a pinned app entirely if loadLaunchableApps()
                    // did not contain its package (query failed,
                    // icon load failed, package renamed) - then the
                    // widget showed the misleading "no pinned app"
                    // text even though favorites were still saved.
                    // Now a pinned package always renders as a
                    // text button (label = last package segment,
                    // no icon) so it stays visible and launchable.
                    // vc59 WIDGET FALLBACK: vc56 fixed pinned apps
                    // disappearing when the query failed, but if
                    // the user has pinned ZERO apps, the widget
                    // still showed only "Chưa ghim" text and no
                    // apps at all - which reads as "widget doesn't
                    // show apps". Now, with no pins, the widget
                    // shows the first 8 launchable apps instead,
                    // with a note that pinning in CFlip replaces
                    // them with the user's own picks. Only if the
                    // query itself returns empty does it show the
                    // empty text. Query uses applicationContext
                    // (not the cover display context) to avoid
                    // display-specific PackageManager quirks.
                    // vc111: favorites read ONCE (was
                    // read twice: pins + hasPins).
                    // loadLaunchableApps() itself is
                    // now cached in AppRepository, so
                    // this widget no longer re-queries
                    // PackageManager + icons on every
                    // panel build - the main widget
                    // loading cost the user reported.
                    val favoritesSet = com.cuong.cflip.data.FavoritesStore(ctx.applicationContext).getFavorites()
                    val favApps = run {
                        val repo = com.cuong.cflip.data.AppRepository(ctx.applicationContext)
                        val all = runCatching { repo.loadLaunchableApps() }.getOrDefault(emptyList())
                        val byPkg = all.associateBy { it.packageName }
                        val pins = favoritesSet.map { pkg ->
                            byPkg[pkg] ?: com.cuong.cflip.data.AppInfo(packageName = pkg, label = pkg.substringAfterLast('.'), icon = null)
                        }.take(8)
                        if (pins.isNotEmpty()) pins else all.take(8)
                    }
                    val hasPins = favoritesSet.isNotEmpty()
                    if (favApps.isEmpty()) {
                        // vc71 WIDGET RETRY: the old text blamed
                        // pinning ("Chưa ghim...") even when the query
                        // itself failed, and offered no recovery - the
                        // widget stayed empty until the panel was
                        // closed and reopened, and if the query failed
                        // systematically it stayed empty forever. Now
                        // the text says honestly that the list failed
                        // to load, and a retry button rebuilds the
                        // panel fresh (same pattern as addWidgetOnCover),
                        // so a transient empty recovers with one tap,
                        // no AssistiveTouch restart needed.
                        widgetCard.addView(TextView(ctx).apply {
                            text = "Không tải được danh sách ứng dụng (0 app) - bấm Thử lại."
                            textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
                        })
                        widgetCard.addView(modernButton("Thử lại").apply {
                            setOnClickListener {
                                hidePanelOnly()
                                CoverControlCenterManager.showFresh(ctx.applicationContext, display, 0)
                            }
                        })
                    } else {
                        if (!hasPins) {
                            widgetCard.addView(TextView(ctx).apply {
                                text = "Chưa ghim app nào - hiện 8 app đầu, ghim ở CFlip để chọn riêng."
                                textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
                            })
                        }
                        val favGrid = GridLayout(ctx).apply { columnCount = 4 }
                        for (app in favApps) {
                            favGrid.addView(Button(ctx).apply {
                                text = app.label; textSize = 10f; isAllCaps = false
                                background = rounded(0xFF23262B.toInt(), 12)
                                setTextColor(0xFFFFFFFF.toInt())
                                if (app.icon != null) {
                                    val d = app.icon.constantState?.newDrawable()?.mutate() ?: app.icon
                                    d.setBounds(0, 0, dp(40), dp(40))
                                    setCompoundDrawables(null, d, null, null)
                                }
                                setOnClickListener {
                                    com.cuong.cflip.display.AppLauncher.launch(ctx, app.packageName, display.displayId)
                                    hide()
                                }
                            }, GridLayout.LayoutParams().apply { width = dp(76); height = dp(96); setMargins(dp(4), dp(6), dp(4), dp(6)) })
                        }
                        widgetCard.addView(favGrid)
                    }
                    widgetCard.addView(modernButton("Xem tất cả ứng dụng").apply {
                        setOnClickListener { selectTab(2) }
                    })
                }
            }
            if (chosen.isNotEmpty()) {
                if ("clock" !in chosen) {
                    widgetCard.addView(modernButton("Thêm Widget Đồng hồ").apply {
                        setOnClickListener {
                            val cur = store.load()
                            val next = (cur.coverWidgets.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet() + "clock").sorted().joinToString(",")
                            store.save(cur.copy(coverWidgets = next))
                            hidePanelOnly()
                            CoverControlCenterManager.showFresh(ctx.applicationContext, display, 0)
                        }
                    })
                }
                if ("appgrid" !in chosen) {
                    widgetCard.addView(modernButton("Thêm Widget Lưới app").apply {
                        setOnClickListener {
                            val cur = store.load()
                            val next = (cur.coverWidgets.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet() + "appgrid").sorted().joinToString(",")
                            store.save(cur.copy(coverWidgets = next))
                            hidePanelOnly()
                            CoverControlCenterManager.showFresh(ctx.applicationContext, display, 0)
                        }
                    })
                }
                widgetCard.addView(modernButton("Xóa tất cả Widget đã thêm").apply {
                    setOnClickListener {
                        store.save(store.load().copy(coverWidgets = ""))
                        hidePanelOnly()
                        CoverControlCenterManager.showFresh(ctx.applicationContext, display, 0)
                    }
                })
            }
            widgetCardDeferred = widgetCard
        }

        // ---- Tab 1: Điều khiển ----
        val mediaRepo = MediaControllerRepository(ctx)
        runCatching { mediaRepo.refresh() }
        val mediaInfo = MediaState.current.value
        // vc36 9Flip media view (e4.g / a9.setMediaVisible): the bar
        // exists ONLY while media is active, and appears with
        // alpha 0->1, translationY -10dp->0, scale 0.985->1 in 190ms.
        // Previously CFlip always showed "Chưa có nhạc" with no artist,
        // no progress and a fixed play icon. Now: title marquee 13sp
        // white, artist 11sp 75% white, elapsed/total + thin progress
        // bar (tap to seek, via the repository's seekTo), and a
        // play/pause icon that actually reflects isPlaying.
        // vc37 media card: FrameLayout so album art can be a full-bleed
        // background (Spotify/Samsung cover card in Cường's screenshot),
        // with a dark overlay on top, like 9Flip e4.g (art alpha 0.96
        // + argb(116,0,0,0) overlay). Content lives in mediaContent.
        val mediaCard = FrameLayout(ctx).apply {
            background = rounded(cardColor, 16)
            // vc40 rounded media: previously the album-art ImageView
            // was a square rectangle on top of the rounded card, so
            // the media island had square corners in the screenshot
            // while the slider island had round ones. clipToOutline
            // clips children (the art) to the rounded background.
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }
        }
        val mediaContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        // vc40 art view is ALWAYS present (not only when the first
        // track had art), so updateMediaViews() can swap it when the
        // track changes. Previously art was set once at panel build
        // and never updated, so a new song kept the old song's image.
        val mediaArtView = ImageView(ctx).apply {
            if (mediaInfo?.albumArt != null) setImageBitmap(mediaInfo.albumArt)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.96f
        }
        mediaCard.addView(mediaArtView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        mediaCard.addView(View(ctx).apply {
            setBackgroundColor(android.graphics.Color.argb(116, 0, 0, 0))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // vc106: do NOT GONE the card when there is
        // no session. vc105 moved it below Function
        // in hierarchy, but GONE made that move
        // invisible, so the test correctly said it
        // had not moved. Now the card stays VISIBLE
        // (empty state, see mediaTitleView below)
        // and, when not playing, sits below Function
        // via the vc105 order block. Playing case
        // keeps the 190ms appear animation.
        if (mediaInfo == null) {
            mediaCard.visibility = View.VISIBLE
        } else {
            mediaCard.alpha = 0f
            mediaCard.translationY = -dp(10).toFloat()
            mediaCard.scaleX = 0.985f
            mediaCard.scaleY = 0.985f
            mediaCard.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(190L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        // vc42 media tap fix: vc41 tried sessionActivity FIRST.
        // If that PendingIntent succeeded, it opened on the MAIN
        // display (its own launch options ignore the cover display
        // id), so the panel hid, the app opened folded inside, and
        // on the cover nothing appeared - "không chuyển đến được
        // đích". The fallback to AppLauncher never ran because
        // sessionActivity had already "succeeded". vc42 reverses
        // the order: cover launch FIRST (the only route guaranteed
        // to be visible while folded), sessionActivity only if
        // there is no launch intent, and a main-display launch
        // only as a last resort with a toast saying to unfold.
        // The action is also set on mediaContent, not just the
        // card, so taps on title/artist (inside mediaContent)
        // definitely trigger it even if parent propagation is
        // blocked; buttons/progress are clickable children, so
        // they still consume their own taps.
        val openMediaDestination = {
            runCatching { mediaRepo.refresh() }
            val cur = MediaState.current.value
            hide()
            if (cur == null) {
                android.widget.Toast.makeText(ctx, "Chưa có nhạc đang phát", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val pm = ctx.packageManager
                val launchIntent = runCatching { pm.getLaunchIntentForPackage(cur.packageName) }.getOrNull()
                var openedOnCover = false
                if (launchIntent != null) {
                    openedOnCover = runCatching {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        val opts = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle()
                        ctx.startActivity(launchIntent, opts)
                        true
                    }.getOrDefault(false)
                }
                if (!openedOnCover) {
                    val pi = cur.sessionActivity
                    var openedViaPi = false
                    if (pi != null) {
                        openedViaPi = runCatching {
                            pi.send(ctx, 0, null, null, null, null, null)
                            true
                        }.getOrDefault(false)
                    }
                    if (!openedViaPi && launchIntent != null) {
                        runCatching {
                            ctx.applicationContext.startActivity(
                                Intent(launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        android.widget.Toast.makeText(ctx, "App không mở được trên màn phụ, đã mở trên màn chính - mở máy ra để xem", android.widget.Toast.LENGTH_LONG).show()
                    } else if (openedViaPi) {
                        android.widget.Toast.makeText(ctx, "Đã mở app đang phát", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        android.widget.Toast.makeText(ctx, "Không mở được app đang phát", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        mediaCard.setOnClickListener { openMediaDestination() }
        // vc107: small 4-bar wave next to the
        // Âm thanh title (vc44) DELETED at Cường's
        // request 27/10 - the timeline wave below
        // (MediaProgressBar, 9Flip e4/i) is now the
        // only wave in the media card.
        mediaContent.addView(title("Âm thanh"))
        val mediaTitleView = TextView(ctx).apply {
            text = mediaInfo?.title ?: "Chưa có media đang phát"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
            maxLines = 1; isSingleLine = true; isSelected = true
            setHorizontallyScrolling(true)
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
        }
        mediaContent.addView(mediaTitleView)
        val mediaArtistView = TextView(ctx).apply {
            text = mediaInfo?.artist ?: ""; textSize = 11f
            setTextColor(0xBFFFFFFF.toInt())
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        mediaContent.addView(mediaArtistView)
        fun fmtTime(ms: Long): String {
            val total = (ms / 1000).toInt()
            return "%d:%02d".format(total / 60, total % 60)
        }
        val mediaElapsed = TextView(ctx).apply { textSize = 11f; setTextColor(0xFFFFFFFF.toInt()) }
        val mediaTotal = TextView(ctx).apply { textSize = 11f; setTextColor(0xFFFFFFFF.toInt()) }
        val mediaProgress = MediaProgressBar(ctx).apply {
            // vc113: back to 28dp (9Flip height).
            // vc112's 36dp was for the symmetric
            // wave above AND below the centre;
            // vc113's wave is above the timeline
            // only, so 36dp would leave a large
            // empty gap below the pill.
            // vc115: slight gap above the timeline
            // so it does not sit tight against the
            // song title / artist above it.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)).apply {
                topMargin = dp(6)
            }
            // vc105: wave pattern seed = song title
            // hash, so the same song keeps the same
            // wave (decorative, not real audio data).
            waveformSeed = mediaInfo?.title?.hashCode() ?: 0
            wavePlaying = mediaInfo?.isPlaying == true
            // vc108: wave colour from album art
            // dominant colour (falls back to the
            // title-hash hue when art is null).
            setMediaArt(mediaInfo?.albumArt)
            if (mediaInfo != null && mediaInfo.durationMs > 0) {
                progress = mediaInfo.positionMs.toFloat() / mediaInfo.durationMs
            }
            // vc113 SEEK: MediaProgressBar now
            // calls this ONCE on finger-up, not
            // on every move. Do NOT refresh +
            // overwrite progress here: the session
            // updates asynchronously, so an immediate
            // read gives the OLD position and snaps
            // the thumb back off the finger. The
            // thumb already sits at frac, and the
            // 1s media tick picks up the session's
            // new position on its next run.
            onSeek = { frac ->
                val dur = MediaState.current.value?.durationMs?.takeIf { it > 0 }
                    ?: mediaInfo?.durationMs?.takeIf { it > 0 }
                if (dur != null) {
                    runCatching { mediaRepo.seekTo((frac * dur).toLong()) }
                }
            }
        }
        if (mediaInfo != null) {
            mediaElapsed.text = fmtTime(mediaInfo.positionMs)
            mediaTotal.text = fmtTime(mediaInfo.durationMs)
        }
        mediaContent.addView(mediaProgress)
        mediaContent.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(mediaElapsed)
            addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            addView(mediaTotal)
        })
        // vc30 LOCAL-ONLY: media buttons use Samsung ic_sec drawables that
        // exist only in the local samsung-icons-360-vc30 branch / D:\icons\sec_only.
        // Do NOT push this file to main without replacing these with clean-room drawables.
        // vc34 Samsung media controls: circles, not wide pills.
        // Play is larger (56dp) and mint, prev/next are 48dp dark circles.
        // vc37 media buttons: white outline icons on transparent
        // background, like the Spotify card in Cường's screenshot,
        // NOT dark circles with a mint play button (vc34/vc36).
        // vc37 Play/Pause fix: Samsung's ic_sec_play draws PAUSE bars
        // and ic_sec_pause draws a PLAY triangle (verified from the
        // sec_only zip), so vc36's icon was backwards. CFlip now uses
        // its own clean-room play/pause with standard names, and the
        // icon shows the NEXT action: pause bars while playing,
        // triangle while paused.
        fun mediaIconButton(iconRes: Int, primary: Boolean = false): ImageButton = ImageButton(ctx).apply {
            setImageResource(iconRes)
            setColorFilter(0xFFFFFFFF.toInt())
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val sz = if (primary) dp(56) else dp(48)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                setMargins(dp(8), dp(4), dp(8), dp(4))
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        // vc38 real-time media: the panel was built ONCE, so progress
        // never moved and a click's immediate refresh() read the session's
        // STALE state (session updates asynchronously), which is why
        // Prev/Next looked dead and Play/Pause looked reversed until the
        // panel was reopened. updateMediaViews() re-reads the session
        // and rewrites title/artist/progress/elapsed/play icon.
        var mediaPlayButtonRef: ImageButton? = null
        var lastMediaArt: android.graphics.Bitmap? = mediaInfo?.albumArt
        var lastPlayIconState: Boolean? = null
        // vc123: tracks whether a session existed at the last tick,
        // so updateMediaViews() can spot an appear/disappear
        // transition (see the block right after `cur` below).
        var lastMediaExists: Boolean = mediaInfo != null
        fun updateMediaViews() {
            runCatching { mediaRepo.refresh() }
            val cur = MediaState.current.value
            // vc123 LIVE MEDIA CARD (user, 28/10):
            // the panel used to decide the card
            // position ONCE at build (vc108), so a
            // session starting while the panel was
            // already open only updated the card's
            // text - the card stayed buried at the
            // bottom and the Play-last pill stayed,
            // until a full rebuild (service toggle).
            // Now an existence transition moves
            // the card + pill immediately (within
            // the 1s tick, no reopen needed), and
            // refreshes the cache snapshot so the
            // next reopen takes the fast path with
            // the right layout instead of a rebuild.
            if (lastMediaExists != (cur != null)) {
                lastMediaExists = cur != null
                cachedMediaExists = cur != null
                mediaCardPositionSync?.invoke(cur != null)
            }
            if (cur != null) {
                cachedMediaPlaying = cur.isPlaying
            }
            if (cur == null) {
                // vc123 STOP: session gone while the
                // panel is open. Card position now
                // moves live via the transition
                // block above (card to the bottom,
                // pill back), and the wave lowers
                // down to the timeline instead of
                // freezing at full height.
                mediaProgress.wavePlaying = false
                return
            }
            // vc112 TITLE MARQUEE FIX (user,
            // 27/10): updateMediaViews runs every
            // 1s, and it used to set title/artist
            // unconditionally. setText() restarts
            // the marquee even when the text is
            // identical, so the title restarted its
            // scroll every second and never
            // completed a loop. Now title, artist
            // and the play icon only change when
            // the value actually changed - elapsed
            // / progress still update every second,
            // as they should, because time moves.
            if (mediaTitleView.text.toString() != cur.title) mediaTitleView.text = cur.title
            if (mediaArtistView.text.toString() != cur.artist) mediaArtistView.text = cur.artist
            // vc113: while the user is dragging
            // the timeline, do NOT overwrite
            // progress / elapsed with the session
            // position - the finger owns the thumb
            // until finger-up, then the tick takes
            // over again (see onSeek above).
            if (!mediaProgress.isSeeking) {
                mediaElapsed.text = fmtTime(cur.positionMs)
                mediaTotal.text = fmtTime(cur.durationMs)
                mediaProgress.progress = if (cur.durationMs > 0) cur.positionMs.toFloat() / cur.durationMs else 0f
            }
            mediaProgress.waveformSeed = cur.title.hashCode()
            mediaProgress.wavePlaying = cur.isPlaying
            if (lastPlayIconState != cur.isPlaying) {
                lastPlayIconState = cur.isPlaying
                mediaPlayButtonRef?.setImageResource(if (cur.isPlaying) R.drawable.ic_cflip_pause else R.drawable.ic_cflip_play)
            }
            // vc40: swap album art when the track changes (reference
            // comparison avoids re-setting the same bitmap every second).
            if (cur.albumArt !== lastMediaArt) {
                lastMediaArt = cur.albumArt
                mediaArtView.setImageBitmap(cur.albumArt)
                // vc108: wave colour follows the new
                // art's dominant colour (or falls
                // back to the title-hash hue if the
                // new track has no art).
                mediaProgress.setMediaArt(cur.albumArt)
            }
        }
        val mediaRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        mediaRow.addView(mediaIconButton(R.drawable.ic_cflip_media_prev).apply { setOnClickListener { resetTimer(); mediaRepo.previous(); handler.postDelayed({ updateMediaViews() }, 500); handler.postDelayed({ updateMediaViews() }, 1200) } })
        val mediaPlayButton = mediaIconButton(if (mediaInfo?.isPlaying == true) R.drawable.ic_cflip_pause else R.drawable.ic_cflip_play, true).also { mediaPlayButtonRef = it }.apply { setOnClickListener { resetTimer(); mediaRepo.playPause(); handler.postDelayed({ val cur = MediaState.current.value; if (cur == null) android.widget.Toast.makeText(ctx, "Chưa có nhạc để phát lại", android.widget.Toast.LENGTH_SHORT).show() else updateMediaViews() }, 500); handler.postDelayed({ updateMediaViews() }, 1200) } }
        mediaRow.addView(mediaPlayButton)
        mediaRow.addView(mediaIconButton(R.drawable.ic_cflip_media_next).apply { setOnClickListener { resetTimer(); mediaRepo.next(); handler.postDelayed({ updateMediaViews() }, 500); handler.postDelayed({ updateMediaViews() }, 1200) } })
        // vc38 real-time tick: runs updateMediaViews() every 1s while
        // the panel is open (cancelled on hide in hidePanelOnly).
        val mediaTick = object : Runnable {
            override fun run() {
                if (view != null) {
                    updateMediaViews()
                    handler.postDelayed(this, 1000)
                }
            }
        }
        mediaTickRunnable = mediaTick
        handler.postDelayed(mediaTick, 1000)
        mediaContent.addView(mediaRow)
        mediaCard.addView(mediaContent, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        mediaContent.setOnClickListener { openMediaDestination() }
        // vc38: mediaCard is added later, in the combined row with sliders.
        // vc37 Play last song / Media output pill, from Cường's screenshot:
        // shown ONLY when no media is active (instead of an empty card).
        // Play resumes the last session; Media output cannot be opened
        // directly by a third-party app, so it opens Connected Devices
        // (Bluetooth) settings, where the output is actually chosen.
        val playLastPill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(cardColor, 24)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }
            visibility = if (mediaInfo == null) View.VISIBLE else View.GONE
        }
        playLastPill.addView(ImageView(ctx).apply {
            setImageResource(R.drawable.ic_cflip_play)
            setColorFilter(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(dp(24), dp(24))
        })
        playLastPill.addView(TextView(ctx).apply {
            text = "Phát bài hát cuối cùng"; textSize = 14f; setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        playLastPill.addView(Button(ctx).apply {
            text = "Đầu ra Media"; textSize = 12f; isAllCaps = false
            background = rounded(0xFF23262B.toInt(), 24)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                hide()
                android.widget.Toast.makeText(ctx, "Mở Cài đặt Thiết bị đã kết nối để chọn đầu ra âm thanh", android.widget.Toast.LENGTH_LONG).show()
                runCatching { ctx.applicationContext.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        })
        playLastPill.setOnClickListener {
            resetTimer()
            runCatching { mediaRepo.playPause() }
            runCatching { mediaRepo.refresh() }
            val cur = MediaState.current.value
            if (cur == null) {
                android.widget.Toast.makeText(ctx, "Chưa có nhạc để phát lại", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        controlContent.addView(playLastPill)

        // 3.2.0 brightness: PANEL window brightness, not system.
        // 9Flip decompile (p6/t6): own manual value clamp 0.05–1.0,
        // applied as panel window screenBrightness, or -1 (system)
        // when manual is off (slider alpha 0.42 when off, 1.0 when on).
        // The old system-brightness slider never moved the cover
        // because Samsung's cover brightness is not Settings.System.
        // vc35 9Flip slider (p6/e): custom bar, not a SeekBar.
        // Left icon dark on the white fill, translucent pill track,
        // fill = thumb + (width-thumb)*value, alpha 1.0 manual / 0.42 auto.
        // Dragging once switches to manual, like 9Flip p6.d().
        // vc38 horizontal sliders in the SAME row as the Âm thanh card:
        // vc37's vertical sliders took a whole tall card below media.
        // Now the row is: [Âm thanh card | brightness slider + volume
        // slider stacked], so the panel saves ~200dp of vertical space.
        // Sliders are horizontal NineFlipSliders again (vc35, 9Flip p6/e
        // logic), with the same value logic as before: brightness
        // manual clamp 0.05-1.0, alpha 1.0 manual / 0.42 auto, volume
        // mute restores saved volume else ~45% of max.
        // vc40 balanced islands: same background, same 16dp corner,
        // same 12dp padding as the media island, clipToOutline so
        // sliders cannot stick out of the rounded corners, and
        // centred vertically so the two sliders sit balanced against
        // the taller media island instead of hugging its top.
        // vc105: sliders are VERTICAL only when
        // media is PLAYING (mediaInfo at panel open).
        // Not playing / no session = horizontal, as
        // before. Vertical = two vertical NineFlip
        // sliders side by side in a horizontal column,
        // matching the media card's height.
        val slidersVertical = mediaInfo?.isPlaying == true
        val sliderColumn = LinearLayout(ctx).apply {
            orientation = if (slidersVertical) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            background = rounded(cardColor, 16)
            clipToOutline = true
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        var brightSlider2: NineFlipSlider? = null
        var volSlider2: NineFlipSlider? = null
        fun refreshBright2() {
            val st = store.load()
            brightSlider2?.setManual(st.panelBrightnessManual)
            brightSlider2?.minValue = 0.05f
            brightSlider2?.setValue(st.panelBrightness, animate = false)
        }
        fun refreshVol2() {
            val (v, m) = QuickControls.volume(ctx)
            volSlider2?.setValue(if (m > 0) v.toFloat() / m else 0f, animate = false)
        }
        brightSlider2 = NineFlipSlider(ctx).apply {
            if (slidersVertical) setVertical(true)
            layoutParams = if (slidersVertical) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            setIcon(R.drawable.ic_sec_brightness)
            // vc118 BASE-ICON ONLY: tip auto icon
            // removed; its toggle moves to the base
            // sun icon below (setLeftClickListener).
            hideRightIcon()
            minValue = 0.05f
            setValue(store.load().panelBrightness, animate = false)
            setManual(store.load().panelBrightnessManual)
            onValueChanged = { v ->
                var cur = store.load()
                if (!cur.panelBrightnessManual) {
                    cur = cur.copy(panelBrightnessManual = true)
                    store.save(cur)
                    setManual(true)
                }
                store.save(cur.copy(panelBrightness = v))
                applyPanelBrightness(ctx.applicationContext)
                CoverAssistiveTouchManager.applyCoverBrightness(ctx.applicationContext)
            }
            onDragEnd = { resetTimer() }
            // vc118: base sun icon now toggles
            // manual / auto (was the tip auto
            // icon's job). Manual state still shows
            // as slider alpha (1.0 manual / 0.42
            // auto); the toast names the new state
            // because the icon itself no longer
            // changes. Note: a drag starting exactly
            // on the sun icon now taps instead of
            // dragging, same as the speaker icon.
            setLeftClickListener {
                val cur = store.load()
                val nextManual = !cur.panelBrightnessManual
                store.save(cur.copy(panelBrightnessManual = nextManual))
                applyPanelBrightness(ctx.applicationContext)
                CoverAssistiveTouchManager.applyCoverBrightness(ctx.applicationContext)
                refreshBright2(); resetTimer()
                android.widget.Toast.makeText(ctx,
                    if (nextManual) "Đã bật Độ sáng thủ công" else "Đã bật Độ sáng tự động",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        sliderColumn.addView(brightSlider2)
        sliderColumn.addView(View(ctx).apply {
            layoutParams = if (slidersVertical) LinearLayout.LayoutParams(dp(8), LinearLayout.LayoutParams.MATCH_PARENT)
                else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12))
        })
        volSlider2 = NineFlipSlider(ctx).apply {
            if (slidersVertical) setVertical(true)
            layoutParams = if (slidersVertical) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            setIcon(R.drawable.ic_sec_volume)
            // vc118 BASE-ICON ONLY: tip mute icon
            // removed; its music mute / restore
            // moves into the base icon's Mute step
            // below (sound cycle, vc118).
            hideRightIcon()
            minValue = 0f
            val (vol, volMax) = QuickControls.volume(ctx)
            setValue(if (volMax > 0) vol.toFloat() / volMax else 0f, animate = false)
            setManual(true)
            onValueChanged = { v ->
                val (_, volMax2) = QuickControls.volume(ctx)
                QuickControls.setVolume(ctx, (v * volMax2).toInt())
                refreshVol2()
            }
            onDragEnd = { resetTimer() }
        }
        sliderColumn.addView(volSlider2)
        // vc118 SOUND CYCLE (user, 28/10):
        // base speaker icon cycles Âm thanh ->
        // Rung -> Tắt âm -> Âm thanh ... Tắt âm
        // is RINGER SILENT, NOT Do Not Disturb:
        // the old step 2 set filter NONE (DND),
        // which the user called a mistake. The
        // separate DND tile in the grid below
        // still controls DND on purpose; this
        // cycle never sets it. Detection is
        // ringer-only: Vibrate = 1, Silent = 2,
        // else Sound = 0. No DND permission is
        // needed for any step now; filter ALL is
        // still set when permitted so tapping
        // always exits a DND left on by the tile.
        fun soundMode(): Int {
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            return when (am?.ringerMode) {
                android.media.AudioManager.RINGER_MODE_VIBRATE -> 1
                android.media.AudioManager.RINGER_MODE_SILENT -> 2
                else -> 0
            }
        }
        // vc115 SOUND ICON FIX (user, 27/10:
        // icon must change right after the tap):
        // refreshVolModeIcon() re-reads the system
        // state: ringer changes are synchronous,
        // but the 1s re-read is kept as a correction
        // in case the system rejects a change (then
        // a correct icon stays the same, a rejected
        // change is corrected without flicker).
        // Fix: set the icon from the mode just
        // selected (next), not from a re-read, and
        // only re-read after 1s as a correction
        // (by then the system state has settled, so
        // a correct icon stays the same, a rejected
        // change is corrected without flicker).
        fun setVolModeIcon(mode: Int) {
            volSlider2?.setIcon(when (mode) {
                1 -> R.drawable.ic_cflip_vibrate
                2 -> R.drawable.ic_sec_mute
                else -> R.drawable.ic_sec_volume
            })
        }
        fun refreshVolModeIcon() {
            setVolModeIcon(soundMode())
        }
        refreshVolModeIcon()
        volSlider2?.setLeftClickListener {
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val next = (soundMode() + 1) % 3
            // vc118: every step sets filter ALL when
            // permitted, so tapping always exits DND
            // (DND is no longer a cycle step).
            if (nm != null && nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
            }
            when (next) {
                0 -> {
                    am?.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                    // Mute step saved music volume and
                    // set it to 0; restore it only if
                    // it is still 0 (a manual slider
                    // drag meanwhile wins over restore).
                    val (curVol, maxVol) = QuickControls.volume(ctx)
                    if (curVol == 0) {
                        val prefs = ctx.getSharedPreferences("cflip_volume", Context.MODE_PRIVATE)
                        val saved = prefs.getInt("saved_volume", -1)
                        val restore = if (saved > 0) saved else ((maxVol * 0.45f).toInt()).coerceIn(1, maxVol)
                        QuickControls.setVolume(ctx, restore)
                    }
                    android.widget.Toast.makeText(ctx, "Đã bật Âm thanh", android.widget.Toast.LENGTH_SHORT).show()
                    refreshVol2()
                }
                1 -> {
                    am?.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
                    android.widget.Toast.makeText(ctx, "Đã bật Rung", android.widget.Toast.LENGTH_SHORT).show()
                }
                else -> {
                    am?.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                    // vc118 MUTE = ringer silent, NOT
                    // DND. It also carries the old tip
                    // mute: save music volume, set 0.
                    val (curVol, _) = QuickControls.volume(ctx)
                    if (curVol > 0) {
                        ctx.getSharedPreferences("cflip_volume", Context.MODE_PRIVATE)
                            .edit().putInt("saved_volume", curVol).apply()
                        QuickControls.setVolume(ctx, 0)
                    }
                    android.widget.Toast.makeText(ctx, "Đã bật Tắt âm", android.widget.Toast.LENGTH_SHORT).show()
                    refreshVol2()
                }
            }
            setVolModeIcon(next); resetTimer()
            handler.postDelayed({ refreshVolModeIcon() }, 1000)
        }
        refreshBright2(); refreshVol2()
        // Combined row: media frame (weight 3) + slider column (weight 2).
        // When media is GONE (no media), the slider column expands to
        // full width on its own, and the Play last song pill above it
        // (added earlier) stays visible.
        val combinedRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }
        }
        combinedRow.addView(mediaCard, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
        // vc40: slider column stretches to the media island's height
        // (MATCH_PARENT in the row) instead of wrapping to its own
        // shorter height, so the two islands are the same height and
        // look balanced side by side.
        combinedRow.addView(sliderColumn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f).apply {
            setMargins(dp(8), 0, 0, 0)
        })
        // vc105: combinedRow is NOT added here.
        // It is added after tileCard below, in an
        // order that depends on playing state (see
        // the block after tileCard is built).
        val tileCard = card()
        // vc42 right-edge fix: card() pads 12dp each side; the tile
        // card gets 8dp horizontally so the grid has room to fit.
        tileCard.setPadding(dp(8), dp(12), dp(8), dp(12))
        tileCard.addView(title("Chức năng"))
        tileCard.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)) })
        val grid = GridLayout(ctx).apply { columnCount = 5 }
        // vc30 LOCAL-ONLY Samsung QS style tiles:
        // a 48dp circle + label below. Off = dark slate circle + white
        // icon, On = mint circle + black icon, like Samsung's Quick
        // Settings tiles. The Int overloads below use Samsung ic_sec
        // drawables that exist ONLY in the local branch (never push
        // this file to main as-is). The String overloads are kept for
        // sort / columns / labels tiles, which have no ic_sec icon.
        fun tileWithLabel(icon: String, label: String, active: Boolean = false, onClick: () -> Unit): Pair<LinearLayout, TextView> {
            val labelView = TextView(ctx).apply {
                text = label; textSize = 10f
                setTextColor(if (active) 0xFF7FE3C0.toInt() else 0xFFFFFFFF.toInt()); gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 2
                setPadding(0, dp(4), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
            }
            val circle = TextView(ctx).apply {
                text = icon; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(if (active) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (active) 0xFF7FE3C0.toInt() else 0xFF23262B.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            }
            val cardView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                // vc40 alignment: previously CENTER (both axes) centred
                // each tile's content in its 96dp cell, so a 1-line tile
                // sat lower than a 2-line tile in the same row and the
                // circles did not line up. Now content starts at the
                // top, circles all at the same height, labels first
                // line at the same baseline, second line below it.
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { resetTimer(); onClick() }
            }
            cardView.addView(circle)
            cardView.addView(labelView)
            return cardView to labelView
        }
        fun tile(label: String, icon: String, onClick: () -> Unit): LinearLayout =
            tileWithLabel(icon, label, false, onClick).first
        fun tileRow(label: String, icon: String, onClick: () -> Unit) {
            grid.addView(tile(label, icon, onClick),
                GridLayout.LayoutParams().apply { width = dp(56); height = dp(88); setMargins(dp(3), dp(6), dp(3), dp(6)) })
        }
        fun tileRowActive(label: String, icon: String, active: Boolean, onClick: () -> Unit) {
            grid.addView(tileWithLabel(icon, label, active, onClick).first,
                GridLayout.LayoutParams().apply { width = dp(56); height = dp(88); setMargins(dp(3), dp(6), dp(3), dp(6)) })
        }
        // Int overloads (vc30 local): same QS circle, but an ImageView
        // drawable instead of a Unicode TextView symbol.
        fun tileWithLabel(iconRes: Int, label: String, active: Boolean = false, onClick: () -> Unit): Pair<LinearLayout, TextView> {
            val labelView = TextView(ctx).apply {
                text = label; textSize = 10f
                setTextColor(if (active) 0xFF7FE3C0.toInt() else 0xFFFFFFFF.toInt()); gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 2
                setPadding(0, dp(4), 0, 0)
                setLineSpacing(dp(2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(32))
            }
            val circle = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setImageResource(iconRes)
                setColorFilter(if (active) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (active) 0xFF7FE3C0.toInt() else 0xFF23262B.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            }
            val cardView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                // vc40 alignment: previously CENTER (both axes) centred
                // each tile's content in its 96dp cell, so a 1-line tile
                // sat lower than a 2-line tile in the same row and the
                // circles did not line up. Now content starts at the
                // top, circles all at the same height, labels first
                // line at the same baseline, second line below it.
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { resetTimer(); onClick() }
            }
            cardView.addView(circle)
            cardView.addView(labelView)
            return cardView to labelView
        }
        fun tile(label: String, iconRes: Int, onClick: () -> Unit): LinearLayout =
            tileWithLabel(iconRes, label, false, onClick).first
        fun tileRow(label: String, iconRes: Int, onClick: () -> Unit) {
            grid.addView(tile(label, iconRes, onClick),
                GridLayout.LayoutParams().apply { width = dp(56); height = dp(88); setMargins(dp(3), dp(6), dp(3), dp(6)) })
        }
        fun tileRowActive(label: String, iconRes: Int, active: Boolean, onClick: () -> Unit) {
            grid.addView(tileWithLabel(iconRes, label, active, onClick).first,
                GridLayout.LayoutParams().apply { width = dp(56); height = dp(88); setMargins(dp(3), dp(6), dp(3), dp(6)) })
        }
        // vc31: Rotate is ONE tile cycling FOLLOW -> LOCK -> SYSTEM.
        // Press 1 = FOLLOW (FULL_SENSOR host, follows flip direction),
        // press 2 = LOCK (fixed host at current rotation),
        // press 3 = SYSTEM (remove host, back to Samsung default),
        // press 4 = FOLLOW again. Label shows the CURRENT mode; the
        // tile is mint/active while a host exists, dark when SYSTEM.
        run {
            val currentMode = store.load().rotateMode
            val rotateLabel = when (currentMode) {
                0 -> "Theo lật"
                1 -> "Khóa xoay"
                else -> "Mặc định"
            }
            val rotateIcon = when (currentMode) {
                1 -> R.drawable.ic_cflip_lock
                else -> R.drawable.ic_sec_rotate
            }
            // vc33: 9Flip toggle tiles re-render in place and do NOT close
            // the panel. Previously CFlip closed the panel and hid+re-showed
            // the bubble here, so the bubble could disappear entirely if
            // re-show failed or assistiveTouchEnabled was off.
            // vc33 fix: a local val cannot be referenced inside its own
            // initializer lambda (Kotlin: unresolved reference). Use a
            // holder declared BEFORE the tile, assigned right after.
            var rotateTileRef: Pair<LinearLayout, TextView>? = null
            val rotateTile = tileWithLabel(rotateIcon, rotateLabel, currentMode != 2) {
                // vc39 FIX: previously `next` was computed from `currentMode`,
                // a val captured when the panel was built. First click
                // worked, second click recomputed the SAME next, so the
                // tile looked stuck until the panel was rebuilt (close +
                // reopen, or toggle AssistiveTouch off/on). Now every
                // click re-reads the stored mode, so the cycle
                // FOLLOW -> LOCK -> SYSTEM -> FOLLOW works repeatedly
                // in the same panel, with no reopen needed.
                val curMode = store.load().rotateMode
                val next = when (curMode) { 0 -> 1; 1 -> 2; else -> 0 }
                store.save(store.load().copy(rotateMode = next))
                CoverRotateState.contentFallback = false
                when (next) {
                    0 -> {
                        val svcOn = com.cuong.cflip.service.MyAccessibilityService.instance != null
                        CoverRotateHostManager.follow(ctx.applicationContext, display)
                        android.widget.Toast.makeText(
                            ctx,
                            if (svcOn) "Đã bật Xoay theo hướng lật — host FULL_SENSOR đang giữ, hãy lật máy để kiểm tra."
                            else "Đã bật Xoay theo hướng lật (chưa có Accessibility — nếu không xoay, bật Accessibility cho CFlip rồi bấm lại).",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                    1 -> {
                        CoverRotateHostManager.lock(ctx.applicationContext, display)
                        android.widget.Toast.makeText(
                            ctx, "Đã khóa xoay màn phụ ở hướng hiện tại.",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        CoverRotateHostManager.remove()
                        android.widget.Toast.makeText(
                            ctx, "Đã về xoay mặc định hệ thống — gỡ host xoay.",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                // vc33: panel stays open, bubble untouched. Update the
                // tile itself in place so the new mode is visible immediately.
                val newLabel = when (next) { 0 -> "Theo lật"; 1 -> "Khóa xoay"; else -> "Mặc định" }
                val newIcon = if (next == 1) R.drawable.ic_cflip_lock else R.drawable.ic_sec_rotate
                val newActive = next != 2
                rotateTileRef?.second?.text = newLabel
                rotateTileRef?.second?.setTextColor(if (newActive) 0xFF7FE3C0.toInt() else 0xFFFFFFFF.toInt())
                val circle = rotateTileRef?.first?.getChildAt(0) as? ImageView ?: return@tileWithLabel
                circle.setImageResource(newIcon)
                circle.setColorFilter(if (newActive) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                (circle.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                    if (newActive) 0xFF7FE3C0.toInt() else 0xFF23262B.toInt())
            }
            rotateTileRef = rotateTile
            grid.addView(rotateTile.first, GridLayout.LayoutParams().apply { width = dp(56); height = dp(88); setMargins(dp(3), dp(6), dp(3), dp(6)) })
        }
        // vc39: Đèn was a plain tileRow with no active state, so
        // toggling it twice looked like nothing happened. Now it is a
        // tileWithLabel with a ref: each click re-reads QuickControls
        // (torchOn is updated by setTorch itself) and repaints the
        // circle mint when on, dark when off, in place.
        run {
            var torchTileRef: Pair<LinearLayout, TextView>? = null
            val torchTile = tileWithLabel(R.drawable.ic_sec_flashlight, "Đèn", QuickControls.torchOn) {
                val nextOn = !QuickControls.torchOn
                QuickControls.setTorch(ctx, nextOn)
                val nowOn = QuickControls.torchOn
                torchTileRef?.second?.setTextColor(if (nowOn) 0xFF7FE3C0.toInt() else 0xFFFFFFFF.toInt())
                val circle = torchTileRef?.first?.getChildAt(0) as? ImageView
                circle?.setColorFilter(if (nowOn) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                (circle?.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                    if (nowOn) 0xFF7FE3C0.toInt() else 0xFF23262B.toInt())
            }
            torchTileRef = torchTile
            grid.addView(torchTile.first, GridLayout.LayoutParams().apply { width = dp(56); height = dp(88); setMargins(dp(3), dp(6), dp(3), dp(6)) })
        }
        tileRow("Wi-Fi", R.drawable.ic_cflip_wifi) { hide(); runCatching { QuickControls.openWifiPanel(ctx) } }
        tileRow("Bluetooth", R.drawable.ic_cflip_bluetooth) { hide(); runCatching { QuickControls.openBluetoothSettings(ctx) } }
        tileRow("Internet", R.drawable.ic_cflip_internet) { hide(); runCatching { QuickControls.openInternetSettings(ctx.applicationContext) } }
        tileRow("NFC", R.drawable.ic_cflip_nfc) { hide(); runCatching { QuickControls.openNfcPanel(ctx) } }
        tileRow("Khóa màn hình", R.drawable.ic_cflip_lock) { a11yAction("Khóa máy") { it.lockScreen() } }
        tileRow("Gần đây", R.drawable.ic_sec_recent) { a11yAction("Gần đây") { it.openRecents() } }
        tileRow("Thông báo", R.drawable.ic_sec_notification) { selectTab(1) }
        tileRow("Cài đặt nhanh", R.drawable.ic_sec_settings) { a11yAction("Cài đặt nhanh") { it.openQuickSettings() } }
        tileRow("Cài đặt", R.drawable.ic_sec_settings) { hide(); runCatching { QuickControls.openDeviceSettings(ctx) } }
        // vc41: Chụp màn hình works directly - Accessibility
        // GLOBAL_ACTION_TAKE_SCREENSHOT (MyAccessibilityService).
        // Panel hides first, so the shot captures what is underneath,
        // not the panel itself, like Samsung's QS screenshot tile.
        // Quay màn hình CANNOT be started directly by a third-party
        // app: Android has no public intent to start the system
        // recorder, and a full in-app MediaProjection recorder is a
        // separate large build (permission activity + encoder +
        // foreground service + stop control). The supported route,
        // like the other system tiles here, is to open Samsung's
        // Quick Settings where its own Quay màn hình tile lives.
        // vc42 screenshot: vc41's global action targets the default
        // display, so folded it looked dead. Now: hide panel, wait
        // 300ms for the panel to be gone, then capture the COVER
        // display itself via the service's takeScreenshot(displayId)
        // and save it to Gallery. If that API fails, fall back to
        // the global action (which may capture the main display).
        // vc44 screenshot: vc42's takeScreenshot(cover) failed
        // in practice because canTakeScreenshot was never declared
        // (see service comment), so it fell back to the global
        // action which captures the MAIN display - exactly the bug
        // reported. vc44 declares the capability, waits 500ms for
        // the panel to be fully gone, and REMOVES the silent main
        // fallback: a failed cover shot now reports its real error
        // code instead of saving a wrong-display photo.
        tileRow("Chụp màn hình", R.drawable.ic_cflip_screenshot) {
            val svc = MyAccessibilityService.instance
            if (svc == null) {
                android.widget.Toast.makeText(ctx, "Chụp màn hình không chạy: cần bật Accessibility cho CFlip", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                hide()
                handler.postDelayed({
                    svc.takeScreenshotForDisplay(display.displayId) { ok, msg ->
                        if (ok) {
                            showCoverMessage(ctx, display, "\u0110\u00e3 ch\u1ee5p m\u00e0n ph\u1ee5 \u2713\n" + msg)
                            android.widget.Toast.makeText(ctx, "Đã chụp màn phụ, lưu vào Gallery: $msg", android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            showCoverMessage(ctx, display, "Ch\u1ee5p th\u1ea5t b\u1ea1i: " + msg)
                            android.widget.Toast.makeText(ctx, "Chụp màn phụ thất bại ($msg). CFlip không chụp màn chính thay thế để khỏi nhầm ảnh - bấm lại sau 1 giây nếu mã là chụp quá nhanh.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }, 500)
            }
        }
        // vc67: vc61 pending flag removed - dual is now enabled
        // by MirrorPermissionActivity, so "dual already on" is
        // handled there (brief settle, then dialog), not here.
        tileRow("Phản chiếu", R.drawable.ic_sec_cast) {
            if (com.cuong.cflip.mirror.MirrorState.active) {
                ctx.startService(Intent(ctx, com.cuong.cflip.service.ScreenMirrorService::class.java)
                    .setAction(com.cuong.cflip.service.ScreenMirrorService.ACTION_STOP))
                runCatching { com.cuong.cflip.service.DualDisplayHelper.disable() }
                // vc61: clear active here too, not only in service
                // cleanup, so a missed cleanup cannot leave the
                // tile stuck in "stop" mode (tap then looks dead
                // because it only stops an already-dead mirror).
                com.cuong.cflip.mirror.MirrorState.active = false
                showCoverMessage(ctx, display, "Đã dừng phản chiếu")
                // vc45: service cleanup now restores AssistiveTouch,
                // so stopping from here is no longer a one-way door.
                android.widget.Toast.makeText(ctx, "Đã dừng phản chiếu, AssistiveTouch sẽ hiện lại", android.widget.Toast.LENGTH_SHORT).show()
                hide()
            } else {
                // vc46: vc45 always launched the permission activity
                // on the main display, so a folded user saw nothing
                // happen except "mở máy ra" - and if they did not
                // unfold, mirror looked dead. Now try the permission
                // activity ON THE COVER first (same activity, cover
                // displayId); only if the system refuses a cover
                // activity (Samsung blocks some cover activities)
                // fall back to main + the unfold toast.
                // vc53 ORDER FIX: vc51/vc52 refused BEFORE trying
                // dual enable when the main display was OFF (folded),
                // so dual was never even attempted in the exact
                // situation where the user taps the tile on the
                // cover panel - and the tap looked like "nothing
                // happens". 9Flip does NOT check main ON first; it
                // toggles dual first, then checks dual state. vc53
                // follows that order: try dual first regardless of
                // folded/open, then let the service gates decide
                // after permission. Only a missing main display
                // (impossible in practice) refuses here.
                // vc61 TAP PROOF: vc60 report was "tap does
                // nothing at all" - no Toast, no dialog, no pill.
                // Toasts are rate-limited by Android after many
                // taps (vc46 symptom), so a Toast-only flow can
                // look dead even when it ran. Show a WindowManager
                // pill on the cover FIRST, in normal mode before
                // dual is ON (overlays still show here), so every
                // tap is visibly registered even if later Toasts
                // are suppressed or the permission dialog ends up
                // on main.
                showCoverMessage(ctx, display, "Đã bấm Phản chiếu - đang mở xin quyền...")
                // vc84 COVER DIALOG: hide the full-screen panel overlay
                // BEFORE launching the host, so no cover overlay can sit
                // on top of the system permission dialog (vc83: dialog
                // never appeared on cover, only rotation). The pill above
                // is a separate view and survives hide().
                hide()
                val mainDisplay = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                if (mainDisplay == null) {
                    android.widget.Toast.makeText(ctx, "Ph\u1ea3n chi\u1ebfu ch\u1ec9 d\u00f9ng \u0111\u01b0\u1ee3c khi M\u1ede M\u00e1y", android.widget.Toast.LENGTH_LONG).show()
                    return@tileRow
                }
                // vc83 HOST-FIRST: tile no longer opens the permission
                // activity directly. It opens DualHostActivity on the
                // cover (fresh task, CLEAR_TASK); the host enables dual
                // only when top-resumed (9Flip order), then opens the
                // dialog-only permission on the cover. Permission never
                // enables dual itself any more.
                val hostIntent = Intent(ctx.applicationContext, com.cuong.cflip.mirror.DualHostActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                val coverHostOk = runCatching {
                    val opts = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle()
                    ctx.startActivity(hostIntent, opts)
                    true
                }.getOrDefault(false)
                if (coverHostOk) {
                    android.widget.Toast.makeText(ctx, "Đã mở xin quyền phản chiếu trên màn phụ - bấm Cho phép để bắt đầu", android.widget.Toast.LENGTH_LONG).show()
                    showCoverMessage(ctx, display, "Đã mở xin quyền trên màn phụ - bấm Cho phép")
                } else {
                    android.widget.Toast.makeText(
                        ctx, "Mở máy ra để đồng ý phản chiếu màn hình",
                        android.widget.Toast.LENGTH_LONG).show()
                    showCoverMessage(ctx, display, "Mở máy ra để đồng ý phản chiếu màn hình")
                    runCatching { ctx.applicationContext.startActivity(hostIntent) }
                }
            }
        }
        // vc35 missing system tiles from 9Flip (f0/u list).
        // Android does not let a third-party app toggle mobile data,
        // hotspot, airplane, location or battery saver directly, so
        // those tiles open the system settings page for that feature
        // (the supported route, like 9Flip's Settings-opening tiles),
        // with an honest toast. DND CAN toggle when CFlip has
        // Notification Policy Access, otherwise it opens that grant page.
        tileRow("Dữ liệu", R.drawable.ic_cflip_mobiledata) {
            hide()
            android.widget.Toast.makeText(ctx, "Mở Cài đặt Dữ liệu di động để bật/tắt (Android không cho app thường bật/tắt trực tiếp)", android.widget.Toast.LENGTH_LONG).show()
            runCatching { ctx.applicationContext.startActivity(Intent(Settings.ACTION_DATA_USAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        tileRow("Chia sẻ Internet", R.drawable.ic_cflip_hotspot) {
            hide()
            android.widget.Toast.makeText(ctx, "Mở Cài đặt Chia sẻ kết nối để bật/tắt điểm phát sóng", android.widget.Toast.LENGTH_LONG).show()
            runCatching {
                val appCtx = ctx.applicationContext
                val intent = Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(appCtx.packageManager) != null) appCtx.startActivity(intent)
                else appCtx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        tileRow("Máy bay", R.drawable.ic_cflip_airplane) {
            hide()
            runCatching {
                val appCtx = ctx.applicationContext
                val intent = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(appCtx.packageManager) != null) appCtx.startActivity(intent)
                else appCtx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        tileRow("Vị trí", R.drawable.ic_cflip_location) {
            hide()
            runCatching { ctx.applicationContext.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        // vc48 REMOVED by user request: Battery Saver tile and Screen Record tile are gone.
        // vc36 DND toggle in place, like rotate: press once -> on,
        // press again -> off, and the tile itself changes (moon filled
        // + mint circle when on, moon outline + dark circle when off).
        // Previously the tile never updated, so a successful toggle
        // looked like nothing happened until the panel reopened.
        run {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val dndOn = nm?.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
            var dndTileRef: Pair<LinearLayout, TextView>? = null
            val dndTile = tileWithLabel(if (dndOn) R.drawable.ic_cflip_dnd else R.drawable.ic_cflip_dnd_off, "Không làm phiền", dndOn) {
                if (nm != null && nm.isNotificationPolicyAccessGranted) {
                    val next = if (nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                        android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                    else android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                    nm.setInterruptionFilter(next)
                    val nowOn = next == android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                    android.widget.Toast.makeText(ctx, if (nowOn) "Đã bật Không làm phiền" else "Đã tắt Không làm phiền", android.widget.Toast.LENGTH_SHORT).show()
                    dndTileRef?.second?.setTextColor(if (nowOn) 0xFF7FE3C0.toInt() else 0xFFFFFFFF.toInt())
                    val circle = dndTileRef?.first?.getChildAt(0) as? ImageView
                    circle?.setImageResource(if (nowOn) R.drawable.ic_cflip_dnd else R.drawable.ic_cflip_dnd_off)
                    circle?.setColorFilter(if (nowOn) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                    (circle?.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                        if (nowOn) 0xFF7FE3C0.toInt() else 0xFF23262B.toInt())
                } else {
                    hide()
                    android.widget.Toast.makeText(ctx, "Cần cấp quyền Không làm phiền cho CFlip", android.widget.Toast.LENGTH_LONG).show()
                    runCatching { ctx.applicationContext.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            dndTileRef = dndTile
            grid.addView(dndTile.first, GridLayout.LayoutParams().apply { width = dp(56); height = dp(88); setMargins(dp(3), dp(6), dp(3), dp(6)) })
        }
        // vc44 balance: the grid (5 x 62dp = 310dp) used to sit
        // left-aligned in a ~328dp card, leaving all slack on the
        // right, so the island looked shifted left. Centring the
        // grid gives equal ~9dp margins on both sides.
        tileCard.addView(grid, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        // vc108 MEDIA CARD POSITION (user, 27/10,
        // replaces vc105 playing-based rule):
        // DEFAULT (no media session): media card
        // at the VERY BOTTOM, after Widget.
        // Order: PlayLastPill, sliders only
        // (horizontal, full width), Function,
        // Widget, media card (empty, vc106), spacer.
        // MEDIA EXISTS (session present, playing
        // OR paused): media card goes back up into
        // combinedRow, horizontally alongside the
        // brightness + volume sliders, unchanged
        // from the original layout. Order: combined
        // row (media + sliders), Function, Widget.
        // Sliders orientation is unchanged by this
        // rule: playing -> vertical (vc105, confirmed
        // correct), paused -> horizontal.
        // Layout is decided at panel open, from
        // mediaInfo at that moment - like the rest
        // of this panel, which builds once and only
        // ticks text/progress/wave while open.
        // vc123 LIVE (user, 28/10): a session
        // starting/ending while the panel is open
        // NO LONGER needs close + reopen - the
        // 1s tick spots the existence transition
        // in updateMediaViews() and moves the card
        // + pill immediately via the sync lambda
        // below (wave lowering on stop already
        // updated while open since vc108).
        if (mediaInfo != null) {
            controlContent.addView(combinedRow)
            controlContent.addView(tileCard)
            widgetCardDeferred?.let { controlContent.addView(it) }
        } else {
            combinedRow.removeView(mediaCard)
            controlContent.addView(combinedRow)
            controlContent.addView(tileCard)
            widgetCardDeferred?.let { controlContent.addView(it) }
            mediaCard.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(12))
            }
            controlContent.addView(mediaCard)
        }
        // vc41: bottom spacer so the last card (Function island or
        // Widget) is never hidden behind the cover's bottom gesture
        // bar when scrolled to the end.
        controlContent.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        })
        // vc123 LIVE MEDIA CARD (see updateMediaViews transition
        // block): the same two layouts the vc108 build-time block
        // above chooses, but applied while the panel is open.
        // Defined here - after every view it touches exists - and
        // assigned to mediaCardPositionSync so the earlier-defined
        // updateMediaViews() can invoke it without a forward
        // reference; cachedReopen re-assigns it before its own
        // updateMediaViews() call (see below).
        val syncMediaCardPosition = { hasSession: Boolean ->
            if (hasSession) {
                if (mediaCard.parent !== combinedRow) {
                    (mediaCard.parent as? android.view.ViewGroup)?.removeView(mediaCard)
                    mediaCard.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f)
                    combinedRow.addView(mediaCard, 0)
                    mediaCard.alpha = 0f
                    mediaCard.translationY = -dp(10).toFloat()
                    mediaCard.scaleX = 0.985f
                    mediaCard.scaleY = 0.985f
                    mediaCard.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                        .setDuration(190L)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
                playLastPill.visibility = View.GONE
            } else {
                if (mediaCard.parent !== controlContent) {
                    (mediaCard.parent as? android.view.ViewGroup)?.removeView(mediaCard)
                    mediaCard.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, dp(12))
                    }
                    val widget = widgetCardDeferred
                    val insertAt = if (widget != null) {
                        val wi = controlContent.indexOfChild(widget)
                        if (wi >= 0) wi + 1 else controlContent.childCount
                    } else {
                        (controlContent.childCount - 1).coerceAtLeast(0)
                    }
                    controlContent.addView(mediaCard, insertAt)
                }
                playLastPill.visibility = View.VISIBLE
            }
        }
        mediaCardPositionSync = syncMediaCardPosition

        // ---- Tab 2: Thông báo ---- (vc44 lazy, like apps:
        // notification rows used to build on EVERY panel open even
        // though the user only sees Điều khiển first. Now they build
        // only on first switch to tab 1, cutting panel-open cost.)
        var notifTabBuilt = false
        buildNotifTab = {
            if (!notifTabBuilt) {
                notifTabBuilt = true
        // ---- Tab 2: Thông báo ----
        notifContent.addView(title("Thông báo"))
        val notifs = NotificationState.active.value
        if (notifs.isEmpty()) {
            notifContent.addView(TextView(ctx).apply {
                text = "Chưa có thông báo.\nNếu có thông báo mà không hiện, cần cấp quyền Truy cập thông báo cho CFlip (xem tab CFlip)."
                textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
            })
        }
        for (n in notifs) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                background = rounded(cardColor, 12)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, dp(8))
                }
            }
            row.addView(TextView(ctx).apply {
                text = "${n.title}\n${n.text}"; textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(modernButton("Mở", true).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); setOnClickListener { NotificationController.open(n.key); hide() } })
            row.addView(modernButton("Xóa").apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT); setOnClickListener { NotificationController.dismiss(n.key); row.visibility = View.GONE } })
            notifContent.addView(row)
        }

            }
        }

        // ---- Tab 3: Ứng dụng ---- (lazy, see selectTab above)
        var appsTabBuilt = false
        var appsEmptyRetryDone = false
        buildAppsTab = {
            if (!appsTabBuilt) {
                // vc56 APPS TAB FIX: vc41-vc55 set appsTabBuilt =
                // true BEFORE querying. If the query threw or
                // returned empty, the tab stayed blank forever
                // for that panel instance - tapping the tab again
                // did nothing because the flag was already true.
                // Now the flag is set only after a non-empty grid
                // is added, the query is guarded, and an empty
                // result shows a retry button instead of a blank
                // tab. Clearing first also prevents a duplicate
                // title on retry.
                appsContent.removeAllViews()
        appsContent.addView(title("Ứng dụng"))
        run {
            val appRepo = com.cuong.cflip.data.AppRepository(ctx.applicationContext)
            val allApps = runCatching { appRepo.loadLaunchableApps() }.getOrDefault(emptyList())
            if (allApps.isEmpty()) {
                appsContent.addView(android.widget.TextView(ctx).apply {
                    text = "Không tải được danh sách ứng dụng (0 app) - bấm Thử lại."
                    textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
                })
                appsContent.addView(modernButton("Thử lại").apply {
                    setOnClickListener { appsContent.removeAllViews(); buildAppsTab?.invoke() }
                })
                // vc59 AUTO-RETRY: after an app update/install the
                // first PackageManager query can transiently return
                // empty (system still indexing). Vc56 left that to
                // a manual "Thử lại" tap, which reads as "tab is
                // broken". Now the tab retries itself once after
                // 800ms, so a transient empty no longer needs a
                // tap to recover. Manual retry stays for a real
                // persistent empty.
                if (!appsEmptyRetryDone) {
                    appsEmptyRetryDone = true
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        if (!appsTabBuilt) { appsContent.removeAllViews(); buildAppsTab?.invoke() }
                    }, 800)
                }
                return@run
            }
            val settings = store.load()
            val recentOrder = com.cuong.cflip.data.RecentAppsRepository(ctx).recentPackages(100)
            // vc41: installTimes was queried for every package on
            // every panel open, but only used when sortMode == 2.
            // Now it is queried only in that case.
            val installTimes = if (settings.sortMode == 2) appRepo.installTimes(allApps.map { it.packageName }) else emptyMap()
            val ordered = when (settings.sortMode) {
                1 -> allApps.sortedBy { recentOrder.indexOf(it.packageName).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                2 -> allApps.sortedByDescending { installTimes[it.packageName] ?: 0L }
                else -> allApps
            }
            val cols = when (settings.columns) { 0 -> 4; in 3..6 -> settings.columns; else -> 4 }
            val appGrid = GridLayout(ctx).apply { columnCount = cols }
            for (app in ordered) {
                appGrid.addView(Button(ctx).apply {
                    text = app.label; textSize = 10f; isAllCaps = false
                    background = rounded(0xFF23262B.toInt(), 12)
                    setTextColor(0xFFFFFFFF.toInt())
                    if (app.icon != null) {
                        val d = app.icon.constantState?.newDrawable()?.mutate() ?: app.icon
                        d.setBounds(0, 0, dp(40), dp(40))
                        setCompoundDrawables(null, d, null, null)
                    }
                    setOnClickListener {
                        com.cuong.cflip.display.AppLauncher.launch(ctx, app.packageName, display.displayId)
                        hide()
                    }
                }, GridLayout.LayoutParams().apply { width = dp(76); height = dp(96); setMargins(dp(4), dp(6), dp(4), dp(6)) })
            }
            appsContent.addView(appGrid)
            appsTabBuilt = true
        }

            }
        }
        // ---- Tab 4: CFlip ----
        cflipContent.addView(title("CFlip Launcher"))
        cflipContent.addView(TextView(ctx).apply {
            text = "Mở CFlip trên màn hình chính để cài đặt và tùy chỉnh:\n- AssistiveTouch: cỡ nút, tự mờ, vị trí\n- Widget, sắp xếp, số cột, hình nền cover\n- Quyền: Accessibility, Thông báo, Overlay"
            textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
        })
        fun openOnMain(intent: Intent) {
            // Application context, no launch display id: the system puts
            // this on the main display, not on the cover.
            ctx.applicationContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hide()
        }
        cflipContent.addView(modernButton("Mở CFlip (màn hình chính)", true).apply {
            setOnClickListener {
                openOnMain(Intent(ctx.applicationContext, com.cuong.cflip.MainActivity::class.java))
            }
        })
        cflipContent.addView(modernButton("Quyền Accessibility").apply {
            setOnClickListener { openOnMain(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        cflipContent.addView(modernButton("Quyền Truy cập thông báo").apply {
            setOnClickListener { openOnMain(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        })
        cflipContent.addView(modernButton("Quyền Hiện trên ứng dụng khác").apply {
            setOnClickListener {
                openOnMain(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}")))
            }
        })

        // vc110: capture everything the next
        // open needs to reuse this root. The
        // lambda captures buildPanel locals by
        // reference, so resetting the lazy-tab
        // flags here really resets the closures
        // selectTab / buildNotifTab / buildAppsTab
        // use, and mediaTick really restarts.
        cachedPanelDisplayId = display.displayId
        cachedPanelDisplayW = ctx.resources.displayMetrics.widthPixels
        cachedPanelDisplayH = ctx.resources.displayMetrics.heightPixels
        cachedMediaExists = mediaInfo != null
        cachedMediaPlaying = mediaInfo?.isPlaying == true
        cachedSettingsKey = settingsKeyFor(store.load())
        cachedAppListVersion = com.cuong.cflip.data.AppRepository.appListVersion()
        cachedFavoritesKey = runCatching {
            com.cuong.cflip.data.FavoritesStore(ctx.applicationContext).getFavorites().sorted().joinToString(",")
        }.getOrElse { "" }
        cachedHiddenKey = runCatching {
            com.cuong.cflip.data.HiddenAppsStore(ctx.applicationContext).getHidden().sorted().joinToString(",")
        }.getOrElse { "" }
        cachedReopen = { tab: Int ->
            notifTabBuilt = false
            notifContent.removeAllViews()
            appsTabBuilt = false
            appsEmptyRetryDone = false
            appsContent.removeAllViews()
            refreshBright2()
            refreshVol2()
            mediaCardPositionSync = syncMediaCardPosition
            updateMediaViews()
            // vc111: clock widget refresh on reopen
            // (time / date / notification count /
            // battery were frozen at build time).
            clockTimeView?.text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            clockDetailView?.let { detail ->
                val batteryPct = runCatching {
                    val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                    if (level >= 0 && scale > 0) level * 100 / scale else -1
                }.getOrDefault(-1)
                val battery = if (batteryPct >= 0) " · Pin $batteryPct%" else ""
                detail.text = java.text.SimpleDateFormat("EEE, dd/MM", java.util.Locale.getDefault()).format(java.util.Date()) +
                    " · TB: ${NotificationState.active.value.size}$battery"
            }
            scroll.scrollTo(0, 0)
            selectTab(tab.coerceIn(0, 3))
            mediaTickRunnable = mediaTick
            handler.postDelayed(mediaTick, 1000)
        }
        if (initialTab == 2) buildAppsTab?.invoke()
        if (initialTab == 1) buildNotifTab?.invoke()
        return outer
    }
}
