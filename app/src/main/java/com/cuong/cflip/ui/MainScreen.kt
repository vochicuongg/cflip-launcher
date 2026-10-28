package com.cuong.cflip.ui

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.cuong.cflip.data.SettingsStore
import com.cuong.cflip.display.DisplayRepository
import com.cuong.cflip.service.CoverForegroundService
import com.cuong.cflip.service.MyAccessibilityService
import com.cuong.cflip.service.MyNotificationListenerService
import com.cuong.cflip.service.NotificationController

/**
 * CFlip 2.3 main screen, redesigned after Cường called the 2.2 hub ugly.
 *
 * What was wrong with 2.2: every section shouted at once - five 20sp
 * headings, full-width buttons stacked on each other, long explanations,
 * Mirror/Bubble controls embedded in the middle, and raw display text.
 * The redesign follows one rule: a quiet grid, then compact lists.
 * Nothing here is decorative text.
 *
 * - vc125 (user, 29/10): the hero miniature-cover preview + the mint
 *   "Mở Cover" primary action were REMOVED (display only). The cover
 *   still opens via AssistiveTouch / Swipe indicator and via
 *   MainActivity's cover intent. Service control is a single toggle
 *   in its own card, not three stacked buttons.
 * - Status: three small stat cards, refreshed automatically on every
 *   action (vc133: the manual refresh button is gone).
 * - Mirror & Bubble are NOT on this screen any more - they live in the
 *   cover Quick tab where they are actually used. Removing them is
 *   what unclutters the hub; no feature was deleted.
 * - Every card still does something real: feature cards open the cover
 *   at that tab, permission rows open the exact system page, and the
 *   sort/toggle rows write to the same SettingsStore the cover reads.
 */
