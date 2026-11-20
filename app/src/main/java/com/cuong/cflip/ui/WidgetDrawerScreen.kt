package com.cuong.cflip.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.data.CoverSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * vc245 WIDGET DRAWER (UI/UX Pro Max) — widget activation on the big
 * screen, same pattern as the other drawers (vc244): top bar + title +
 * staggered rows, no new data sources.
 *
 * Terminology (audited 2026-11-14):
 * - "Kích hoạt Widget" = this drawer. The only widget the app itself
 *   can toggle into the cover Quick Panel is "appgrid" (Lưới ứng dụng),
 *   stored in SettingsStore.coverWidgets. "clock" is added only from
 *   the cover panel itself (CoverControlCenter), and MainScreen still
 *   strips a saved "clock" key (vc135) — so this drawer does NOT offer
 *   a clock toggle that could never stick.
 * - The 4 Samsung cover widgets (AppGrid / Clock / Cover / Music
 *   providers) are NOT toggled here: Samsung's own cover picker adds
 *   them (fold the phone, swipe cover widgets to the end, tap +, pick
 *   CFlip). They are listed below as info rows with the two Samsung
 *   actions, exactly as the old Widget island had them.
 * - No AnimatedVisibility inside a Box (vc243 lesson) and no
 *   FittedBox anywhere. Switches are Material3, tinted mint.
 */
