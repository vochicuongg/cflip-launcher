package com.cuong.cflip.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.data.AppInfo
import com.cuong.cflip.data.CoverSettings

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
    val haptic = LocalHapticFeedback.current
    val columns = if (settings.columns in 3..6) GridCells.Fixed(settings.columns)
        else GridCells.Adaptive(minSize = (settings.iconSizeDp + 28).dp)
    LazyVerticalGrid(
        columns = columns,
        contentPadding = PaddingValues(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(apps, key = { it.packageName }) { app ->
            val isFav = app.packageName in favorites
            // vc240: unread badge (FEATURES-V2 #4). Top-start so it never
            // collides with the favourite star at top-end; "9+" caps the
            // width so a flood of notifications cannot stretch the icon.
            val unread = notifCounts[app.packageName] ?: 0
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .combinedClickable(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                            onAppClick(app)
                        },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (onAppMenu != null) onAppMenu(app) else onToggleFavorite(app)
                        }
                    )
                    .padding(vertical = 4.dp)
            ) {
                Box {
                    AppIcon(app.icon, settings.iconSizeDp)
                    if (unread > 0) {
                        Box(
                            modifier = Modifier.align(Alignment.TopStart)
                                .sizeIn(minWidth = 16.dp, minHeight = 16.dp)
                                .background(CFlipColors.Danger, CircleShape)
                                .padding(horizontal = 4.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (unread > 9) "9+" else unread.toString(),
                                fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                // Danger is a light salmon in this theme,
                                // so black text reads better than white.
                                color = androidx.compose.ui.graphics.Color.Black)
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
