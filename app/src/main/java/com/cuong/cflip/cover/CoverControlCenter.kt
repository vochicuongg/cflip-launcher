package com.cuong.cflip.cover
import com.cuong.cflip.R

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

    /** Apply the panel's own brightness (3.2.0, 9Flip p6/t6 model). */
    fun applyPanelBrightness(appContext: Context) {
        val params = panelParams; val v = view; val manager = wm
        if (params == null || v == null || manager == null) return
        val st = SettingsStore(appContext).load()
        params.screenBrightness = if (st.panelBrightnessManual) st.panelBrightness.coerceIn(0.05f, 1.0f) else -1f
        runCatching { manager.updateViewLayout(v, params) }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
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
        val root = buildPanel(displayContext, display, initialTab)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        // 3.3.0: panel open = screen stays on (panel auto-dismisses
        // after 60s, so this cannot keep the cover on forever).
        root.keepScreenOn = true
        manager.addView(root, params)
        wm = manager; view = root; panelParams = params
        // Panel brightness from the saved manual value, or -1 (system).
        runCatching {
            val st = SettingsStore(appContext).load()
            params.screenBrightness = if (st.panelBrightnessManual) st.panelBrightness.coerceIn(0.05f, 1.0f) else -1f
            manager.updateViewLayout(root, params)
        }
        scheduleDismiss()
        Log.i(TAG, "Control Center shown on display ${display.displayId}")
        Unit
    }.onFailure { Log.w(TAG, "Control Center addView failed", it) }

    fun hide() {
        hidePanelOnly()
    }

    private fun hidePanelOnly() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
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
        fun mintSeekBar(): SeekBar = SeekBar(ctx).apply {
            runCatching {
                progressTintList = android.content.res.ColorStateList.valueOf(mint)
                thumbTintList = android.content.res.ColorStateList.valueOf(mint)
            }
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
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val scroll = ScrollView(ctx); scroll.addView(root)
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
        fun selectTab(i: Int) {
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
                            }, GridLayout.LayoutParams().apply { width = dp(76); height = dp(84) })
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
        val mediaCard = card()
        mediaCard.addView(title("Nhạc"))
        mediaCard.addView(TextView(ctx).apply {
            text = mediaInfo?.title ?: "Chưa có nhạc"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
        })
        // vc30 LOCAL-ONLY: media buttons use Samsung ic_sec drawables that
        // exist only in the local samsung-icons-360-vc30 branch / D:\icons\sec_only.
        // Do NOT push this file to main without replacing these with clean-room drawables.
        fun mediaIconButton(iconRes: Int, primary: Boolean = false): ImageButton = ImageButton(ctx).apply {
            setImageResource(iconRes)
            setColorFilter(if (primary) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            background = rounded(if (primary) mint else 0xFF23262B.toInt(), 24)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        val mediaRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        mediaRow.addView(mediaIconButton(R.drawable.ic_sec_media_prev).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { resetTimer(); mediaRepo.previous() } })
        mediaRow.addView(mediaIconButton(R.drawable.ic_sec_play, true).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { resetTimer(); mediaRepo.playPause() } })
        mediaRow.addView(mediaIconButton(R.drawable.ic_sec_media_next).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { resetTimer(); mediaRepo.next() } })
        mediaCard.addView(mediaRow)
        controlContent.addView(mediaCard)

        // 3.2.0 brightness: PANEL window brightness, not system.
        // 9Flip decompile (p6/t6): own manual value clamp 0.05–1.0,
        // applied as panel window screenBrightness, or -1 (system)
        // when manual is off (slider alpha 0.42 when off, 1.0 when on).
        // The old system-brightness slider never moved the cover
        // because Samsung's cover brightness is not Settings.System.
        val sliderCard = card()
        sliderCard.addView(title("Độ sáng panel màn phụ"))
        val brightLabel = TextView(ctx).apply { textSize = 12f; setTextColor(0xFFFFFFFF.toInt()) }
        fun refreshBrightLabel() {
            val st = store.load()
            brightLabel.text = if (st.panelBrightnessManual) {
                "Độ sáng panel: ${(st.panelBrightness * 100).toInt()}% (thủ công — chỉ panel này, không phải độ sáng hệ thống)"
            } else {
                "Độ sáng panel: Theo hệ thống — bấm nút dưới để chỉnh thủ công cho panel"
            }
        }
        refreshBrightLabel()
        sliderCard.addView(brightLabel)
        var brightBar: SeekBar? = null
        fun refreshBrightBar() {
            val st = store.load()
            brightBar?.isEnabled = st.panelBrightnessManual
            brightBar?.alpha = if (st.panelBrightnessManual) 1.0f else 0.42f
            brightBar?.progress = (st.panelBrightness * 255).toInt().coerceIn(13, 255)
        }
        sliderCard.addView(modernButton("Chuyển sang Thủ công / Về Theo hệ thống").apply {
            setOnClickListener {
                val cur = store.load()
                val nextManual = !cur.panelBrightnessManual
                store.save(cur.copy(panelBrightnessManual = nextManual))
                applyPanelBrightness(ctx.applicationContext)
                refreshBrightLabel(); refreshBrightBar(); resetTimer()
            }
        })
        brightBar = mintSeekBar().apply {
            max = 255
            progress = (store.load().panelBrightness * 255).toInt().coerceIn(13, 255)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val cur = store.load()
                    if (!cur.panelBrightnessManual) return
                    val v = (p / 255f).coerceIn(0.05f, 1.0f)
                    store.save(cur.copy(panelBrightness = v))
                    applyPanelBrightness(ctx.applicationContext)
                    brightLabel.text = "Độ sáng panel: ${(v * 100).toInt()}% (thủ công)"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { resetTimer() }
            })
        }
        sliderCard.addView(brightBar)
        refreshBrightBar()

        // 3.2.0 volume: CFlip's own bar, flag 0 (no Samsung popup).
        // Mute restores to saved volume, else ~45% of max (9Flip formula).
        sliderCard.addView(title("Âm lượng"))
        val volLabel = TextView(ctx).apply { textSize = 12f; setTextColor(0xFFFFFFFF.toInt()) }
        fun refreshVolLabel() {
            val (v, m) = QuickControls.volume(ctx)
            volLabel.text = "Âm lượng nhạc: $v / $m" + if (v == 0) " (đang tắt tiếng)" else ""
        }
        refreshVolLabel()
        sliderCard.addView(volLabel)
        sliderCard.addView(mintSeekBar().apply {
            val (vol, volMax) = QuickControls.volume(ctx)
            max = volMax.coerceAtLeast(1)
            progress = vol.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) { QuickControls.setVolume(ctx, p); refreshVolLabel() }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { resetTimer() }
            })
        })
        sliderCard.addView(modernButton("Tắt tiếng / Khôi phục").apply {
            setOnClickListener {
                val (v, m) = QuickControls.volume(ctx)
                val prefs = ctx.getSharedPreferences("cflip_volume", Context.MODE_PRIVATE)
                if (v > 0) {
                    prefs.edit().putInt("saved_volume", v).apply()
                    QuickControls.setVolume(ctx, 0)
                } else {
                    val saved = prefs.getInt("saved_volume", -1)
                    val restore = if (saved > 0) saved else ((m * 0.45f).toInt() + 0).coerceIn(1, m)
                    QuickControls.setVolume(ctx, restore)
                }
                refreshVolLabel(); resetTimer()
            }
        })

        controlContent.addView(sliderCard)
        val tileCard = card()
        tileCard.addView(title("Đảo chức năng"))
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
                setTextColor(if (active) 0xFF7FE3C0.toInt() else 0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            }
            val circle = TextView(ctx).apply {
                text = icon; textSize = 18f; gravity = Gravity.CENTER
                setTextColor(if (active) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (active) 0xFF7FE3C0.toInt() else 0xFF23262B.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }
            val cardView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                gravity = Gravity.CENTER
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
                GridLayout.LayoutParams().apply { width = dp(64); height = dp(76) })
        }
        fun tileRowActive(label: String, icon: String, active: Boolean, onClick: () -> Unit) {
            grid.addView(tileWithLabel(icon, label, active, onClick).first,
                GridLayout.LayoutParams().apply { width = dp(64); height = dp(76) })
        }
        // Int overloads (vc30 local): same QS circle, but an ImageView
        // drawable instead of a Unicode TextView symbol.
        fun tileWithLabel(iconRes: Int, label: String, active: Boolean = false, onClick: () -> Unit): Pair<LinearLayout, TextView> {
            val labelView = TextView(ctx).apply {
                text = label; textSize = 10f
                setTextColor(if (active) 0xFF7FE3C0.toInt() else 0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
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
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            }
            val cardView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                gravity = Gravity.CENTER
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
                GridLayout.LayoutParams().apply { width = dp(64); height = dp(76) })
        }
        fun tileRowActive(label: String, iconRes: Int, active: Boolean, onClick: () -> Unit) {
            grid.addView(tileWithLabel(iconRes, label, active, onClick).first,
                GridLayout.LayoutParams().apply { width = dp(64); height = dp(76) })
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
                1 -> R.drawable.ic_sec_lock
                else -> R.drawable.ic_sec_rotate
            }
            tileRowActive(rotateLabel, rotateIcon, currentMode != 2) {
                val next = when (currentMode) { 0 -> 1; 1 -> 2; else -> 0 }
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
                hide()
                if (SettingsStore(ctx.applicationContext).load().assistiveTouchEnabled) {
                    CoverAssistiveTouchManager.hide()
                    CoverAssistiveTouchManager.show(ctx.applicationContext, display)
                }
            }
        }
        tileRow("Đèn", R.drawable.ic_sec_flashlight) { QuickControls.setTorch(ctx, !QuickControls.torchOn) }
        tileRow("Wi-Fi", R.drawable.ic_sec_wifi) { runCatching { QuickControls.openWifiPanel(ctx) } }
        tileRow("Bluetooth", R.drawable.ic_sec_bluetooth) { runCatching { QuickControls.openBluetoothSettings(ctx) } }
        tileRow("Internet", R.drawable.ic_sec_mobiledata) { runCatching { QuickControls.openInternetSettings(ctx.applicationContext) } }
        tileRow("NFC", R.drawable.ic_sec_nfc) { runCatching { QuickControls.openNfcPanel(ctx) } }
        tileRow("Khóa", R.drawable.ic_sec_lock) { a11yAction("Khóa máy") { it.lockScreen() } }
        tileRow("Gần đây", R.drawable.ic_sec_recent) { a11yAction("Gần đây") { it.openRecents() } }
        tileRow("TB", R.drawable.ic_sec_notification) { a11yAction("Thông báo") { it.openNotifications() } }
        tileRow("CĐ nhanh", R.drawable.ic_sec_settings) { a11yAction("Cài đặt nhanh") { it.openQuickSettings() } }
        tileRow("Cài đặt", R.drawable.ic_sec_settings) { runCatching { QuickControls.openDeviceSettings(ctx) } }
        var sortLabel: TextView? = null
        val sortTile = tileWithLabel(R.drawable.ic_cflip_sort, "SX ${when (store.load().sortMode) { 1 -> "Mở"; 2 -> "Cài"; else -> "A-Z" }}") {
            val cur = store.load()
            val next = (cur.sortMode + 1) % 3
            store.save(cur.copy(sortMode = next))
            sortLabel?.text = "SX ${when (next) { 1 -> "Mở"; 2 -> "Cài"; else -> "A-Z" }}"
        }
        sortLabel = sortTile.second
        grid.addView(sortTile.first, GridLayout.LayoutParams().apply { width = dp(64); height = dp(76) })
        var colLabel: TextView? = null
        val colTile = tileWithLabel(R.drawable.ic_cflip_columns, "Cột ${store.load().columns}") {
            val cur = store.load()
            val next = when (cur.columns) { 0 -> 3; 3 -> 4; 4 -> 5; 5 -> 6; else -> 0 }
            store.save(cur.copy(columns = next))
            colLabel?.text = "Cột $next"
        }
        colLabel = colTile.second
        grid.addView(colTile.first, GridLayout.LayoutParams().apply { width = dp(64); height = dp(76) })
        tileRow(if (store.load().showLabels) "Tên: Bật" else "Tên: Tắt", "Aa") {
            val cur = store.load(); store.save(cur.copy(showLabels = !cur.showLabels))
        }
        tileRow("Xóa ghim", R.drawable.ic_sec_close) {
            ctx.getSharedPreferences("favorites", Context.MODE_PRIVATE).edit().clear().apply()
        }
        tileRow("Mirror", R.drawable.ic_sec_cast) {
            if (com.cuong.cflip.mirror.MirrorState.active) {
                ctx.startService(Intent(ctx, com.cuong.cflip.service.ScreenMirrorService::class.java)
                    .setAction(com.cuong.cflip.service.ScreenMirrorService.ACTION_STOP))
            } else {
                // vc32: previously this tile only stopped or toasted.
                // Now it starts the permission flow (like 9Flip's
                // MirrorPermissionActivity): the system capture dialog
                // appears on the main display, so unfold to approve.
                hide()
                android.widget.Toast.makeText(
                    ctx, "Mở máy ra để đồng ý phản chiếu màn hình",
                    android.widget.Toast.LENGTH_LONG).show()
                ctx.applicationContext.startActivity(
                    Intent(ctx.applicationContext, com.cuong.cflip.mirror.MirrorPermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        tileCard.addView(grid)
        controlContent.addView(tileCard)
        // 3.6.0 order: Media first, Brightness second, Function island
        // third, Widget last. Widget was built first but is added here.
        widgetCardDeferred?.let { controlContent.addView(it) }

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

        // ---- Tab 3: Ứng dụng ----
        appsContent.addView(title("Ứng dụng"))
        run {
            val appRepo = com.cuong.cflip.data.AppRepository(ctx)
            val allApps = appRepo.loadLaunchableApps()
            val settings = store.load()
            val recentOrder = com.cuong.cflip.data.RecentAppsRepository(ctx).recentPackages(100)
            val installTimes = appRepo.installTimes(allApps.map { it.packageName })
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
                }, GridLayout.LayoutParams().apply { width = dp(76); height = dp(84) })
            }
            appsContent.addView(appGrid)
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

        return outer
    }
}
