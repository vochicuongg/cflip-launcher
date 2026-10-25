package com.cuong.cflip.cover

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
    private val handler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null
    // Replacement-interface state (2.10.0): when the panel opens, it
    // REPLACES the cover interface, it does not stack on top of it.
    // The cover overlay is hidden first and restored when the panel
    // closes (close button, auto-dismiss, app launch, …).
    private var restoreDisplay: Display? = null
    private var restoreAppContext: Context? = null

    fun isShown(): Boolean = view != null

    fun show(appContext: Context, display: Display): Result<Unit> = runCatching {
        if (display.displayId == Display.DEFAULT_DISPLAY) {
            throw IllegalArgumentException("Control Center is cover-only, refusing default display")
        }
        hidePanelOnly()
        // Replace, don't stack: hide the cover overlay first.
        if (CoverOverlayManager.isShown()) {
            restoreDisplay = display; restoreAppContext = appContext
            CoverOverlayManager.hide()
        }
        val displayContext = appContext.createDisplayContext(display)
        val manager = displayContext.getSystemService(WindowManager::class.java)
            ?: throw IllegalStateException("No WindowManager for display ${display.displayId}")
        val root = buildPanel(displayContext, display)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        manager.addView(root, params)
        wm = manager; view = root
        scheduleDismiss()
        Log.i(TAG, "Control Center shown on display ${display.displayId}")
        Unit
    }.onFailure { Log.w(TAG, "Control Center addView failed", it) }

    fun hide() {
        hidePanelOnly()
        // Restore the cover interface that this panel replaced.
        val d = restoreDisplay; val ac = restoreAppContext
        restoreDisplay = null; restoreAppContext = null
        if (d != null && ac != null) {
            CoverOverlayManager.show(ac, d)
        }
    }

    private fun hidePanelOnly() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        val v = view
        if (v != null) {
            runCatching { wm?.removeView(v) }
            view = null; wm = null
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
    private fun buildPanel(ctx: Context, display: Display): View {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val store = SettingsStore(ctx)
        fun a11yAction(name: String, action: (MyAccessibilityService) -> Boolean) {
            val svc = MyAccessibilityService.instance
            if (svc == null) {
                android.widget.Toast.makeText(
                    ctx, "$name không chạy: cần bật Accessibility cho CFlip",
                    android.widget.Toast.LENGTH_SHORT).show()
            } else {
                action(svc)
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
        // Fallback rotation, in sync with the overlay (2.10.0).
        if (CoverRotateState.contentFallback) {
            val rotation = store.load().coverRotation
            if (rotation != 0) {
                outer.post {
                    outer.rotation = rotation.toFloat()
                    if (rotation % 180 != 0 && outer.width > 0 && outer.height > 0) {
                        val sc = minOf(outer.width, outer.height).toFloat() /
                            maxOf(outer.width, outer.height).toFloat()
                        outer.scaleX = sc; outer.scaleY = sc
                    }
                }
            }
        }
        selectTab(0)

        // ---- Tab 1: Điều khiển ----
        val mediaRepo = MediaControllerRepository(ctx)
        runCatching { mediaRepo.refresh() }
        val mediaInfo = MediaState.current.value
        val mediaCard = card()
        mediaCard.addView(title("Nhạc"))
        mediaCard.addView(TextView(ctx).apply {
            text = mediaInfo?.title ?: "Chưa có nhạc"; textSize = 13f; setTextColor(0xFFFFFFFF.toInt())
        })
        val mediaRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        mediaRow.addView(modernButton("⏮").apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { resetTimer(); mediaRepo.previous() } })
        mediaRow.addView(modernButton("⏯", true).apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { resetTimer(); mediaRepo.playPause() } })
        mediaRow.addView(modernButton("⏭").apply { layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f); setOnClickListener { resetTimer(); mediaRepo.next() } })
        mediaCard.addView(mediaRow)
        controlContent.addView(mediaCard)

        val sliderCard = card()
        sliderCard.addView(title("Độ sáng"))
        val brightLabel = TextView(ctx).apply { textSize = 12f; setTextColor(0xFFFFFFFF.toInt()) }
        fun refreshBrightLabel() {
            val pct = QuickControls.brightness(ctx) * 100 / 255
            brightLabel.text = if (QuickControls.canWriteSettings(ctx)) {
                "Độ sáng: $pct% (kéo rồi thả để áp dụng)"
            } else {
                "Độ sáng: chưa cấp quyền Sửa cài đặt — bấm vào đây để cấp"
            }
        }
        refreshBrightLabel()
        brightLabel.setOnClickListener {
            if (!QuickControls.canWriteSettings(ctx)) {
                QuickControls.openWriteSettingsPermission(ctx.applicationContext)
            }
        }
        sliderCard.addView(brightLabel)
        sliderCard.addView(mintSeekBar().apply {
            max = 255
            progress = QuickControls.brightness(ctx).coerceIn(13, 255)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = p.coerceIn(13, 255)
                    if (QuickControls.canWriteSettings(ctx)) {
                        QuickControls.setBrightness(ctx, v)
                        brightLabel.text = "Độ sáng: ${v * 100 / 255}%"
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val v = (s?.progress ?: 125).coerceIn(13, 255)
                    if (!QuickControls.setBrightness(ctx, v)) {
                        android.widget.Toast.makeText(
                            ctx, "Chưa cấp quyền Sửa cài đặt nên chưa đổi được độ sáng — bấm vào dòng độ sáng để cấp quyền trên màn hình chính",
                            android.widget.Toast.LENGTH_SHORT).show()
                    }
                    refreshBrightLabel(); resetTimer()
                }
            })
        })

        sliderCard.addView(title("Âm lượng"))
        val (vol, volMax) = QuickControls.volume(ctx)
        sliderCard.addView(mintSeekBar().apply {
            max = volMax.coerceAtLeast(1)
            progress = vol.coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) QuickControls.setVolume(ctx, p)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    QuickControls.setVolume(ctx, s?.progress ?: 0); resetTimer()
                }
            })
        })

        controlContent.addView(sliderCard)
        val tileCard = card()
        tileCard.addView(title("Chức năng"))
        val grid = GridLayout(ctx).apply { columnCount = 5 }
        // Modern tiles 2.9.0: rounded mini-cards with an icon on top and
        // a label below, instead of the default grey Android buttons.
        fun tileWithLabel(icon: String, label: String, onClick: () -> Unit): Pair<LinearLayout, TextView> {
            val labelView = TextView(ctx).apply {
                text = label; textSize = 10f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER
            }
            val cardView = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(0xFF23262B.toInt(), 12)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                gravity = Gravity.CENTER
                setOnClickListener { resetTimer(); onClick() }
            }
            cardView.addView(TextView(ctx).apply {
                text = icon; textSize = 20f; gravity = Gravity.CENTER
            })
            cardView.addView(labelView)
            return cardView to labelView
        }
        fun tile(label: String, icon: String, onClick: () -> Unit): LinearLayout =
            tileWithLabel(icon, label, onClick).first
        fun tileRow(label: String, icon: String, onClick: () -> Unit) {
            grid.addView(tile(label, icon, onClick),
                GridLayout.LayoutParams().apply { width = dp(64); height = dp(72) })
        }
        tileRow("Đèn", "💡") { QuickControls.setTorch(ctx, !QuickControls.torchOn) }
        tileRow("Wi-Fi", "📶") { runCatching { QuickControls.openWifiPanel(ctx) } }
        tileRow("Bluetooth", "🔷") { runCatching { QuickControls.openBluetoothSettings(ctx) } }
        tileRow("Internet", "🌐") { runCatching { QuickControls.openInternetPanel(ctx) } }
        tileRow("NFC", "📲") { runCatching { QuickControls.openNfcPanel(ctx) } }
        tileRow("Khóa", "🔒") { a11yAction("Khóa máy") { it.lockScreen() } }
        tileRow("Gần đây", "▦") { a11yAction("Gần đây") { it.openRecents() } }
        tileRow("TB", "🔔") { a11yAction("Thông báo") { it.openNotifications() } }
        tileRow("CĐ nhanh", "⚡") { a11yAction("Cài đặt nhanh") { it.openQuickSettings() } }
        tileRow("Cài đặt", "⚙️") { runCatching { QuickControls.openDeviceSettings(ctx) } }
        // Rotate, 2.10.0: whole-display rotation via CoverRotateHostManager
        // (1x1 screenOrientation overlay) FIRST, then verified: if the
        // display's rotation has not moved after 600ms, Samsung ignored
        // the host (the 2.9.0 failure), so CFlip falls back to rotating
        // all of its own windows in sync and says so. Either way the
        // panel closes and the cover interface returns, now rotated.
        var rotateLabel: TextView? = null
        val rotateTile = tileWithLabel("↻", "Xoay ${store.load().coverRotation}°") {
            val cur = store.load()
            val next = when (cur.coverRotation) { 0 -> 90; 90 -> 180; 180 -> 270; else -> 0 }
            store.save(cur.copy(coverRotation = next))
            rotateLabel?.text = "Xoay ${next}°"
            val rotationBefore = display.rotation
            CoverRotateHostManager.apply(ctx.applicationContext, display)
            hide()
            Handler(Looper.getMainLooper()).postDelayed({
                if (display.rotation == rotationBefore) {
                    // Host ignored: fall back to synced content rotation.
                    CoverRotateState.contentFallback = true
                    android.widget.Toast.makeText(
                        ctx.applicationContext,
                        "Samsung không nhận ép hướng hệ thống, CFlip đã xoay giao diện của mình sang ${next}° thay thế",
                        android.widget.Toast.LENGTH_LONG).show()
                    CoverOverlayManager.show(ctx.applicationContext, display)
                } else {
                    CoverRotateState.contentFallback = false
                    android.widget.Toast.makeText(
                        ctx.applicationContext,
                        "Đã xoay toàn màn phụ sang ${next}°",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
                if (SettingsStore(ctx.applicationContext).load().assistiveTouchEnabled) {
                    CoverAssistiveTouchManager.hide()
                    CoverAssistiveTouchManager.show(ctx.applicationContext, display)
                }
            }, 600)
        }
        rotateLabel = rotateTile.second
        grid.addView(rotateTile.first, GridLayout.LayoutParams().apply { width = dp(64); height = dp(72) })
        var sortLabel: TextView? = null
        val sortTile = tileWithLabel("⇅", "SX ${when (store.load().sortMode) { 1 -> "Mở"; 2 -> "Cài"; else -> "A-Z" }}") {
            val cur = store.load()
            val next = (cur.sortMode + 1) % 3
            store.save(cur.copy(sortMode = next))
            sortLabel?.text = "SX ${when (next) { 1 -> "Mở"; 2 -> "Cài"; else -> "A-Z" }}"
        }
        sortLabel = sortTile.second
        grid.addView(sortTile.first, GridLayout.LayoutParams().apply { width = dp(64); height = dp(72) })
        var colLabel: TextView? = null
        val colTile = tileWithLabel("▤", "Cột ${store.load().columns}") {
            val cur = store.load()
            val next = when (cur.columns) { 0 -> 3; 3 -> 4; 4 -> 5; 5 -> 6; else -> 0 }
            store.save(cur.copy(columns = next))
            colLabel?.text = "Cột $next"
        }
        colLabel = colTile.second
        grid.addView(colTile.first, GridLayout.LayoutParams().apply { width = dp(64); height = dp(72) })
        tileRow(if (store.load().showLabels) "Tên: Bật" else "Tên: Tắt", "Aa") {
            val cur = store.load(); store.save(cur.copy(showLabels = !cur.showLabels))
        }
        tileRow("Xóa ghim", "✕") {
            ctx.getSharedPreferences("favorites", Context.MODE_PRIVATE).edit().clear().apply()
        }
        tileRow("Mirror", "🪞") {
            if (com.cuong.cflip.mirror.MirrorState.active) {
                ctx.startService(Intent(ctx, com.cuong.cflip.service.ScreenMirrorService::class.java)
                    .setAction(com.cuong.cflip.service.ScreenMirrorService.ACTION_STOP))
            } else {
                android.widget.Toast.makeText(
                    ctx, "Mở Mirror từ tab Điều khiển (cần xin quyền ghi màn hình)",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        tileCard.addView(grid)
        controlContent.addView(tileCard)

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