@Composable
fun WidgetDrawerScreen(
    settings: CoverSettings,
    onSettingsChange: (CoverSettings) -> Unit,
    onBack: () -> Unit,
    onOpenGroupEditor: () -> Unit,
) {
    val context = LocalContext.current
    val hapticView = LocalView.current
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    val chosen = settings.coverWidgets.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val appgridOn = "appgrid" in chosen
    fun toggleWidget(key: String, on: Boolean) {
        val next = if (on) chosen + key else chosen - key
        onSettingsChange(settings.copy(coverWidgets = next.sorted().joinToString(",")))
    }

    BackHandler { onBack() }
    Box(
        // vc257 FLAT BACKGROUND: one flat theme colour (was a
        // two-stop vertical gradient) — same as the hub.
        modifier = Modifier.fillMaxSize().background(CFlipColors.Bg)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Top bar: back + status. Title below, like the app drawer.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Quay lại", color = CFlipColors.Accent) }
                Spacer(Modifier.weight(1f))
                Text(
                    if (appgridOn) "Đã bật 1 widget" else "Chưa bật widget nào",
                    fontSize = 13.sp, color = CFlipColors.TextSecondary,
                )
            }
            Text(
                "Widget màn hình ngoài",
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CFlipColors.TextPrimary,
                modifier = Modifier.padding(top = 4.dp),
            )

            var rowIndex = 0
            StaggerRow(index = rowIndex++) {
                SectionHeader("TRONG PANEL MÀN HÌNH NGOÀI")
            }
            StaggerRow(index = rowIndex++) {
                // The one in-app toggle: the app grid inside the cover
                // Quick Panel. Preview is a static mini mock (RemoteViews
                // cannot animate, and the real widget lives on the cover).
                WidgetToggleRow(
                    icon = Icons.Filled.GridView,
                    title = "Lưới ứng dụng",
                    desc = "Hiện lưới app trong panel màn hình ngoài",
                    checked = appgridOn,
                    onChange = {
                        hapticView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        toggleWidget("appgrid", it)
                    },
                ) {
                    // Mini grid preview: 2 rows x 4 cells, icon + label
                    // bar, matching the real widget cells (44dp icon,
                    // 11sp label) at preview scale.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(2) { r ->
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                repeat(4) { c ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(2.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier.size(12.dp).clip(RoundedCornerShape(3.dp))
                                                .background(
                                                    if ((r * 4 + c) % 3 == 0) CFlipColors.Accent.copy(alpha = 0.85f)
                                                    else CFlipColors.CardFill
                                                )
                                        )
                                        Box(
                                            modifier = Modifier.size(width = 10.dp, height = 2.dp)
                                                .clip(RoundedCornerShape(1.dp))
                                                .background(CFlipColors.CardFill)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!appgridOn) {
                StaggerRow(index = rowIndex++) {
                    GlassCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Chưa bật widget nào trong panel",
                                fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = CFlipColors.TextPrimary)
                            Text("Bật Lưới ứng dụng ở trên để panel màn hình ngoài hiện widget khi mở.",
                                fontSize = 12.sp, color = CFlipColors.TextSecondary)
                        }
                    }
                }
            }

            StaggerRow(index = rowIndex++) {
                SectionHeader("WIDGET SAMSUNG")
            }
            StaggerRow(index = rowIndex++) {
                Text("Thêm từ màn hình ngoài: gập máy, vuốt Widget tới cuối, bấm + và chọn CFlip.",
                    fontSize = 12.sp, color = CFlipColors.TextSecondary)
            }
            // The 5 Samsung cover widgets — info rows, not toggles: only
            // Samsung's picker can add them, the app never forces them.
            val samsungWidgets = listOf(
                Triple(Icons.Filled.GridView, "Lưới ứng dụng", "Lưới app 4 cột, mở app ngay từ màn hình ngoài"),
                Triple(Icons.Filled.Schedule, "Đồng hồ", "Giờ lớn, ngày, số thông báo và pin"),
                Triple(Icons.Filled.Widgets, "Widget Cover", "Đồng hồ + app yêu thích + bài đang phát + đèn pin + nhạc"),
                Triple(Icons.Filled.MusicNote, "Nhạc", "Băng cassette: tên bài, nghệ sĩ, tiến độ, phát/dừng"),
                Triple(Icons.Filled.MusicNote, "Nhạc (nhỏ)", "Băng cassette gọn: tên bài + nút phát/dừng"),
            )
            samsungWidgets.forEach { (icon, name, desc) ->
                StaggerRow(index = rowIndex++) {
                    WidgetInfoRow(icon = icon, title = name, desc = desc)
                }
            }
            StaggerRow(index = rowIndex++) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        com.cuong.cflip.widget.SamsungCoverWidgetHelper.openCoverWidgetSettings(context)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Mở Cài đặt Widget màn hình ngoài Samsung",
                            color = CFlipColors.Accent, fontSize = 13.sp)
                    }
                    TextButton(onClick = {
                        toast(com.cuong.cflip.widget.SamsungCoverWidgetHelper.statusText(context))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Kiểm tra Widget màn hình ngoài đã thêm chưa",
                            color = CFlipColors.Accent, fontSize = 13.sp)
                    }
                    TextButton(onClick = onOpenGroupEditor, modifier = Modifier.fillMaxWidth()) {
                        Text("Tùy chỉnh nhóm ứng dụng", color = CFlipColors.Accent, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** One staggered row — same entrance pattern as the app drawer cells. */
@Composable
private fun StaggerRow(index: Int, content: @Composable () -> Unit) {
    val reduced = rememberReducedMotion()
    val alpha = remember { Animatable(if (reduced) 1f else 0f) }
    val offsetY = remember { Animatable(if (reduced) 0f else CFlipMotion.EntranceOffsetDp) }
    LaunchedEffect(Unit) {
        if (reduced) {
            alpha.snapTo(1f); offsetY.snapTo(0f)
        } else {
            delay((minOf(index, CFlipMotion.StaggerMaxItems) * CFlipMotion.StaggerStepMs).toLong())
            launch { alpha.animateTo(1f, tween(CFlipMotion.MediumMs)) }
            launch { offsetY.animateTo(0f, tween(CFlipMotion.MediumMs)) }
        }
    }
    Box(
        modifier = Modifier.fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha.value
                translationY = offsetY.value
            }
    ) { content() }
}

/** A widget row with a live toggle: 48dp+ target, mint switch, press scale. */
@Composable
private fun WidgetToggleRow(
    icon: ImageVector,
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    preview: @Composable () -> Unit,
) {
    val pressSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier.fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CFlipColors.Surface)
            .border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
            .pressScale(pressSource)
            .clickable(interactionSource = pressSource, indication = LocalIndication.current) {
                onChange(!checked)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(CFlipColors.Bg),
                contentAlignment = Alignment.Center,
            ) { preview() }
            Column(modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(icon, contentDescription = null, tint = CFlipColors.Accent,
                        modifier = Modifier.size(18.dp))
                    Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = CFlipColors.TextPrimary)
                }
                Text(desc, fontSize = 12.sp, color = CFlipColors.TextSecondary)
            }
            Switch(
                checked = checked, onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = CFlipColors.AccentDark,
                    checkedTrackColor = CFlipColors.Accent,
                ),
            )
        }
    }
}

/** An info-only widget row (Samsung widgets): no toggle, no tap action. */
@Composable
private fun WidgetInfoRow(icon: ImageVector, title: String, desc: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CFlipColors.Surface)
            .border(1.dp, CFlipColors.CardStroke, RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape)
                .background(CFlipColors.Accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = CFlipColors.Accent,
                modifier = Modifier.size(20.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = CFlipColors.TextPrimary)
            Text(desc, fontSize = 12.sp, color = CFlipColors.TextSecondary)
        }
    }
}