@Composable
fun MainScreen(
    initialTab: CoverTab = CoverTab.HOME,
    showCoverInitially: Boolean = false,
) {
    val context = LocalContext.current
    val displayRepo = remember { DisplayRepository(context) }
    val settingsStore = remember { SettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var showCover by remember { mutableStateOf(showCoverInitially) }
    var coverTab by remember { mutableStateOf(initialTab) }

    if (showCover) {
        BackHandler { showCover = false }
        Column(modifier = Modifier.fillMaxSize().background(CFlipColors.Bg).statusBarsPadding()) {
            TextButton(onClick = { showCover = false }) { Text("← Quay lại", color = CFlipColors.Accent) }
            CoverApp(modifier = Modifier.weight(1f),
                targetDisplayId = displayRepo.secondaryDisplay()?.displayId,
                initialTab = coverTab)
        }
        return
    }

    var statusVersion by remember { mutableStateOf(0) }
    // vc115 PERMISSIONS LIVE UPDATE (user, 27/10):
    // permission rows used to refresh only when
    // the user tapped "Lam moi trang thai" - after
    // granting a permission in system settings and
    // returning to CFlip, the rows still showed the
    // old state. Now ON_RESUME reloads settings
    // and bumps statusVersion, so every return
    // from system settings refreshes the rows
    // immediately, with no manual refresh tap.
    // (DisposableEffect is already imported.)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                settings = settingsStore.load()
                statusVersion++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val statuses = remember(statusVersion) {
        MainStatuses(
            notifications = NotificationController.isConnected(),
            accessibility = MyAccessibilityService.instance != null,
            overlay = Settings.canDrawOverlays(context),
            serviceRunning = isServiceRunning(context, CoverForegroundService::class.java),
            secondarySeen = displayRepo.secondaryDisplay() != null,
        )
    }
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "2.3"
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // vc126 PERMISSION DEEP LINKS (9Flip pattern, clean-room):
    // for each special permission, try the most specific settings
    // page first and fall back to the generic page; if no candidate
    // resolves on this OEM build, toast instead of crashing.
    // (Special permissions cannot be batch-granted by Android - each
    // still needs its own settings page; the win is fewer taps to
    // the right page, not fewer pages.)
    fun openFirstSettingsPage(vararg intents: Intent): Boolean {
        for (intent in intents) {
            try { context.startActivity(intent); return true }
            catch (_: ActivityNotFoundException) { /* try next candidate */ }
        }
        return false
    }
    fun openNotificationSettings() {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, MyNotificationListenerService::class.java).flattenToString())
        val list = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        if (!openFirstSettingsPage(detail, list)) toast("Không mở được trang cài đặt thông báo trên máy này.")
    }
    fun openOverlaySettings() {
        val withPkg = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + context.packageName))
        val generic = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        if (!openFirstSettingsPage(withPkg, generic)) toast("Không mở được trang hiển thị trên app khác trên máy này.")
    }
    fun openAccessibilitySettings() {
        if (!openFirstSettingsPage(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
            toast("Không mở được trang Accessibility trên máy này.")
    }

    // vc135 ANIMATED BACKGROUND (user, 29/10): the two ambient blobs
    // gently morph square -> circle (corner percent 0 -> 50) and drift
    // a few dp, each on its own slow cycle so they never move in sync.
    // They stay outside the scroll, so the animation costs one small
    // layer, not a per-frame blur while scrolling.
    val bgTransition = rememberInfiniteTransition(label = "cflip-bg")
    val morph1 by bgTransition.animateFloat(0f, 50f, infiniteRepeatable(tween(5000), RepeatMode.Reverse), label = "morph1")
    val driftX1 by bgTransition.animateFloat(0f, 24f, infiniteRepeatable(tween(7000), RepeatMode.Reverse), label = "driftX1")
    val driftY1 by bgTransition.animateFloat(0f, 16f, infiniteRepeatable(tween(9000), RepeatMode.Reverse), label = "driftY1")
    val morph2 by bgTransition.animateFloat(0f, 50f, infiniteRepeatable(tween(6500), RepeatMode.Reverse), label = "morph2")
    val driftX2 by bgTransition.animateFloat(0f, -20f, infiniteRepeatable(tween(8000), RepeatMode.Reverse), label = "driftX2")
    val driftY2 by bgTransition.animateFloat(0f, 12f, infiniteRepeatable(tween(11000), RepeatMode.Reverse), label = "driftY2")
    Box(modifier = Modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(CFlipColors.Bg, Color(0xFF0B1512))))) {
        Box(modifier = Modifier.size(280.dp).offset(x = (180f + driftX1).dp, y = (-60f + driftY1).dp)
            .blur(80.dp).background(CFlipColors.Accent.copy(alpha = 0.13f), RoundedCornerShape(morph1.toInt())))
        Box(modifier = Modifier.size(220.dp).offset(x = (-80f + driftX2).dp, y = (420f + driftY2).dp)
            .blur(90.dp).background(CFlipColors.AccentDeep.copy(alpha = 0.10f), RoundedCornerShape(morph2.toInt())))
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Top bar: wordmark left, version pill right. No tagline paragraph.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                    Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                        .background(CFlipColors.AccentBrush), contentAlignment = Alignment.Center) {
                        Text("C", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CFlipColors.AccentDark)
                    }
                    Text("CFlip", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = CFlipColors.TextPrimary)
                }
                Box(modifier = Modifier.clip(CircleShape).background(CFlipColors.Surface)
                    .border(1.dp, CFlipColors.CardStroke, CircleShape).padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text(versionName, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = CFlipColors.TextSecondary)
                }
            }

            // vc126 WARNING FIRST (user, 29/10):
            // the GoodLock warning moves to the very top
            // of the app (above Permissions) and gets a
            // danger-tinted card so it cannot be missed.
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(CFlipColors.Danger.copy(alpha = 0.12f))
                .border(1.dp, CFlipColors.Danger.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                .padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("\u26a0", fontSize = 18.sp, color = CFlipColors.Danger)
                        Text("CẢNH BÁO", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = CFlipColors.Danger, letterSpacing = 1.2.sp)
                    }
                    Text("Không thêm CFlip vào GoodLock.",
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CFlipColors.TextPrimary)
                    Text("CFlip chạy độc lập trên màn hình ngoài, không cần GoodLock hay MultiStar. Thêm vào GoodLock làm chức năng CFlip không hoạt động đúng yêu cầu.",
                        fontSize = 12.sp, color = CFlipColors.TextSecondary)
                    Text("Mở panel bằng AssistiveTouch hoặc Thanh Vuốt trong mục Quick Panel, không qua GoodLock.",
                        fontSize = 12.sp, color = CFlipColors.TextSecondary)
                }
            }

            // vc126 PERMISSIONS (9Flip pattern, clean-room):
            // deep-link page first, generic page as fallback, toast if
            // none resolves; the "Cấp quyền còn thiếu" button jumps to
            // the first missing permission in row order, and the vc115
            // ON_RESUME refresh makes each return land on the next one.
            // (Warning now sits above this island, vc126.)
            // Permissions: compact rows, chevron only, plus that button.
            // vc127 REQUIRED SET (9Flip evidence, user, 29/10): 9Flip declares
            // no Usage Stats and no Write Settings - its recents come from its
            // own launch history (like CFlip's LaunchHistoryStore fallback) and
            // it never writes system brightness. So CFlip's required set is now
            // 3 (Thông báo, Accessibility, Overlay); Usage moved out (its
            // on-demand button already lives in the STYLE tab QUYỀN section).
            // vc128 CORRECTION: Write Settings is NOT dead - the cover QUICK
            // tab has its own system-brightness slider (QuickControlsScreen)
            // that writes Settings.System and already grants on demand
            // (slider toast + grant button). Panel brightness is window-local
            // since 3.2.0, but the QUICK-tab slider still needs the write
            // helpers, so they are restored; Write stays OUT of the required
            // set (3) and is granted on demand only.
            SectionLabel("Quyền")
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(CFlipColors.Surface).border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column {
                    // perm-btn-anim
                    AnimatedVisibility(
                        visible = statuses.requiredCount < 3,
                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(200)),
                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(200)),
                    ) {
                        Column {
                            Box(modifier = Modifier.fillMaxWidth().clip(CircleShape).background(CFlipColors.Accent)
                                .clickable {
                                    when {
                                        !statuses.notifications -> openNotificationSettings()
                                        !statuses.accessibility -> openAccessibilitySettings()
                                        !statuses.overlay -> openOverlaySettings()
                                    }
                                }
                                .padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Text("Cấp quyền còn thiếu (${statuses.requiredCount}/3) →",
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = CFlipColors.AccentDark)
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    PermissionRow("Thông báo", statuses.notifications) {
                        openNotificationSettings()
                    }
                    PermissionRow("Accessibility", statuses.accessibility) {
                        openAccessibilitySettings()
                    }
                    PermissionRow("Hiển thị trên app khác", statuses.overlay) {
                        openOverlaySettings()
                    }
                }
            }



            // vc134 STATS (user, 29/10: the 3 separate cards had uneven heights
            // - "Màn hình ngoài" wrapped to 2 lines): one card, 3 columns with
            // dividers, fixed 2-line label slot so values always align.
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF1A2220), CFlipColors.Surface)))
                .border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
                .padding(vertical = 14.dp, horizontal = 8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StatColumn("Quyền", "${statuses.requiredCount}/3", statuses.requiredCount == 3, Modifier.weight(1f))
                    Box(modifier = Modifier.width(1.dp).height(40.dp).background(CFlipColors.CardStroke))
                    StatColumn("Dịch vụ", if (statuses.serviceRunning) "Đang chạy" else "Tắt", statuses.serviceRunning, Modifier.weight(1f))
                    Box(modifier = Modifier.width(1.dp).height(40.dp).background(CFlipColors.CardStroke))
                    StatColumn("Màn hình ngoài", if (statuses.secondarySeen) "Đã thấy" else "Chưa thấy", statuses.secondarySeen, Modifier.weight(1f))
                }
            }

            // Widgets, 3.1.0 — USER-ADDED ONLY, corrected after the user's
            // complaint. Decompile truth (9Flip v149, static only):
            // NineCoverAppGridWidgetProvider + RemoteViewsService and the
            // Cassette widget family are standard Android AppWidgetProviders.
            // No AppWidgetHost code and no forced overlay widget were found
            // in the native scope — the user places widgets, the app does not
            // force them. CFlip now follows that exactly:
            //  - Nothing shows on the cover until the user turns a widget on
            //    below (stored in SettingsStore.coverWidgets, default empty).
            //  - 3.4.0: NO requestPinAppWidget() anymore. Pin is the main-home
            //    launcher's dialog ("Mở điện thoại để tiếp tục") and can never
            //    place a widget on the cover. 9Flip never pins either: it opens
            //    Samsung's cover widget settings, and its widgets are keyguard
            //    (not home_screen) + Samsung sub_screen, so Samsung's cover
            //    picker on the folded phone lists them. CFlip now does the same.
            //  - The cover widgets below live inside CFlip's own AssistiveTouch
            //    panel, and only after the user adds them there.
            SectionLabel("Widget")
            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
                .background(CFlipColors.Surface).border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
                .padding(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    run {
                        val chosen = settings.coverWidgets.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                        // vc135: the Clock & Battery switch is gone - drop the key from
                        // saved widgets so a previously-enabled clock widget does not
                        // stay on with no switch to turn it off.
                        if ("clock" in chosen) {
                            val nextWidgets = (chosen - "clock").sorted().joinToString(",")
                            settings = settings.copy(coverWidgets = nextWidgets); settingsStore.save(settings)
                        }
                        fun toggleWidget(key: String, on: Boolean) {
                            val next = if (on) chosen + key else chosen - key
                            settings = settings.copy(coverWidgets = next.sorted().joinToString(","))
                            settingsStore.save(settings)
                        }
                        HubSwitch("Kích hoạt Widget màn hình ngoài", "appgrid" in chosen) { toggleWidget("appgrid", it) }
                    }
                    Text("Chưa bật gì thì panel không tự hiện Widget.",
                        fontSize = 12.sp, color = CFlipColors.TextFaint)
                    Text("Thêm Widget Samsung trên màn hình ngoài: gập máy, vuốt Widget tới cuối, bấm + và chọn CFlip.",
                        fontSize = 12.sp, color = CFlipColors.TextSecondary)
                    Text("Hoặc mở cài đặt Widget Samsung bên dưới (chỉ mở được trên màn chính).",
                        fontSize = 12.sp, color = CFlipColors.TextSecondary)
                    TextButton(onClick = {
                        com.cuong.cflip.widget.SamsungCoverWidgetHelper.openCoverWidgetSettings(context)
                    }, modifier = Modifier.align(Alignment.Start)) {
                        Text("Mở Cài đặt Widget màn hình ngoài Samsung", color = CFlipColors.Accent, fontSize = 13.sp)
                    }
                    TextButton(onClick = {
                        toast(com.cuong.cflip.widget.SamsungCoverWidgetHelper.statusText(context))
                    }, modifier = Modifier.align(Alignment.Start)) {
                        Text("Kiểm tra Widget màn hình ngoài đã thêm chưa", color = CFlipColors.Accent, fontSize = 13.sp)
                    }
                }
            }

            // vc121 ISLANDS (user, 28/10: group the
            // main settings into separate feature
            // islands, neat like 9Flip). The old
            // "Cai dat cover" box crammed unrelated
            // features - display, AssistiveTouch,
            // Quick Panel, touch actions - into one
            // card. Each group is now its own island
            // (9Flip pattern: grouped sections, one
            // feature per rounded card, same card
            // style as the existing Widget /
            // Permissions / Service islands). Nothing
            // is added or removed - same rows, same
            // order, same save logic, only grouped.
            // One move: the AssistiveTouch position
            // reset button moves from the touch-action
            // rows into the AssistiveTouch island,
            // where it belongs. Island labels sit
            // 8dp above their card (before, a label
            // sat 24dp above its card, the outer
            // section spacing, which read as detached).
            // vc127: AssistiveTouch + Quick Panel + Touch actions merged into one Quick Panel island below.
            // vc127 QUICK PANEL MERGE (user, 29/10):
            // the old AssistiveTouch + Quick Panel +
            // Touch actions islands are one island now:
            // Quick Panel is the feature (bubble or bar);
            // its open mode and touch actions appear
            // inside it once it is enabled. Bubble-only
            // rows (size, auto-dim, position reset)
            // appear only in AssistiveTouch mode.
            Island("Quick Panel") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // 25/10/2026: this toggle is cover-only. The main display
                    // itself never shows a bubble — it is custom/settings only.
                    // (Label renamed vc127: this switch is the common on/off
                    // for both openers, not just the bubble.)
                    HubSwitch("Quick Panel trên màn hình ngoài", settings.assistiveTouchEnabled) {
                        settings = settings.copy(assistiveTouchEnabled = it); settingsStore.save(settings)
                        if (it) {
                            // vc132 POKE (user, 29/10: enable showed neither opener):
                            // the vc126 guard only started the service when it was
                            // NOT running, so re-enabling while the (sticky)
                            // service was already alive never re-ran tryShow -
                            // and on a static emulator cover there is no later
                            // display/screen event to bring the opener back.
                            // startForegroundService on an already-running service
                            // is harmless: it just delivers onStartCommand, which
                            // re-runs tryShow with fresh settings. So always poke
                            // on enable, never guard.
                            ContextCompat.startForegroundService(context,
                                Intent(context, CoverForegroundService::class.java))
                            statusVersion++
                        } else {
                            // vc132: OFF hides BOTH openers, not just the bubble -
                            // the old code left the swipe bar visible in bar mode.
                            com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
                            com.cuong.cflip.cover.CoverSwipeIndicatorManager.hide()
                            // vc133: stats recompute at once on toggle-off too
                            // (the manual refresh button is gone).
                            statusVersion++
                        }
                    }
                    var openModeExpanded by remember { mutableStateOf(false) }
                    var bubbleMenuExpanded by remember { mutableStateOf<String?>(null) }
                    // qp-outer-anim
                    AnimatedVisibility(
                        visible = settings.assistiveTouchEnabled,
                        enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(200)),
                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(200)),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            // vc120 QUICK PANEL OPEN MODE (user,
                            // 28/10): Quick Panel is NOT a bubble
                            // action anymore - it is a feature
                            // with two open modes (9Flip gb):
                            // AssistiveTouch bubble or Swipe
                            // indicator bar.
                            OpenModeRow("Mở panel bằng", settings.panelOpenMode,
                                expanded = openModeExpanded,
                                onExpand = { openModeExpanded = true },
                                onDismiss = { openModeExpanded = false },
                                onSelect = { m ->
                                    settings = settings.copy(panelOpenMode = m); settingsStore.save(settings); openModeExpanded = false
                                    // vc132 POKE (see toggle above): re-show the opener
                                    // for the new mode at once when Quick Panel is on.
                                    if (settings.assistiveTouchEnabled) ContextCompat.startForegroundService(context,
                                        Intent(context, CoverForegroundService::class.java))
                                })
                            Text("Thanh Vuốt là thanh nhỏ ở cạnh trái hoặc phải màn hình ngoài, hành vi chạm giống hệt bong bóng (chạm 1 lần, chạm 2 lần, giữ lâu theo đúng cài đặt bên dưới). Kéo ngang để chuyển thanh sang cạnh còn lại, kéo dọc để dời thanh lên xuống; kéo để di chuyển thì không mở panel. Chọn Thanh Vuốt thì bong bóng sẽ ẩn đi, chọn AssistiveTouch thì thanh sẽ ẩn đi.",
                                fontSize = 12.sp, color = CFlipColors.TextSecondary)
                            // vc136 SYNC: size + auto-dim are shared by both openers -
                            // they sit outside the bubble-only block, so changing
                            // them in either mode applies to bubble AND bar.
                            Row(modifier = Modifier.fillMaxWidth().clip(CircleShape).background(CFlipColors.Bg).padding(4.dp)) {
                                for ((size, label) in listOf(0 to "Nhỏ", 1 to "Vừa", 2 to "Lớn")) {
                                    val selected = settings.assistiveTouchSize == size
                                    Box(modifier = Modifier.weight(1f).clip(CircleShape)
                                        .background(if (selected) CFlipColors.Accent else Color.Transparent)
                                        .clickable {
                                            settings = settings.copy(assistiveTouchSize = size)
                                            settingsStore.save(settings)
                                            // vc133 SIZE LIVE (user, 29/10: size picks
                                            // did nothing): show() sizes the button once
                                            // at creation, and while the bubble was up the
                                            // service guard returned early, so a new size
                                            // never took effect. Hide the opener and poke
                                            // the service: tryShow re-shows it at the
                                            // new size right away.
                                            if (settings.assistiveTouchEnabled) {
                                                com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
                                                com.cuong.cflip.cover.CoverSwipeIndicatorManager.hide()
                                                ContextCompat.startForegroundService(context,
                                                    Intent(context, CoverForegroundService::class.java))
                                            }
                                        }
                                        .padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                            color = if (selected) CFlipColors.AccentDark else CFlipColors.TextSecondary)
                                    }
                                }
                            }
                            HubSwitch("Tự mờ sau 2.5s không chạm", settings.assistiveTouchAutoDim) {
                                settings = settings.copy(assistiveTouchAutoDim = it); settingsStore.save(settings)
                                // vc136 SYNC: re-show both openers at once so the bar
                                // follows the same auto-dim switch as the bubble.
                                if (settings.assistiveTouchEnabled) {
                                    com.cuong.cflip.cover.CoverAssistiveTouchManager.hide()
                                    com.cuong.cflip.cover.CoverSwipeIndicatorManager.hide()
                                    ContextCompat.startForegroundService(context,
                                        Intent(context, CoverForegroundService::class.java))
                                }
                            }
                            // vc138 SYNC (user, 29/10: the bar is only a style,
                            // not an independent function): touch actions now
                            // apply to BOTH openers, so this block is shown
                            // in both modes instead of bubble-only.
                            // vc117 DROPDOWN (user, 27/10):
                            // tap-to-cycle rows were easy to
                            // overshoot (e.g. cycling past to
                            // NONE, then long-press looked dead).
                            // Each row now opens a dropdown with
                            // all 5 actions, so the choice is
                            // visible before it is made. Defaults:
                            // tap = Home, double-tap = Full panel,
                            // long-press = Recents.
                            // vc138 SYNC: these rows now apply to both openers -
                            // the bar is only a style, not an independent function.
                            Text("Thay đổi hành vi AssistiveTouch",
                                fontSize = 13.sp, color = CFlipColors.TextPrimary)
                            BubbleActionRow("Chạm 1 lần", settings.bubbleTapAction,
                                expanded = bubbleMenuExpanded == "tap",
                                onExpand = { bubbleMenuExpanded = "tap" },
                                onDismiss = { if (bubbleMenuExpanded == "tap") bubbleMenuExpanded = null },
                                onSelect = { a -> settings = settings.copy(bubbleTapAction = a); settingsStore.save(settings); bubbleMenuExpanded = null })
                            BubbleActionRow("Chạm 2 lần", settings.bubbleDoubleTapAction,
                                expanded = bubbleMenuExpanded == "double",
                                onExpand = { bubbleMenuExpanded = "double" },
                                onDismiss = { if (bubbleMenuExpanded == "double") bubbleMenuExpanded = null },
                                onSelect = { a -> settings = settings.copy(bubbleDoubleTapAction = a); settingsStore.save(settings); bubbleMenuExpanded = null })
                            BubbleActionRow("Giữ lâu", settings.bubbleLongPressAction,
                                expanded = bubbleMenuExpanded == "long",
                                onExpand = { bubbleMenuExpanded = "long" },
                                onDismiss = { if (bubbleMenuExpanded == "long") bubbleMenuExpanded = null },
                                onSelect = { a -> settings = settings.copy(bubbleLongPressAction = a); settingsStore.save(settings); bubbleMenuExpanded = null })
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
        }
    }
}

