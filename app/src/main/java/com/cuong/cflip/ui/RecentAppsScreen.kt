package com.cuong.cflip.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.data.RecentAppsRepository
import com.cuong.cflip.display.AppLauncher

/**
 * vc254 MỞ GẦN ĐÂY (the reference app "recently opened" pattern, clean-room):
 * the apps the user actually opened lately, newest first — tap to
 * launch from the big screen without opening the cover or digging
 * through a drawer. Loads once per open (the screen is recreated
 * by MainScreen's early return each time it opens).
 * Usage Access is optional: without it the list only covers apps
 * launched from CFlip, and the screen says so instead of looking
 * empty — the same caveat SettingsScreen's QUYỀN section already
 * states ("cho mục Gần đây thật").
 */
@Composable
fun RecentAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val hapticView = LocalView.current
    val recentRepo = remember { RecentAppsRepository(context) }
    val apps = remember {
        val byPackage = AppRepository(context).loadLaunchableApps()
            .associateBy { it.packageName }
        recentRepo.recentPackages(8).mapNotNull { byPackage[it] }
    }
    val hasUsageAccess = remember { recentRepo.hasUsageAccess() }
    BackHandler { onBack() }
    Box(
        // vc257 FLAT BACKGROUND: one flat theme colour (was a
        // two-stop vertical gradient) — same as the hub.
        modifier = Modifier.fillMaxSize().background(CFlipColors.Bg)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("← Quay lại", color = CFlipColors.Accent)
                }
                Spacer(Modifier.weight(1f))
                Text("${apps.size} ứng dụng", fontSize = 13.sp, color = CFlipColors.TextSecondary)
            }
            Text(
                "Mở gần đây",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CFlipColors.TextPrimary,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            if (apps.isEmpty()) {
                // UX guideline (ui-ux-pro-max, Search/No Results): a dead
                // end gets a suggestion, never a blank screen.
                EmptyState(
                    Icons.Filled.History, "Chưa có ứng dụng gần đây",
                    "Mở vài ứng dụng rồi quay lại đây.",
                )
            } else {
                if (!hasUsageAccess) {
                    Text(
                        "Chưa cấp Usage Access — chỉ hiện ứng dụng mở từ CFlip. Cấp quyền trong mục Cá nhân hóa để xem đầy đủ.",
                        fontSize = 12.sp, color = CFlipColors.TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        GlassCard(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                                AppLauncher.launch(context, app.packageName)
                            },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                AppIcon(app.icon, 40)
                                Text(
                                    app.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                    color = CFlipColors.TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text("›", fontSize = 20.sp, color = CFlipColors.TextFaint)
                            }
                        }
                    }
                }
            }
        }
    }
}
