package com.cuong.cflip.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.data.AppInfo
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.data.CoverSettings
import com.cuong.cflip.data.FavoritesStore
import com.cuong.cflip.data.HiddenAppsStore
import com.cuong.cflip.data.RecentAppsRepository
import com.cuong.cflip.display.AppLauncher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * vc244 MAIN-APP DRAWER (UI/UX Pro Max): the full app list on the big
 * screen — search + the same three sort modes the cover reads, so the
 * cover and the drawer can never disagree about order.
 *
 * - Sort: the exact `when (settings.sortMode)` block from CoverApp.kt
 *   (0 = A-Z, 1 = recently opened, 2 = newly installed, favourites
 *   first on top when enabled). The picker writes the same
 *   SettingsStore the cover reads.
 * - Search: label contains, case-insensitive, plus an unaccented
 *   match ("den pin" finds "Đèn pin", "zalo" finds "Zalo") — the same
 *   idea as the cover's unaccented control keywords.
 * - Cells: the cover grid's pattern (stagger entrance, press scale,
 *   tap = launch, long-press = menu, favourite star, unread badge
 *   via the vc243 animateFloatAsState pattern) at main-screen size
 *   (56dp icons, 12sp labels). No new data sources: badges come from
 *   the same NotificationState the cover reads, passed in by MainScreen.
 * - No AnimatedVisibility inside a Box (vc243 lesson) and no
 *   FittedBox anywhere.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppDrawerScreen(
    settings: CoverSettings,
    onSettingsChange: (CoverSettings) -> Unit,
    notifCounts: Map<String, Int> = emptyMap(),
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val hapticView = LocalView.current
    val appRepo = remember { AppRepository(context) }
    val favoritesStore = remember { FavoritesStore(context) }
    val hiddenStore = remember { HiddenAppsStore(context) }
    val recentRepo = remember { RecentAppsRepository(context) }

    var appsVersion by remember { mutableStateOf(0) }
    var favorites by remember { mutableStateOf(favoritesStore.getFavorites()) }
    var search by remember { mutableStateOf("") }
    var menuApp by remember { mutableStateOf<AppInfo?>(null) }
    val allApps = remember(appsVersion) { appRepo.loadLaunchableApps() }
    val byPackage = remember(allApps) { allApps.associateBy { it.packageName } }
    val recentApps = remember(favorites, appsVersion, allApps) {
        recentRepo.recentPackages().mapNotNull { byPackage[it] }
    }
    // Sort modes — same three modes as the cover (CoverApp.kt).
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
    val shown = remember(search, appsOrdered) {
        if (search.isBlank()) appsOrdered
        else {
            val q = search.trim()
            val qFlat = q.unaccented()
            appsOrdered.filter { it.label.contains(q, ignoreCase = true) || it.label.unaccented().contains(qFlat) }
        }
    }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    BackHandler { onBack() }
    Box(
        modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(CFlipColors.Bg, Color(0xFF0B1512)))
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            // Top bar: back + count. Title below, like the hub's sections.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Quay lại", color = CFlipColors.Accent) }
                Spacer(Modifier.weight(1f))
                Text("${shown.size} ứng dụng", fontSize = 13.sp, color = CFlipColors.TextSecondary)
            }
            Text(
                "Tất cả ứng dụng",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CFlipColors.TextPrimary,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                placeholder = { Text("Tìm ứng dụng…") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            SortModePicker(
                mode = settings.sortMode,
                onSelect = { onSettingsChange(settings.copy(sortMode = it)) },
                modifier = Modifier.padding(vertical = 12.dp),
            )
            if (shown.isEmpty()) {
                // UX guideline (ui-ux-pro-max, Search/No Results): a dead
                // end gets a suggestion, never a blank screen.
                EmptyState(
                    Icons.Filled.Search, "Không tìm thấy ứng dụng",
                    "Thử từ khóa khác, ví dụ \"zalo\" hoặc \"cài đặt\".",
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 80.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    itemsIndexed(shown, key = { _, app -> app.packageName }) { index, app ->
                        val isFav = app.packageName in favorites
                        val unread = notifCounts[app.packageName] ?: 0
                        // Stagger entrance — same pattern as the cover grid
                        // (CoverLauncherScreen.kt): keyed per package, so it
                        // replays when the drawer re-opens, not on scroll.
                        val reduced = rememberReducedMotion()
                        val cellAlpha = remember { Animatable(if (reduced) 1f else 0f) }
                        val cellOffsetY = remember { Animatable(if (reduced) 0f else CFlipMotion.EntranceOffsetDp) }
                        LaunchedEffect(Unit) {
                            if (reduced) {
                                cellAlpha.snapTo(1f); cellOffsetY.snapTo(0f)
                            } else {
                                delay((minOf(index, CFlipMotion.StaggerMaxItems) * CFlipMotion.StaggerStepMs).toLong())
                                launch { cellAlpha.animateTo(1f, tween(CFlipMotion.MediumMs)) }
                                launch { cellOffsetY.animateTo(0f, tween(CFlipMotion.MediumMs)) }
                            }
                        }
                        val pressSource = remember { MutableInteractionSource() }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .graphicsLayer {
                                    alpha = cellAlpha.value
                                    translationY = cellOffsetY.value
                                }
                                .pressScale(pressSource)
                                .combinedClickable(
                                    interactionSource = pressSource,
                                    indication = LocalIndication.current,
                                    onClick = {
                                        hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                        AppLauncher.launch(context, app.packageName)
                                    },
                                    onLongClick = {
                                        hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                        menuApp = app
                                    }
                                )
                                .padding(vertical = 4.dp)
                        ) {
                            Box {
                                AppIcon(app.icon, 56)
                                // Unread badge — vc243 pattern (no
                                // AnimatedVisibility inside a Box).
                                val badgeScale by animateFloatAsState(
                                    targetValue = if (unread > 0) 1f else 0.5f,
                                    animationSpec = tween(CFlipMotion.FastMs),
                                    label = "drawerBadgeScale",
                                )
                                val badgeAlpha by animateFloatAsState(
                                    targetValue = if (unread > 0) 1f else 0f,
                                    animationSpec = tween(CFlipMotion.FastMs),
                                    label = "drawerBadgeAlpha",
                                )
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .graphicsLayer {
                                            scaleX = badgeScale; scaleY = badgeScale
                                            alpha = badgeAlpha
                                        }
                                        .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                                        .background(CFlipColors.Danger, CircleShape)
                                        .padding(horizontal = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AnimatedContent(
                                        targetState = unread,
                                        transitionSpec = {
                                            (fadeIn(tween(CFlipMotion.FastMs)) + scaleIn(tween(CFlipMotion.FastMs), initialScale = 0.6f)) togetherWith
                                                (fadeOut(tween(CFlipMotion.FastMs)) + scaleOut(tween(CFlipMotion.FastMs), targetScale = 0.6f))
                                        },
                                        label = "drawerUnreadBadge",
                                    ) { count ->
                                        Text(
                                            if (count > 9) "9+" else count.toString(),
                                            fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                            color = Color.Black,
                                        )
                                    }
                                }
                                if (isFav) {
                                    Box(
                                        modifier = Modifier.align(Alignment.TopEnd)
                                            .size(16.dp)
                                            .background(CFlipColors.Accent, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            Icons.Filled.Star, contentDescription = null,
                                            tint = CFlipColors.AccentDark, modifier = Modifier.size(11.dp),
                                        )
                                    }
                                }
                            }
                            Text(
                                text = app.label,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = CFlipColors.TextPrimary,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }
                }
            }
        }

        // Long-press menu — same actions as the cover's menu
        // (CoverApp.kt): pin/unpin favourite, hide the app.
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
                                appsVersion++
                                menuApp = null
                                toast("Đã ẩn ${app.label}")
                            }) { Text("Ẩn ứng dụng này") }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { menuApp = null }) { Text("Đóng") }
                }
            )
        }
    }
}

/**
 * Shared sort control (hub island + drawer): the same three modes the
 * cover reads. Segmented-row style, copied from the Quick Panel size
 * picker in MainScreen.kt so the two pickers look like one component.
 */
@Composable
fun SortModePicker(mode: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().clip(CircleShape).background(CFlipColors.Bg).padding(4.dp)) {
        for ((m, label) in listOf(0 to "A-Z", 1 to "Mới mở", 2 to "Mới cài")) {
            val selected = mode == m
            Box(
                modifier = Modifier.weight(1f).clip(CircleShape)
                    .background(if (selected) CFlipColors.Accent else Color.Transparent)
                    .clickable { onSelect(m) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = if (selected) CFlipColors.AccentDark else CFlipColors.TextSecondary,
                )
            }
        }
    }
}

/** Strip diacritics so "den pin" also matches "Đèn pin" — the same idea
 * as the cover's unaccented control keywords (CoverApp.kt). Vietnamese
 * "đ" is not a combining mark, so it is folded to "d" by hand. */
private fun String.unaccented(): String =
    java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        .replace('đ', 'd').replace('Đ', 'D')
        .lowercase()