private data class MainStatuses(
    val notifications: Boolean,
    val accessibility: Boolean,
    val overlay: Boolean,
    val serviceRunning: Boolean,
    val secondarySeen: Boolean,
) {
    val requiredCount: Int
        get() = listOf(notifications, accessibility, overlay).count { it }
}

@Suppress("DEPRECATION")
private fun isServiceRunning(context: Context, cls: Class<*>): Boolean {
    val am = context.getSystemService(ActivityManager::class.java) ?: return false
    return runCatching { am.getRunningServices(Int.MAX_VALUE) }
        .getOrNull()?.any { it.service.className == cls.name } == true
}

// vc121 ISLAND: one feature group = one
// island - a small caps label 8dp above a
// rounded card, same card style as the
// existing Widget / Permissions / Service
// islands (20dp corners, Surface, CardStroke,
// 16dp padding). Callers pass their own inner
// Column so row spacing stays as before.
@Composable
private fun Island(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF1A2220), CFlipColors.Surface)))
            .border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
            .padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        color = CFlipColors.TextFaint, letterSpacing = 1.4.sp)
}

@Composable
private fun StatColumn(label: String, value: String, ok: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.height(32.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape)
                    .background(if (ok) CFlipColors.Accent else CFlipColors.TextFaint))
                Text(label, fontSize = 12.sp, color = CFlipColors.TextSecondary,
                    maxLines = 2, textAlign = TextAlign.Center)
            }
        }
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            color = CFlipColors.TextPrimary, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onOpen: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable { onOpen() }.padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(22.dp).clip(CircleShape)
            .background(if (granted) CFlipColors.Accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (granted) CFlipColors.Accent else CFlipColors.CardStroke, CircleShape),
            contentAlignment = Alignment.Center) {
            if (granted) Text("✓", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = CFlipColors.Accent)
        }
        Text(label, fontSize = 14.sp, color = CFlipColors.TextPrimary,
            modifier = Modifier.weight(1f).padding(start = 12.dp))
        Text(if (granted) "Đã cấp" else "Chưa cấp", fontSize = 12.sp,
            color = if (granted) CFlipColors.TextSecondary else CFlipColors.Danger)
        Text("  ›", fontSize = 16.sp, color = CFlipColors.TextFaint)
    }
}

