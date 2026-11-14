package com.cuong.cflip.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.data.AppInfo
import com.cuong.cflip.data.CoverSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The cover app grid (CFlip 2.0 look): centred icon, one-line label,
 * a mint star badge on favourites instead of a text prefix.
 * Tap = launch. Long-press = pin/unpin favourite, or opens the app menu
 * (onAppMenu) when the caller provides one - the menu adds Hide-app.
 * Designed against the real cover sizes: Flip 5/6 = 720x748, Flip 7 = 948x1048.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoverLauncherScreen(
    apps: List<AppInfo>,
    favorites: Set<String>,
    settings: CoverSettings,
    onAppClick: (AppInfo) -> Unit,
    onToggleFavorite: (AppInfo) -> Unit,
    modifier: Modifier = Modifier,
    onAppMenu: ((AppInfo) -> Unit)? = null,
    // vc240: unread count per package, from NotificationState in the
    // caller. Empty map = no badges, so previews and the widget path
    // (which does not pass counts) keep working unchanged.
    notifCounts: Map<String, Int> = emptyMap(),
) {
    // vc240: tap = short tick, long-press = long buzz, so the grid
    // answers the finger before the app even opens.
    // vc241: platform View API (see CoverApp.kt) — Compose's
    // HapticFeedbackType has no VirtualKey in BOM 2024.09.
    val hapticView = LocalView.current
    val columns = if (settings.columns in 3..6) GridCells.Fixed(settings.columns)
        else GridCells.Adaptive(minSize = (settings.iconSizeDp + 28).dp)
    LazyVerticalGrid(
        columns = columns,
        contentPadding = PaddingValues(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            val isFav = app.packageName in favorites
            // vc240: unread badge (FEATURES-V2 #4). Top-start so it never
            // collides with the favourite star at top-end; "9+" caps the
            // width so a flood of notifications cannot stretch the icon.
            val unread = notifCounts[app.packageName] ?: 0
            // vc242 MOTION: staggered entrance — each cell fades in and
            // rises 12dp, 30ms after the previous one (capped at 12 cells
            // so a long list still settles in ~600ms). Keyed per package,
            // so the stagger replays when the tab re-opens but not while
            // scrolling. Reduced motion snaps straight to the end state.
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
            // vc242 MOTION: press scale on the cell, driven by the same
            // interaction source as combinedClickable (tap + long-press).
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
                        onClick = {
                            hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                            onAppClick(app)
                        },
                        onLongClick = {
                            hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            if (onAppMenu != null) onAppMenu(app) else onToggleFavorite(app)
                        }
                    )
                    .padding(vertical = 4.dp)
            ) {
                Box {
                    AppIcon(app.icon, settings.iconSizeDp)
                    // vc242 MOTION: the badge pops in/out (scale + fade)
                    // and the count itself crossfades instead of swapping.
                    AnimatedVisibility(
                        visible = unread > 0,
                        modifier = Modifier.align(Alignment.TopStart),
                        enter = scaleIn(tween(CFlipMotion.FastMs), initialScale = 0.5f) + fadeIn(tween(CFlipMotion.FastMs)),
                        exit = scaleOut(tween(CFlipMotion.FastMs), targetScale = 0.5f) + fadeOut(tween(CFlipMotion.FastMs)),
                    ) {
                        Box(
                            modifier = Modifier
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
                                label = "unreadBadge",
                            ) { count ->
                                Text(if (count > 9) "9+" else count.toString(),
                                    fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    // Danger is a light salmon in this theme,
                                    // so black text reads better than white.
                                    color = androidx.compose.ui.graphics.Color.Black)
                            }
                        }
                    }
                    if (isFav) {
                        Box(
                            modifier = Modifier.align(Alignment.TopEnd)
                                .size(16.dp)
                                .background(CFlipColors.Accent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Star, contentDescription = null,
                                tint = CFlipColors.AccentDark, modifier = Modifier.size(11.dp))
                        }
                    }
                }
                if (settings.showLabels) {
                    Text(
                        text = app.label,
                        fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = CFlipColors.TextPrimary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}
