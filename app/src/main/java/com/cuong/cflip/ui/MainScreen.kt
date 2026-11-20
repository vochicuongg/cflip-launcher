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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.cuong.cflip.control.QuickControls
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.data.BackgroundStyles
import com.cuong.cflip.data.CoverSettings
import com.cuong.cflip.data.CustomGroupStore
import com.cuong.cflip.data.HiddenAppsStore
import com.cuong.cflip.data.RecentAppsRepository
import com.cuong.cflip.data.SettingsStore
import com.cuong.cflip.data.ThemeMode
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
 *
 * vc257 THEME: the hub owns the settings state, and the wrapper
 * below provides the colour scheme for it (Dark / Light / Follow
 * system). The body lives in MainScreenContent; the settings state
 * is hoisted as a MutableState so the body keeps its `settings = …`
 * writes unchanged. The cover branch re-provides Dark (the cover
 * always stays dark — user wallpaper / style colour).
 */
@Composable
fun MainScreen(
    initialTab: CoverTab = CoverTab.HOME,
    showCoverInitially: Boolean = false,
) {
    val context = LocalContext.current
    val settingsStore = remember { SettingsStore(context) }
    val settingsState = remember { mutableStateOf(settingsStore.load()) }
    val settings by settingsState
    val isDark = when (settings.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        else -> true
    }
    CompositionLocalProvider(
        LocalCFlipScheme provides if (isDark) DarkCFlipScheme else LightCFlipScheme
    ) {
        MainScreenContent(initialTab, showCoverInitially, settingsStore, settingsState)
    }
}

