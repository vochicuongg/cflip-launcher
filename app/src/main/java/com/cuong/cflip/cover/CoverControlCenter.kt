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
    private var waveTickRunnable: Runnable? = null
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

    fun show(appContext: Context, display: Display, initialTab: Int = 0): Result<Unit> = runCatching {
        if (display.displayId == Display.DEFAULT_DISPLAY) {
            throw IllegalArgumentException("Control Center is cover-only, refusing default display")
        }
        hidePanelOnly()
        val displayContext = appContext.createDisplayContext(display)
        val manager = displayContext.getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("No WindowManager for display ${display.displayId}")
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
                val root = buildPanel(displayContext, display, initialTab)
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
                scheduleDismiss()
                Log.i(TAG, "Control Center shown on display ${display.displayId}")
            }.onFailure { e ->
                runCatching { manager.removeView(loading) }
                loadingView = null
                Log.w(TAG, "Control Center build failed", e)
                android.widget.Toast.makeText(displayContext, "Mở CFlip thất bại, thử chạm lại", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        loadingRunnable = r
        handler.post(r)
        Unit
    }.onFailure { Log.w(TAG, "Control Center addView failed", it) }

    fun hide() {
        hidePanelOnly()
    }

    // vc48: transient cover message pill (screenshot feedback).
    private fun showCoverMessage(display: Display, text: String) {
        runCatching {
            runCatching {
                val v = shotFeedbackView; val w = shotFeedbackWm
                if (v != null && w != null) w.removeView(v)
            }
            shotFeedbackView = null; shotFeedbackWm = null
            val dctx = appContext.createDisplayContext(display)
            val wm = dctx.getSystemService(WindowManager::class.java) ?: return@runCatching
            val tv = TextView(dctx).apply {
                this.text = text
                textSize = 14f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xE0000000.toInt())
                setPadding(dp(16), dp(12), dp(16), dp(12))
                gravity = Gravity.CENTER
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
            wm.addView(tv, params)
            shotFeedbackView = tv; shotFeedbackWm = wm
            handler.postDelayed({
                runCatching { wm.removeView(tv) }
                if (shotFeedbackView === tv) { shotFeedbackView = null; shotFeedbackWm = null }
            }, 2500)
        }
    }

    private fun hidePanelOnly() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        mediaTickRunnable?.let { handler.removeCallbacks(it) }
        mediaTickRunnable = null
        waveTickRunnable?.let { handler.removeCallbacks(it) }
        waveTickRunnable = null
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
            view = null; wm = null; panelParams = null
            Log.i(TAG, "Control Center hidden")
        }
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
        outer.addView(tabBar); outer.addView(scroll)
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
                    CoverControlCenterManager.show(ctx.applicationContext, display, 0)
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
                    val batteryPct = runCatching {
                        val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                        val level = intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                        val scale = intent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                        if (level >= 0 && scale > 0) level * 100 / scale else -1
                    }.getOrDefault(-1)
                    widgetCard.addView(TextView(ctx).apply {
                        text = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
                        textSize = 28f; setTextColor(0xFFFFFFFF.toInt())
                        setTypeface(null, android.graphics.Typeface.BOLD)
                    })
                    widgetCard.addView(TextView(ctx).apply {
                        val battery = if (batteryPct >= 0) " · Pin $batteryPct%" else ""
                        text = java.text.SimpleDateFormat("EEE, dd/MM", java.util.Locale.getDefault()).format(java.util.Date()) +
                            " · TB: ${NotificationState.active.value.size}$battery"
                        textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
                    })
                }
                if ("appgrid" in chosen) {
                    widgetCard.addView(TextView(ctx).apply {
                        text = "Ứng dụng yêu thích"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
                    })
                    val favApps = run {
                        val repo = com.cuong.cflip.data.AppRepository(ctx)
                        val byPkg = repo.loadLaunchableApps().associateBy { it.packageName }
                        com.cuong.cflip.data.FavoritesStore(ctx).getFavorites().mapNotNull { byPkg[it] }.take(8)
                    }
                    if (favApps.isEmpty()) {
                        widgetCard.addView(TextView(ctx).apply {
                            text = "Chưa ghim app nào — ghim ở CFlip rồi Widget này mới có nội dung."
                            textSize = 12f; setTextColor(0xFFFFFFFF.toInt())
                        })
                    } else {
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
                            CoverControlCenterManager.show(ctx.applicationContext, display, 0)
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
                            CoverControlCenterManager.show(ctx.applicationContext, display, 0)
                        }
                    })
                }
                widgetCard.addView(modernButton("Xóa tất cả Widget đã thêm").apply {
                    setOnClickListener {
                        store.save(store.load().copy(coverWidgets = ""))
                        hidePanelOnly()
                        CoverControlCenterManager.show(ctx.applicationContext, display, 0)
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
        if (mediaInfo == null) {
            mediaCard.visibility = View.GONE
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
        // vc44 One UI 8.5-style music wave: 4 mint bars next to
        // the Âm thanh title, animated while playing, flat when
        // paused. Clean-room code drawing (no Samsung asset): bar
        // heights follow a sine wave, updated by waveTick below.
        val waveBars = mutableListOf<View>()
        val waveView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(16))
        }
        for (i in 0 until 4) {
            val bar = View(ctx).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(2).toFloat()
                    setColor(0xFF7FE3C0.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(3), dp(5)).apply {
                    setMargins(dp(1), 0, dp(1), 0)
                    gravity = Gravity.BOTTOM
                }
            }
            waveBars.add(bar); waveView.addView(bar)
        }
        mediaContent.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title("Âm thanh"), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(waveView)
        })
        val mediaTitleView = TextView(ctx).apply {
            text = mediaInfo?.title ?: ""; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
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
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(16))
            if (mediaInfo != null && mediaInfo.durationMs > 0) {
                progress = mediaInfo.positionMs.toFloat() / mediaInfo.durationMs
            }
            onSeek = { frac ->
                if (mediaInfo != null && mediaInfo.durationMs > 0) {
                    runCatching { mediaRepo.seekTo((frac * mediaInfo.durationMs).toLong()) }
                    runCatching { mediaRepo.refresh() }
                    val cur = MediaState.current.value
                    if (cur != null) {
                        mediaElapsed.text = fmtTime(cur.positionMs)
                        mediaTotal.text = fmtTime(cur.durationMs)
                        progress = if (cur.durationMs > 0) cur.positionMs.toFloat() / cur.durationMs else 0f
                    }
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
        fun updateMediaViews() {
            runCatching { mediaRepo.refresh() }
            val cur = MediaState.current.value ?: return
            mediaTitleView.text = cur.title
            mediaArtistView.text = cur.artist
            mediaElapsed.text = fmtTime(cur.positionMs)
            mediaTotal.text = fmtTime(cur.durationMs)
            mediaProgress.progress = if (cur.durationMs > 0) cur.positionMs.toFloat() / cur.durationMs else 0f
            mediaPlayButtonRef?.setImageResource(if (cur.isPlaying) R.drawable.ic_cflip_pause else R.drawable.ic_cflip_play)
            // vc40: swap album art when the track changes (reference
            // comparison avoids re-setting the same bitmap every second).
            if (cur.albumArt !== lastMediaArt) {
                lastMediaArt = cur.albumArt
                mediaArtView.setImageBitmap(cur.albumArt)
            }
        }
        val mediaRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        mediaRow.addView(mediaIconButton(R.drawable.ic_cflip_media_prev).apply { setOnClickListener { resetTimer(); mediaRepo.previous(); handler.postDelayed({ updateMediaViews() }, 500); handler.postDelayed({ updateMediaViews() }, 1200) } })
        val mediaPlayButton = mediaIconButton(if (mediaInfo?.isPlaying == true) R.drawable.ic_cflip_pause else R.drawable.ic_cflip_play, true).also { mediaPlayButtonRef = it }.apply { setOnClickListener { resetTimer(); mediaRepo.playPause(); handler.postDelayed({ updateMediaViews() }, 500); handler.postDelayed({ updateMediaViews() }, 1200) } }
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
        // vc44 wave animation: 120ms tick, sine heights while
        // playing, flat 5dp bars when paused. Cancelled on hide
        // via waveTickRunnable in hidePanelOnly, like mediaTick.
        val waveTick = object : Runnable {
            override fun run() {
                if (view == null) {
                    handler.postDelayed(this, 120)
                    return
                }
                if (mediaCard.visibility == View.VISIBLE) {
                    val playing = MediaState.current.value?.isPlaying == true
                    if (playing) {
                        val t = System.currentTimeMillis() / 130.0
                        for ((idx, bar) in waveBars.withIndex()) {
                            val h = (5 + 11 * (0.5 + 0.5 * kotlin.math.sin(t + idx * 1.1))).toInt()
                            bar.layoutParams.height = dp(h)
                            bar.requestLayout()
                        }
                    } else {
                        for (bar in waveBars) {
                            bar.layoutParams.height = dp(5)
                            bar.requestLayout()
                        }
                    }
                }
                handler.postDelayed(this, 120)
            }
        }
        waveTickRunnable = waveTick
        handler.postDelayed(waveTick, 200)
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
        val sliderColumn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            setIcon(R.drawable.ic_sec_brightness)
            setRightIcon(R.drawable.ic_cflip_auto)
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
            onRightClick = {
                val cur = store.load()
                val nextManual = !cur.panelBrightnessManual
                store.save(cur.copy(panelBrightnessManual = nextManual))
                applyPanelBrightness(ctx.applicationContext)
                CoverAssistiveTouchManager.applyCoverBrightness(ctx.applicationContext)
                refreshBright2(); resetTimer()
            }
        }
        sliderColumn.addView(brightSlider2)
        sliderColumn.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(12)) })
        volSlider2 = NineFlipSlider(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
            setIcon(R.drawable.ic_sec_volume)
            setRightIcon(R.drawable.ic_sec_mute)
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
            onRightClick = {
                val (v, m) = QuickControls.volume(ctx)
                val prefs = ctx.getSharedPreferences("cflip_volume", Context.MODE_PRIVATE)
                if (v > 0) {
                    prefs.edit().putInt("saved_volume", v).apply()
                    QuickControls.setVolume(ctx, 0)
                } else {
                    val saved = prefs.getInt("saved_volume", -1)
                    val restore = if (saved > 0) saved else ((m * 0.45f).toInt()).coerceIn(1, m)
                    QuickControls.setVolume(ctx, restore)
                }
                refreshVol2(); resetTimer()
            }
        }
        sliderColumn.addView(volSlider2)
        // vc41: left (speaker) icon cycles system sound mode:
        // Âm thanh -> Rung -> Không làm phiền -> Âm thanh ...
        // This is RINGER mode, not music volume: the right (mute)
        // icon still mutes/restores STREAM_MUSIC as before.
        // Detection: DND = interruption filter NONE (if permission
        // granted) or ringer SILENT; Vibrate = ringer VIBRATE; else Sound.
        // Without Notification Policy Access, DND cannot be set, so
        // that step falls back to SILENT with a toast explaining it.
        fun soundMode(): Int {
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            return when {
                nm != null && nm.isNotificationPolicyAccessGranted &&
                    nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_NONE -> 2
                am?.ringerMode == android.media.AudioManager.RINGER_MODE_SILENT -> 2
                am?.ringerMode == android.media.AudioManager.RINGER_MODE_VIBRATE -> 1
                else -> 0
            }
        }
        fun refreshVolModeIcon() {
            volSlider2?.setIcon(when (soundMode()) {
                1 -> R.drawable.ic_cflip_vibrate
                2 -> R.drawable.ic_cflip_dnd
                else -> R.drawable.ic_sec_volume
            })
        }
        refreshVolModeIcon()
        volSlider2?.setLeftClickListener {
            val am = ctx.getSystemService(android.media.AudioManager::class.java)
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            val next = (soundMode() + 1) % 3
            when (next) {
                0 -> {
                    if (nm != null && nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                    am?.ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
                    android.widget.Toast.makeText(ctx, "Đã bật Âm thanh", android.widget.Toast.LENGTH_SHORT).show()
                }
                1 -> {
                    if (nm != null && nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
                    }
                    am?.ringerMode = android.media.AudioManager.RINGER_MODE_VIBRATE
                    android.widget.Toast.makeText(ctx, "Đã bật Rung", android.widget.Toast.LENGTH_SHORT).show()
                }
                else -> {
                    if (nm != null && nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_NONE)
                        am?.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                        android.widget.Toast.makeText(ctx, "Đã bật Không làm phiền", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        am?.ringerMode = android.media.AudioManager.RINGER_MODE_SILENT
                        android.widget.Toast.makeText(ctx, "Đã bật Im lặng (cần quyền Không làm phiền để bật DND đầy đủ)", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
            refreshVolModeIcon(); resetTimer()
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
        controlContent.addView(combinedRow)

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
                            showCoverMessage(display, "\u0110\u00e3 ch\u1ee5p m\u00e0n ph\u1ee5 \u2713\n" + msg)
                            android.widget.Toast.makeText(ctx, "Đã chụp màn phụ, lưu vào Gallery: $msg", android.widget.Toast.LENGTH_LONG).show()
                        } else {
                            showCoverMessage(display, "Ch\u1ee5p th\u1ea5t b\u1ea1i: " + msg)
                            android.widget.Toast.makeText(ctx, "Chụp màn phụ thất bại ($msg). CFlip không chụp màn chính thay thế để khỏi nhầm ảnh - bấm lại sau 1 giây nếu mã là chụp quá nhanh.", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                }, 500)
            }
        }
        tileRow("Phản chiếu", R.drawable.ic_sec_cast) {
            if (com.cuong.cflip.mirror.MirrorState.active) {
                ctx.startService(Intent(ctx, com.cuong.cflip.service.ScreenMirrorService::class.java)
                    .setAction(com.cuong.cflip.service.ScreenMirrorService.ACTION_STOP))
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
                // vc48 GATE: mirror only when main display is ON.
                val mainDisplay = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                if (mainDisplay == null || mainDisplay.state != android.view.Display.STATE_ON) {
                    android.widget.Toast.makeText(ctx, "Ph\u1ea3n chi\u1ebfu ch\u1ec9 d\u00f9ng \u0111\u01b0\u1ee3c khi M\u1ede M\u00e1y", android.widget.Toast.LENGTH_LONG).show()
                    return@tileRow
                }
                hide()
                val permIntent = Intent(ctx.applicationContext, com.cuong.cflip.mirror.MirrorPermissionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_HISTORY)
                val coverPermOk = runCatching {
                    val opts = ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle()
                    ctx.startActivity(permIntent, opts)
                    true
                }.getOrDefault(false)
                if (coverPermOk) {
                    android.widget.Toast.makeText(ctx, "Đã mở xin quyền phản chiếu trên màn phụ - bấm Cho phép để bắt đầu", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    android.widget.Toast.makeText(
                        ctx, "Mở máy ra để đồng ý phản chiếu màn hình",
                        android.widget.Toast.LENGTH_LONG).show()
                    runCatching { ctx.applicationContext.startActivity(permIntent) }
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
        controlContent.addView(tileCard)
        // 3.6.0 order: Media first, Brightness second, Function island
        // third, Widget last. Widget was built first but is added here.
        widgetCardDeferred?.let { controlContent.addView(it) }
        // vc41: bottom spacer so the last card (Function island or
        // Widget) is never hidden behind the cover's bottom gesture
        // bar when scrolled to the end.
        controlContent.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48))
        })

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
        buildAppsTab = {
            if (!appsTabBuilt) {
                appsTabBuilt = true
        appsContent.addView(title("Ứng dụng"))
        run {
            val appRepo = com.cuong.cflip.data.AppRepository(ctx)
            val allApps = appRepo.loadLaunchableApps()
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

        if (initialTab == 2) buildAppsTab?.invoke()
        if (initialTab == 1) buildNotifTab?.invoke()
        return outer
    }
}
