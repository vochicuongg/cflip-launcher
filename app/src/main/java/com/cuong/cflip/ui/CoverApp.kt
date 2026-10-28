package com.cuong.cflip.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.data.AppInfo
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.data.BackgroundStyles
import com.cuong.cflip.data.CoverSettings
import com.cuong.cflip.data.FavoritesStore
import com.cuong.cflip.data.HiddenAppsStore
import com.cuong.cflip.data.RecentAppsRepository
import com.cuong.cflip.data.SettingsStore
import com.cuong.cflip.display.AppLauncher
import com.cuong.cflip.media.MediaState
import com.cuong.cflip.service.NotificationState
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

enum class CoverTab(val label: String, val icon: ImageVector) {
    HOME("Trang chủ", Icons.Filled.Home),
    APPS("Ứng dụng", Icons.Filled.GridView),
    NOTIFS("Thông báo", Icons.Filled.Notifications),
    MEDIA("Nhạc", Icons.Filled.MusicNote),
    QUICK("Tiện ích", Icons.Filled.Bolt),
    STYLE("Cá nhân", Icons.Filled.Palette)
}

/**
 * The complete cover experience, redesigned in CFlip 2.0:
 * glass cards, one mint accent, a floating pill nav, a slim status row,
 * and a hero Home (clock, battery, now playing, favourites).
 * All behaviour (favourites, hidden apps, recents, notifications, media,
 * quick controls, personalisation) is unchanged from v1.x.
 */