@Composable
private fun MainScreenContent(
    initialTab: CoverTab = CoverTab.HOME,
    showCoverInitially: Boolean = false,
    settingsStore: SettingsStore,
    settingsState: MutableState<CoverSettings>,
) {
    val context = LocalContext.current
    val displayRepo = remember { DisplayRepository(context) }
    var settings by settingsState
    val appRepo = remember { AppRepository(context) }
    val hiddenStore = remember { HiddenAppsStore(context) }
    val recentRepo = remember { RecentAppsRepository(context) }
    // vc254 STYLE HOST: the STYLE tab needs the hidden-apps list +
    // unhide handler (same wiring as CoverApp's STYLE tab).
    var appsVersion by remember { mutableStateOf(0) }
    var hidden by remember { mutableStateOf(hiddenStore.getHidden()) }
    val hiddenApps = remember(appsVersion) {
        appRepo.loadLaunchableApps(includeHidden = true).filter { it.packageName in hidden }
    }
    var showCover by remember { mutableStateOf(showCoverInitially) }
    var showGroupEditor by remember { mutableStateOf(false) }
    // vc163 SCROLL: hoisted above the showCover/showGroupEditor
    // early returns — the state used to live inside the settings
    // Column, so entering any feature disposed it and Back always
    // landed on the top of the app instead of the old position.
    val settingsScroll = rememberScrollState()
    var coverTab by remember { mutableStateOf(initialTab) }

    // vc244 ENTRANCE (UI/UX Pro Max): the hub fades/slides in once per
    // app launch — hero first, then one section every 40ms. These live
    // above the showCover/showGroupEditor/showWidgetDrawer/showStyle/showRecents early returns,
    // so coming back from any of them never replays the entrance.
    // Reduced motion skips it entirely (end state at once).
    val reducedMotion = rememberReducedMotion()
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    // vc254: the App + Notification drawers are gone (their islands
    // are deleted — Cường didn't know what they were for). The Widget
    // drawer stays, and two new screens join the same early-return
    // pattern: Style (the STYLE tab) and Recents.
    var showWidgetDrawer by remember { mutableStateOf(false) }
    var showStyle by remember { mutableStateOf(false) }
    var showRecents by remember { mutableStateOf(false) }

    if (showCover) {
        BackHandler { showCover = false }
        // vc257: the cover always stays dark, whatever the app
        // theme is — its background is the user's wallpaper / style
        // colour, and dark text on a dark wallpaper would be unreadable.
        CompositionLocalProvider(LocalCFlipScheme provides DarkCFlipScheme) {
            Column(modifier = Modifier.fillMaxSize().background(CFlipColors.Bg).statusBarsPadding()) {
                TextButton(onClick = { showCover = false }) { Text("← Quay lại", color = CFlipColors.Accent) }
                CoverApp(modifier = Modifier.weight(1f),
                    targetDisplayId = displayRepo.secondaryDisplay()?.displayId,
                    initialTab = coverTab)
            }
        }
        return
    }

    // vc152 CUSTOM GROUP EDITOR (user, 29/10): a section in the
    // app where users build their own app groups, like the reference app.
    if (showGroupEditor) {
        BackHandler { showGroupEditor = false }
        GroupEditorScreen(onBack = { showGroupEditor = false })
        return
    }

    // vc245 WIDGET DRAWER: widget activation lives here now (was the
    // Widget island below); the toggle, Samsung actions and the
    // group-editor link moved with it.
    if (showWidgetDrawer) {
        BackHandler { showWidgetDrawer = false }
        WidgetDrawerScreen(
            settings = settings,
            onSettingsChange = { settings = it; settingsStore.save(it) },
            onBack = { showWidgetDrawer = false },
            onOpenGroupEditor = { showWidgetDrawer = false; showGroupEditor = true },
        )
        return
    }

    // vc254 STYLE: the cover STYLE tab (SettingsScreen) on the big
    // screen — opened by the "Hình nền & Kiểu" island. Back row on
    // top like the cover preview (SettingsScreen itself has no back
    // button — it was built as a tab).
    if (showStyle) {
        BackHandler { showStyle = false }
        Column(modifier = Modifier.fillMaxSize().background(CFlipColors.Bg).statusBarsPadding()) {
            TextButton(onClick = { showStyle = false }) { Text("← Quay lại", color = CFlipColors.Accent) }
            SettingsScreen(
                settings = settings,
                onSettingsChange = { settings = it; settingsStore.save(it) },
                modifier = Modifier.weight(1f),
                hiddenApps = hiddenApps,
                onUnhideApp = { app ->
                    hiddenStore.unhide(app.packageName)
                    hidden = hiddenStore.getHidden()
                    appsVersion++
                },
            )
        }
        return
    }

    // vc254 RECENTS: the "Mở gần đây" island. The screen loads its
    // own list once per open (see RecentAppsScreen).
    if (showRecents) {
        BackHandler { showRecents = false }
        RecentAppsScreen(onBack = { showRecents = false })
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
            // vc255 USAGE ACCESS: shown as a row in the Quyền island
            // below (moved out of the STYLE tab). NOT part of the
            // required /3 count — recents work without it through
            // the launch-history fallback, it only makes them fuller.
            usageAccess = recentRepo.hasUsageAccess(),
            serviceRunning = isServiceRunning(context, CoverForegroundService::class.java),
            secondarySeen = displayRepo.secondaryDisplay() != null,
        )
    }
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "2.3"
    }

    // vc254 SUMMARIES (recomputed on every resume — statusVersion
    // bumps on ON_RESUME — so edits made in the Group Editor /
    // Style screens are reflected when coming back). One
    // PackageManager query per resume, shared by both summaries.
    val allApps = remember(statusVersion) { appRepo.loadLaunchableApps() }
    val groupSummary = remember(allApps) {
        val (sections, _) = CustomGroupStore.sections(
            allApps, CustomGroupStore.load(context),
            CustomGroupStore.loadHiddenAuto(context),
            CustomGroupStore.loadOverrides(context),
            CustomGroupStore.loadAutoOrder(context),
            CustomGroupStore.loadUnifiedOrder(context),
            CustomGroupStore.loadDeletedSections(context))
        if (sections.isEmpty()) "Chưa có nhóm"
        else "${sections.size} nhóm · ${sections.sumOf { it.members.size }} ứng dụng"
    }
    val recentApps = remember(allApps) {
        val byPackage = allApps.associateBy { it.packageName }
        recentRepo.recentPackages(8).mapNotNull { byPackage[it] }
    }
    val styleDetail = remember(settings) {
        val cols = if (settings.columns == 0) "Tự động" else "${settings.columns} cột"
        "$cols · icon ${settings.iconSizeDp} dp"
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    // vc126 PERMISSION DEEP LINKS (the reference app pattern, clean-room):
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

    // vc257 FLAT BACKGROUND (user, 15/11): the in-app background is
    // ONE FLAT COLOUR per theme (CFlipColors.Bg) — the 3-layer
    // background is gone entirely (L0 gradient, L1 TopGlow, L2 blobs
    // with their morph/drift). No gradient, no glow, no blobs, square
    // or round. The glass cards below carry the depth now (translucent
    // fill + bright rim + specular + soft shadow).
    Column(
        modifier = Modifier.fillMaxSize().background(CFlipColors.Bg).statusBarsPadding()
            .verticalScroll(settingsScroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
            EnterSection(index = 0, entered = entered, reduced = reducedMotion, hero = true) {
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
            }

            EnterSection(index = 1, entered = entered, reduced = reducedMotion) {
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
            }

            // vc254 GROUPS (the reference app fragment_settings pattern):
            // the hub reads as two groups now — TRẠNG THÁI (is the
            // phone OK? fix what is not) and MÀN HÌNH NGOÀI (cover
            // features), with "Mở gần đây" between them as a quick
            // launcher. Inside a group the islands sit 12dp apart;
            // groups sit 24dp apart (the outer Column spacing).
            // Order inside TRẠNG THÁI is status first, then the fix:
            // the Stats card diagnoses (Quyền x/3, dịch vụ, màn hình
            // ngoài), the Quyền island right below remedies it.
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupLabel("TRẠNG THÁI")
                EnterSection(index = 2, entered = entered, reduced = reducedMotion) {
                // vc134 STATS (user, 29/10: the 3 separate cards had uneven heights
                // - "Màn hình ngoài" wrapped to 2 lines): one card, 3 columns with
                // dividers, fixed 2-line label slot so values always align.
                // vc257: the solid gradient card becomes a GlassCard so it
                // reads as glass on the flat background, like the islands.
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        StatColumn("Quyền", "${statuses.requiredCount}/3", statuses.requiredCount == 3, Modifier.weight(1f))
                        Box(modifier = Modifier.width(1.dp).height(40.dp).background(CFlipColors.CardStroke))
                        StatColumn("Dịch vụ", if (statuses.serviceRunning) "Đang chạy" else "Tắt", statuses.serviceRunning, Modifier.weight(1f))
                        Box(modifier = Modifier.width(1.dp).height(40.dp).background(CFlipColors.CardStroke))
                        StatColumn("Màn hình ngoài", if (statuses.secondarySeen) "Đã thấy" else "Chưa thấy", statuses.secondarySeen, Modifier.weight(1f))
                    }
                }
                }

                EnterSection(index = 3, entered = entered, reduced = reducedMotion) {
                // vc126 PERMISSIONS (the reference app pattern, clean-room):
                // deep-link page first, generic page as fallback, toast if
                // none resolves; the "Cấp quyền còn thiếu" button jumps to
                // the first missing permission in row order, and the vc115
                // ON_RESUME refresh makes each return land on the next one.
                // (Warning now sits above this island, vc126.)
                // Permissions: compact rows, chevron only, plus that button.
                // vc127 REQUIRED SET (the reference app evidence, user, 29/10): the reference app declares
                // no Usage Stats and no Write Settings - its recents come from its
                // own launch history (like CFlip's LaunchHistoryStore fallback) and
                // it never writes system brightness. So CFlip's required set is now
                // 3 (Thông báo, Accessibility, Overlay); Usage Access is NOT in
                // the required set but has its own row below (vc255, moved from
                // the STYLE tab) so its state is visible on the hub.
                // vc128 CORRECTION: Write Settings is NOT dead - the cover QUICK
                // tab has its own system-brightness slider (QuickControlsScreen)
                // that writes Settings.System and already grants on demand
                // (slider toast + grant button). Panel brightness is window-local
                // since 3.2.0, but the QUICK-tab slider still needs the write
                // helpers, so they are restored; Write stays OUT of the required
                // set (3) and is granted on demand only.
                Island("Quyền") {
                    Column {
                        // perm-btn-anim
                        AnimatedVisibility(
                            visible = statuses.requiredCount < 3,
                            enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(200)),
                            exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(200)),
                        ) {
                            Column {
                                val permPress = remember { MutableInteractionSource() }
                                Box(modifier = Modifier.fillMaxWidth().clip(CircleShape).background(CFlipColors.Accent)
                                    .pressScale(permPress)
                                    .clickable(interactionSource = permPress, indication = LocalIndication.current) {
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
                        // vc255 USAGE ACCESS: moved here from the STYLE
                        // tab QUYỀN section, so all permissions live
                        // in one island. Opens the same system Usage
                        // Access page; the row refreshes on return via
                        // the ON_RESUME statusVersion bump above.
                        PermissionRow("Usage Access", statuses.usageAccess) {
                            QuickControls.openUsageAccessSettings(context)
                        }
                    }
                }
                }
            }

            // vc254 RECENTS (the reference app "recently opened", clean-room): the
            // apps actually opened lately, as a quick launcher from
            // the big screen. The screen itself is RecentAppsScreen.
            EnterSection(index = 4, entered = entered, reduced = reducedMotion) {
            Island("Mở gần đây") {
                GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { showRecents = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(modifier = Modifier.size(40.dp).clip(CircleShape)
                            .background(CFlipColors.Accent.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.History, contentDescription = null,
                                tint = CFlipColors.Accent, modifier = Modifier.size(20.dp))
                        }
                        Column(modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                if (recentApps.isEmpty()) "Chưa có ứng dụng gần đây"
                                else "${recentApps.size} ứng dụng gần đây",
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = CFlipColors.TextPrimary)
                            Text(
                                if (recentApps.isEmpty()) "Mở vài ứng dụng rồi quay lại đây"
                                else recentApps.first().label,
                                fontSize = 12.sp, color = CFlipColors.TextSecondary,
                                maxLines = 1)
                        }
                        Text("›", fontSize = 20.sp, color = CFlipColors.TextFaint)
                    }
                }
            }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupLabel("MÀN HÌNH NGOÀI")
                // vc245 WIDGET DRAWER: activation moved into WidgetDrawerScreen
                // (same pattern as the other islands + drawers). The toggle,
                // Samsung actions and group-editor link live there now.
                // vc135 strip kept: a "clock" key saved from the cover panel
                // is still dropped here so it cannot stay on with no switch
                // to turn it off.
                run {
                    val chosenNow = settings.coverWidgets.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    if ("clock" in chosenNow) {
                        val nextWidgets = (chosenNow - "clock").sorted().joinToString(",")
                        settings = settings.copy(coverWidgets = nextWidgets); settingsStore.save(settings)
                    }
                }
                val widgetChosen = settings.coverWidgets.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val widgetSummary = if (widgetChosen.isEmpty()) "Chưa bật widget nào · Mở đảo Widget"
                    else "Đã bật ${widgetChosen.size} widget · Mở đảo Widget"
                EnterSection(index = 5, entered = entered, reduced = reducedMotion) {
                Island("Widget") {
                    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { showWidgetDrawer = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(CFlipColors.Accent.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.GridView, contentDescription = null,
                                    tint = CFlipColors.Accent, modifier = Modifier.size(20.dp))
                            }
                            Column(modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("Widget màn hình ngoài", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = CFlipColors.TextPrimary)
                                Text(widgetSummary, fontSize = 12.sp, color = CFlipColors.TextSecondary)
                            }
                            Text("›", fontSize = 20.sp, color = CFlipColors.TextFaint)
                        }
                    }
                }
                }

                // vc254 GROUPS ISLAND (the reference app "Group manager" + Cover
                // Power "APP FOLDERS"): groups decide the cover grid's
                // folders, but their editor was buried 2 taps deep
                // inside the Widget drawer — this island puts it on
                // the hub. Opens the existing GroupEditorScreen.
                EnterSection(index = 6, entered = entered, reduced = reducedMotion) {
                Island("Nhóm ứng dụng") {
                    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { showGroupEditor = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(CFlipColors.Accent.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Folder, contentDescription = null,
                                    tint = CFlipColors.Accent, modifier = Modifier.size(20.dp))
                            }
                            Column(modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(groupSummary,
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = CFlipColors.TextPrimary)
                                Text("Tạo và sắp xếp các thư mục trên lưới cover",
                                    fontSize = 12.sp, color = CFlipColors.TextSecondary,
                                    maxLines = 1)
                            }
                            Text("›", fontSize = 20.sp, color = CFlipColors.TextFaint)
                        }
                    }
                }
                }

                // vc254 STYLE ISLAND (the reference app "Wallpaper & Style"
                // + the reference app "Cover Wallpaper"): personalisation is the
                // most-touched setting after install, but it lived
                // inside the STYLE tab. Opens the existing
                // SettingsScreen on the big screen.
                EnterSection(index = 7, entered = entered, reduced = reducedMotion) {
                Island("Hình nền & Kiểu") {
                    GlassCard(modifier = Modifier.fillMaxWidth(), onClick = { showStyle = true }) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(modifier = Modifier.size(40.dp).clip(CircleShape)
                                .background(CFlipColors.Accent.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Wallpaper, contentDescription = null,
                                    tint = CFlipColors.Accent, modifier = Modifier.size(20.dp))
                            }
                            Column(modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    if (settings.wallpaperUri != null) "Hình nền riêng"
                                    else BackgroundStyles.names[settings.backgroundStyle.coerceIn(0, BackgroundStyles.names.lastIndex)],
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = CFlipColors.TextPrimary)
                                Text(styleDetail,
                                    fontSize = 12.sp, color = CFlipColors.TextSecondary,
                                    maxLines = 1)
                            }
                            Text("›", fontSize = 20.sp, color = CFlipColors.TextFaint)
                        }
                    }
                }
                }

                EnterSection(index = 8, entered = entered, reduced = reducedMotion) {
                // vc121 ISLANDS (user, 28/10: group the
                // main settings into separate feature
                // islands, neat like the reference app). The old
                // "Cai dat cover" box crammed unrelated
                // features - display, AssistiveTouch,
                // Quick Panel, touch actions - into one
                // card. Each group is now its own island
                // (the reference app pattern: grouped sections, one
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
                                // with two open modes (the reference app gb):
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
                                Text("Thanh nhỏ ở cạnh màn hình ngoài, chạm giống hệt bong bóng. Kéo ngang đổi cạnh, kéo dọc dời thanh, kéo không mở panel; chọn Thanh Vuốt thì bong bóng ẩn đi và ngược lại.",
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
                                            .padding(vertical = 12.dp).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
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
                }
            }

            Spacer(Modifier.height(8.dp))
        }
}