// vc117: one bubble-action row with a dropdown
// menu (top-level @Composable, like HubSwitch).
@Composable
private fun BubbleActionRow(label: String, cur: Int, expanded: Boolean, onExpand: () -> Unit, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(CFlipColors.Bg)
            .clickable { onExpand() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = CFlipColors.TextSecondary,
                modifier = Modifier.weight(1f))
            Text(com.cuong.cflip.data.BubbleActions.label(cur),
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = CFlipColors.Accent)
            Text("  \u25BE", fontSize = 13.sp, color = CFlipColors.TextSecondary)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            containerColor = CFlipColors.Surface,
        ) {
            com.cuong.cflip.data.BubbleActions.all.forEach { action ->
                DropdownMenuItem(
                    text = { Text(com.cuong.cflip.data.BubbleActions.label(action),
                        color = if (action == cur) CFlipColors.Accent else CFlipColors.TextPrimary,
                        fontWeight = if (action == cur) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { onSelect(action); onDismiss() }
                )
            }
        }
    }
}

// vc120: open-mode row, same dropdown pattern
// as BubbleActionRow, for PanelOpenMode.
@Composable
private fun OpenModeRow(label: String, cur: Int, expanded: Boolean, onExpand: () -> Unit, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(CFlipColors.Bg)
            .clickable { onExpand() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = CFlipColors.TextSecondary,
                modifier = Modifier.weight(1f))
            Text(com.cuong.cflip.data.PanelOpenMode.label(cur),
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
                color = CFlipColors.Accent)
            Text("  \u25BE", fontSize = 13.sp, color = CFlipColors.TextSecondary)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
            modifier = Modifier.border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            containerColor = CFlipColors.Surface,
        ) {
            com.cuong.cflip.data.PanelOpenMode.all.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(com.cuong.cflip.data.PanelOpenMode.label(mode),
                        color = if (mode == cur) CFlipColors.Accent else CFlipColors.TextPrimary,
                        fontWeight = if (mode == cur) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = { onSelect(mode); onDismiss() }
                )
            }
        }
    }
}

@Composable
private fun HubSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = CFlipColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CFlipColors.AccentDark,
                checkedTrackColor = CFlipColors.Accent,
            ))
    }
}