@Composable
fun CoverApp(modifier: Modifier = Modifier, targetDisplayId: Int? = null, initialTab: CoverTab = CoverTab.HOME) {
    val context = LocalContext.current
    val appRepo = remember { AppRepository(context) }
    val favoritesStore = remember { FavoritesStore(context) }
    val hiddenStore = remember { HiddenAppsStore(context) }
    val settingsStore = remember { SettingsStore(context) }
    val recentRepo = remember { RecentAppsRepository(context) }

    // appsVersion bumps whenever the hidden set changes so the lists reload.
    var appsVersion by remember { mutableStateOf(0) }
    val allApps = remember(appsVersion) { appRepo.loadLaunchableApps() }
    var hidden by remember { mutableStateOf(hiddenStore.getHidden()) }
    val hiddenApps = remember(appsVersion) {
        appRepo.loadLaunchableApps(includeHidden = true).filter { it.packageName in hidden }
    }
    var menuApp by remember { mutableStateOf<AppInfo?>(null) }
    var favorites by remember { mutableStateOf(favoritesStore.getFavorites()) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var tab by remember { mutableStateOf(initialTab) }
    var search by remember { mutableStateOf("") }

    val notifications by NotificationState.active.collectAsState()
    val media by MediaState.current.collectAsState()
    val batteryPct = rememberBatteryPercent()
    val charging = remember(batteryPct) { readBatteryCharging(context) }

    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { while (true) { now = LocalDateTime.now(); delay(1000) } }

    val byPackage = remember(allApps) { allApps.associateBy { it.packageName } }
    // Old bug: keyed only on favorites, so Recents never refreshed when the
    // app list changed (hide/unhide) or after launching an app from CFlip.
    // Keying on tab too means opening the Apps tab re-reads recents, so a
    // just-launched app shows up when the user comes back to CFlip.
    val recentApps = remember(favorites, appsVersion, allApps, tab) {
        recentRepo.recentPackages().mapNotNull { byPackage[it] }
    }
    // Sort modes (new in 2.2, same three modes the reference app offers):
    // 0 = A-Z (allApps already arrives alphabetical), 1 = recently opened
    // (recent order first, the rest alphabetical), 2 = newly installed
    // (firstInstallTime descending). Favorites-first, when on, is a stable
    // primary key on top of whichever sort the user picked.
    val appsOrdered = remember(favorites, settings.favoritesFirst, settings.sortMode, allApps, recentApps) {
        val bySort: List<AppInfo> = when (settings.sortMode) {
            1 -> {
                val recentIdx = recentApps.mapIndexed { i, a -> a.packageName to i }.toMap()
                allApps.sortedWith(compareBy<AppInfo> { recentIdx[it.packageName] ?: Int.MAX_VALUE }
                    .thenBy { it.label.lowercase() })
            }
            2 -> {
                val installed = appRepo.installTimes(allApps.map { it.packageName })
                allApps.sortedWith(compareByDescending<AppInfo> { installed[it.packageName] ?: 0L }
                    .thenBy { it.label.lowercase() })
            }
            else -> allApps
        }
        if (settings.favoritesFirst) bySort.sortedByDescending { it.packageName in favorites } else bySort
    }

    // Old bug: a full-resolution photo was decoded at native size on the
    // main thread - a 50MP photo could OOM or freeze the cover. Downsample
    // to at most 1024px on the long edge (far above the 720px cover).
    val wallpaperBitmap = remember(settings.wallpaperUri) {
        settings.wallpaperUri?.let { uriStr ->
            runCatching {
                val uri = Uri.parse(uriStr)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (uriStr.startsWith("file:")) {
                    uri.path?.let { BitmapFactory.decodeFile(it, bounds) }
                } else {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                }
                var sample = 1
                val longest = maxOf(bounds.outWidth, bounds.outHeight)
                while (longest / sample > 1024) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                if (uriStr.startsWith("file:")) {
                    uri.path?.let { BitmapFactory.decodeFile(it, opts) }
                } else {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                }
            }.getOrNull()
        }
    }

    var dragTotal by remember { mutableStateOf(0f) }
    val tabs = CoverTab.entries

    Box(
        modifier = modifier.fillMaxSize()
            .background(Color(BackgroundStyles.colors[settings.backgroundStyle.coerceIn(0, BackgroundStyles.colors.lastIndex)]))
            .pointerInput(tab) {
                detectHorizontalDragGestures(
                    onDragStart = { dragTotal = 0f },
                    onHorizontalDrag = { _, amount -> dragTotal += amount },
                    onDragEnd = {
                        val idx = tabs.indexOf(tab)
                        if (dragTotal < -60f && idx < tabs.lastIndex) tab = tabs[idx + 1]
                        else if (dragTotal > 60f && idx > 0) tab = tabs[idx - 1]
                    }
                )
            }
    ) {
        if (wallpaperBitmap != null) {
            Image(bitmap = wallpaperBitmap.asImageBitmap(), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(modifier = Modifier.fillMaxSize().background(CFlipColors.Scrim))
        Box(modifier = Modifier.fillMaxSize().background(CFlipColors.TopGlow))

        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            // vc130 MOTION (user, 29/10): the status row expands/fades in and out with the tab.
            AnimatedVisibility(
                visible = tab != CoverTab.HOME,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(200)),
            ) {
                StatusRow(now = now, settings = settings, batteryPct = batteryPct, charging = charging)
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // vc130 MOTION (user, 29/10): tab content slides + fades
                // in the swipe direction instead of popping.
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (slideInHorizontally(animationSpec = tween(300)) { if (forward) it else -it } + fadeIn(animationSpec = tween(200))) togetherWith
                            (slideOutHorizontally(animationSpec = tween(300)) { if (forward) -it else it } + fadeOut(animationSpec = tween(200)))
                    },
                    label = "coverTab",
                ) { targetTab ->
                    when (targetTab) {
                        CoverTab.HOME -> HomeTab(
                            now = now, settings = settings,
                            batteryPct = batteryPct, charging = charging,
                            notifCount = notifications.size,
                            mediaTitle = media?.title, mediaPlaying = media?.isPlaying == true,
                            onOpenMedia = { tab = CoverTab.MEDIA },
                            onOpenNotifs = { tab = CoverTab.NOTIFS },
                        )
                        CoverTab.APPS -> Column(modifier = Modifier.fillMaxSize()) {
                            OutlinedTextField(
                                value = search, onValueChange = { search = it },
                                placeholder = { Text("Tìm ứng dụng") }, singleLine = true,
                                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                            if (recentApps.isNotEmpty() && search.isEmpty()) {
                                SectionHeader("GẦN ĐÂY")
                            }
                            val base = if (search.isEmpty()) {
                                (recentApps + appsOrdered).distinctBy { it.packageName }
                            } else appsOrdered.filter { it.label.contains(search, ignoreCase = true) }
                            CoverLauncherScreen(
                                apps = base,
                                favorites = favorites,
                                settings = settings,
                                onAppClick = { AppLauncher.launch(context, it.packageName, targetDisplayId) },
                                onToggleFavorite = { app ->
                                    favoritesStore.toggle(app.packageName)
                                    favorites = favoritesStore.getFavorites()
                                },
                                onAppMenu = { menuApp = it },
                            )
                        }
                        CoverTab.NOTIFS -> NotificationsScreen()
                        CoverTab.MEDIA -> MediaScreen()
                        CoverTab.QUICK -> QuickControlsScreen()
                        CoverTab.STYLE -> SettingsScreen(settings = settings,
                            onSettingsChange = { settings = it },
                            hiddenApps = hiddenApps,
                            onUnhideApp = { app ->
                                hiddenStore.unhide(app.packageName)
                                hidden = hiddenStore.getHidden()
                                appsVersion++
                            })
                    }
                }            }
            PillNav(
                tabs = tabs, current = tab,
                notifCount = notifications.size,
                onSelect = { tab = it },
            )
        }

        // Long-press app menu: favourite toggle + hide (v1.3)
        menuApp?.let { app ->
            AlertDialog(
                onDismissRequest = { menuApp = null },
                title = { Text(app.label) },
                text = {
                    Column {
                        TextButton(onClick = {
                            favoritesStore.toggle(app.packageName)
                            favorites = favoritesStore.getFavorites()
                            menuApp = null
                        }) {
                            Text(if (app.packageName in favorites) "Bỏ ghim yêu thích" else "Ghim yêu thích")
                        }
                        if (app.packageName != context.packageName) {
                            TextButton(onClick = {
                                hiddenStore.hide(app.packageName)
                                hidden = hiddenStore.getHidden()
                                appsVersion++
                                menuApp = null
                            }) { Text("Ẩn ứng dụng này") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { menuApp = null }) { Text("Đóng") }
                },
            )
        }
    }
}

/** Slim clock + battery row shown above every tab except Home. */
@Composable
private fun StatusRow(now: LocalDateTime, settings: CoverSettings, batteryPct: Int, charging: Boolean) {
    val timeFmt = if (settings.clock24h) DateTimeFormatter.ofPattern("HH:mm") else DateTimeFormatter.ofPattern("hh:mm a")
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(now.format(timeFmt), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = CFlipColors.TextPrimary)
        Text("  " + now.format(DateTimeFormatter.ofPattern("EEE, dd/MM")), fontSize = 12.sp, color = CFlipColors.TextSecondary)
        Spacer(Modifier.weight(1f))
        if (batteryPct >= 0) {
            StatusPill(icon = if (charging) Icons.Filled.Bolt else null, text = "$batteryPct%")
        }
    }
}

@Composable
private fun HomeTab(
    now: LocalDateTime,
    settings: CoverSettings,
    batteryPct: Int,
    charging: Boolean,
    notifCount: Int,
    mediaTitle: String?,
    mediaPlaying: Boolean,
    onOpenMedia: () -> Unit,
    onOpenNotifs: () -> Unit,
) {
    val timeFmt = if (settings.clock24h) DateTimeFormatter.ofPattern("HH:mm") else DateTimeFormatter.ofPattern("hh:mm a")
    Column(modifier = Modifier.fillMaxSize().padding(top = 14.dp)) {
        Text(now.format(timeFmt),
            style = TextStyle(brush = CFlipColors.ClockBrush, fontSize = 56.sp,
                fontWeight = FontWeight.Bold, letterSpacing = (-1).sp))
        Text(now.format(DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy")),
            fontSize = 14.sp, color = CFlipColors.TextSecondary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (batteryPct >= 0) {
                StatusPill(icon = if (charging) Icons.Filled.Bolt else null, text = "Pin $batteryPct%")
            }
            Box(modifier = Modifier.clickable { onOpenNotifs() }) {
                StatusPill(icon = Icons.Filled.Notifications,
                    text = if (notifCount > 0) "$notifCount thông báo mới" else "Không có thông báo",
                    tint = if (notifCount > 0) CFlipColors.Accent else CFlipColors.TextSecondary)
            }
        }
        if (mediaTitle != null) {
            Spacer(Modifier.height(10.dp))
            GlassCard(modifier = Modifier.fillMaxWidth(), onClick = onOpenMedia) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(modifier = Modifier.size(38.dp).clip(CircleShape)
                        .background(if (mediaPlaying) CFlipColors.AccentBrush else SolidColor(CFlipColors.CardFill)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.MusicNote, contentDescription = null,
                            tint = if (mediaPlaying) CFlipColors.AccentDark else CFlipColors.TextSecondary,
                            modifier = Modifier.size(20.dp))
                    }
                    Column {
                        Text("Đang phát", fontSize = 11.sp, color = CFlipColors.TextSecondary)
                        Text(mediaTitle, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            color = CFlipColors.TextPrimary, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // vc125 (user, 29/10): the YÊU THÍCH favorites section was
        // REMOVED (display only). Pin/unpin + favorites-first sorting
        // in the Apps tab and FavoritesStore stay untouched, so the
        // weight spacer below keeps the hint pinned to the bottom.
        Spacer(Modifier.weight(1f))
        Text("Vuốt ngang để chuyển tab · Giữ ứng dụng để mở menu",
            fontSize = 11.sp, color = CFlipColors.TextFaint,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    }
}

/** Floating pill navigation: selected tab gets a filled mint pill + label. */
@Composable
private fun PillNav(
    tabs: List<CoverTab>,
    current: CoverTab,
    notifCount: Int,
    onSelect: (CoverTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(CFlipColors.NavFill)
            .border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(28.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val selected = t == current
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) CFlipColors.AccentBrush else SolidColor(Color.Transparent))
                    .clickable { onSelect(t) }
                    .animateContentSize(animationSpec = tween(250))
                    .padding(horizontal = if (selected) 12.dp else 9.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BadgedBox(badge = {
                        if (t == CoverTab.NOTIFS && notifCount > 0 && !selected) {
                            Badge(containerColor = CFlipColors.Danger) { Text(notifCount.toString()) }
                        }
                    }) {
                        Icon(t.icon, contentDescription = t.label,
                            tint = if (selected) CFlipColors.AccentDark else CFlipColors.TextSecondary,
                            modifier = Modifier.size(20.dp))
                    }
                    if (selected) {
                        Text(t.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            color = CFlipColors.AccentDark)
                    }
                }
            }
        }
    }
}