private data class MainStatuses(
    val notifications: Boolean,
    val accessibility: Boolean,
    val overlay: Boolean,
    val usageAccess: Boolean,
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
// islands. Callers pass their own inner
// Column so row spacing stays as before.
// vc257: the island card is a GlassCard now
// (was a solid gradient + 20dp corners) so
// it reads as glass on the flat background.
@Composable
private fun Island(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

// vc244 ENTRANCE (UI/UX Pro Max): one hub section fades in and rises
// 24px, each 40ms after the previous one (the hero also scales from
// 0.96). Runs once per app launch (the `entered` flag is remembered
// above the early returns); reduced motion renders the end state with
// no animation. Only alpha / offset / scale are animated — never
// width or height.
@Composable
private fun EnterSection(
    index: Int,
    entered: Boolean,
    reduced: Boolean,
    hero: Boolean = false,
    content: @Composable () -> Unit,
) {
    if (reduced) { content(); return }
    val delay = minOf(index, 6) * 40
    AnimatedVisibility(
        visible = entered,
        enter = fadeIn(animationSpec = tween(CFlipMotion.MediumMs, delayMillis = delay)) +
            if (hero) scaleIn(animationSpec = tween(CFlipMotion.MediumMs, delayMillis = delay), initialScale = 0.96f)
            else slideInVertically(animationSpec = tween(CFlipMotion.MediumMs, delayMillis = delay), initialOffsetY = { 24 }),
        exit = fadeOut(animationSpec = tween(CFlipMotion.FastMs)),
    ) { content() }
}

// vc254 GROUP LABEL (fix: used by the two hub groups but never
// defined — the build would fail on an unresolved reference):
// a group header above a cluster of islands (TRẠNG THÁI /
// MÀN HÌNH NGOÀI) — same 11sp caps as SectionLabel, with a mint
// dot so a group reads as a group, not another island.
@Composable
private fun GroupLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape)
            .background(CFlipColors.Accent))
        Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = CFlipColors.TextFaint, letterSpacing = 1.4.sp)
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
        // vc248: the value crossfades when it changes (permission
        // granted, service started) instead of swapping instantly.
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                fadeIn(animationSpec = tween(CFlipMotion.FastMs)) togetherWith
                    fadeOut(animationSpec = tween(CFlipMotion.FastMs))
            },
            label = "statValue",
        ) { v ->
            Text(v, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = CFlipColors.TextPrimary, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onOpen: () -> Unit) {
    val pressSource = remember { MutableInteractionSource() }
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .pressScale(pressSource)
        .clickable(interactionSource = pressSource, indication = LocalIndication.current) { onOpen() }
        .padding(vertical = 11.dp),
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
// vc248: press scale on the row (same source as the ripple) —
// before, the row only had a ripple and no scale feedback.
@Composable
private fun BubbleActionRow(label: String, cur: Int, expanded: Boolean, onExpand: () -> Unit, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    val pressSource = remember { MutableInteractionSource() }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(CFlipColors.Bg)
            .pressScale(pressSource)
            .clickable(interactionSource = pressSource, indication = LocalIndication.current) { onExpand() }
            .padding(horizontal = 12.dp, vertical = 12.dp).heightIn(min = 48.dp),
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
// vc248: press scale on the row, like BubbleActionRow above.
@Composable
private fun OpenModeRow(label: String, cur: Int, expanded: Boolean, onExpand: () -> Unit, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    val pressSource = remember { MutableInteractionSource() }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(CFlipColors.Bg)
            .pressScale(pressSource)
            .clickable(interactionSource = pressSource, indication = LocalIndication.current) { onExpand() }
            .padding(horizontal = 12.dp, vertical = 12.dp).heightIn(min = 48.dp),
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
    // vc248: the whole row toggles (not just the switch) and the row
    // is 48dp — same pattern as SettingsScreen's SwitchRow. Before,
    // tapping the label did nothing.
    val pressSource = remember { MutableInteractionSource() }
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .pressScale(pressSource)
        .clickable(interactionSource = pressSource, indication = LocalIndication.current) { onChange(!checked) }
        .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = CFlipColors.TextPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CFlipColors.AccentDark,
                checkedTrackColor = CFlipColors.Accent,
            ))
    }
}
