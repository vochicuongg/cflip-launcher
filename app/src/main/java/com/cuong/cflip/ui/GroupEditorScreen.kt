package com.cuong.cflip.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.data.AppGroup
import com.cuong.cflip.data.AppInfo
import com.cuong.cflip.data.AppRepository
import com.cuong.cflip.data.CustomGroup
import com.cuong.cflip.data.CustomGroupStore
import com.cuong.cflip.widget.AppGridWidgetProvider

/**
 * vc152 CUSTOM GROUP EDITOR (user, 29/10): the section in the CFlip
 * app where users build their own app groups, like 9Flip's
 * user-defined groups (id + title + package list). A custom group
 * shows first, in both the Quick Panel widget card and the Samsung
 * widget, and its apps leave their auto groups. Deleting a group
 * only ungroups its apps; no app is ever removed or hidden.
 *
 * vc153 (user, 29/10): default groups can be deleted too (hidden,
 * restorable — their apps fall through to ungrouped, never hidden);
 * apps inside a group can be reordered with ↑/↓; the picker has a
 * name search bar; members are listed first as their own section
 * so ordering is done without hunting through the picker.
 */
@Composable
fun GroupEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var groups by remember { mutableStateOf(CustomGroupStore.load(context)) }
    var hiddenAuto by remember { mutableStateOf(CustomGroupStore.loadHiddenAuto(context)) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var draftTitle by remember { mutableStateOf("") }
    var draftOrder by remember { mutableStateOf(listOf<String>()) }
    var search by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }

    LaunchedEffect(Unit) {
        apps = runCatching { AppRepository(context).loadLaunchableApps() }
            .getOrDefault(emptyList())
    }

    val editing = groups.firstOrNull { it.id == editingId }

    fun persist(next: List<CustomGroup>) {
        CustomGroupStore.save(context, next)
        groups = next
        AppGridWidgetProvider.refreshAll(context)
    }

    fun persistHidden(next: Set<String>) {
        CustomGroupStore.saveHiddenAuto(context, next)
        hiddenAuto = next
        AppGridWidgetProvider.refreshAll(context)
    }

    fun openEditor(g: CustomGroup) {
        editingId = g.id
        draftTitle = g.title
        draftOrder = g.packages
        search = ""
    }

    fun closeEditor(save: Boolean) {
        if (save && editing != null) {
            val draft = draftOrder
            val next = groups.map { g ->
                when {
                    g.id == editing.id -> g.copy(
                        title = draftTitle.ifBlank { "Nhóm" },
                        packages = draft,
                    )
                    // Single membership: an app picked here leaves
                    // every other custom group on save.
                    else -> g.copy(packages = g.packages.filter { it !in draft })
                }
            }
            persist(next)
        } else {
            // Cancel reverts to the stored groups, so a group added
            // in this session but never saved simply disappears.
            groups = CustomGroupStore.load(context)
        }
        editingId = null
        search = ""
    }

    fun move(pkg: String, delta: Int) {
        val list = draftOrder.toMutableList()
        val i = list.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        list[i] = list[j]
        list[j] = pkg
        draftOrder = list
    }

    Column(
        modifier = Modifier.fillMaxSize()
            .background(CFlipColors.Bg)
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        TextButton(onClick = onBack, modifier = Modifier.align(Alignment.Start)) {
            Text("← Quay lại", color = CFlipColors.Accent)
        }
        if (editing == null) {
            Text(
                "Nhóm tùy chỉnh",
                color = CFlipColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Nhóm tự tạo hiện trước nhóm tự động, ở cả thẻ Widget trong panel lẫn widget Samsung. App trong nhóm tự tạo sẽ không còn ở nhóm tự động nữa. Xóa nhóm chỉ gỡ nhóm, không xóa app.",
                color = CFlipColors.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            val (sections, _) = CustomGroupStore.sections(apps, groups, hiddenAuto)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item {
                    Text(
                        "Nhóm của bạn",
                        color = CFlipColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(groups, key = { "c:" + it.id }) { g ->
                    val installed = g.packages.count { pkg ->
                        apps.any { it.packageName == pkg }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(g.title, color = CFlipColors.TextPrimary, fontSize = 15.sp)
                            Text(
                                "$installed ứng dụng",
                                color = CFlipColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        TextButton(onClick = { openEditor(g) }) {
                            Text("Sửa", color = CFlipColors.Accent, fontSize = 13.sp)
                        }
                        TextButton(onClick = {
                            persist(groups.filter { it.id != g.id })
                        }) {
                            Text("Xóa", color = CFlipColors.Danger, fontSize = 13.sp)
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Nhóm mặc định",
                        color = CFlipColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Xóa nhóm mặc định chỉ ẩn nhóm — app trong nhóm vẫn hiện ở cuối, và khôi phục được bất cứ lúc nào.",
                        color = CFlipColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                items(AppGroup.values(), key = { "a:" + it.name }) { ag ->
                    val hidden = ag.name in hiddenAuto
                    val count = sections.firstOrNull { s -> s.id == "auto:" + ag.name }
                        ?.members?.size ?: 0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(ag.title, color = CFlipColors.TextPrimary, fontSize = 15.sp)
                            Text(
                                if (hidden) "Đã xóa — app vẫn hiện ở cuối"
                                else "$count ứng dụng",
                                color = CFlipColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        TextButton(onClick = {
                            persistHidden(
                                if (hidden) hiddenAuto - ag.name
                                else hiddenAuto + ag.name
                            )
                        }) {
                            Text(
                                if (hidden) "Khôi phục" else "Xóa",
                                color = if (hidden) CFlipColors.Accent
                                else CFlipColors.Danger,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }
            }
            TextButton(
                onClick = {
                    val n = (1..Int.MAX_VALUE).first { k ->
                        groups.none { it.title == "Nhóm $k" }
                    }
                    val g = CustomGroup(CustomGroupStore.newId(), "Nhóm $n", emptyList())
                    groups = groups + g
                    openEditor(g)
                },
                modifier = Modifier.align(Alignment.Start),
            ) {
                Text("＋ Thêm nhóm", color = CFlipColors.Accent, fontSize = 13.sp)
            }
        } else {
            TextField(
                value = draftTitle,
                onValueChange = { draftTitle = it },
                label = { Text("Tên nhóm") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = CFlipColors.TextPrimary,
                    unfocusedTextColor = CFlipColors.TextPrimary,
                    focusedContainerColor = CFlipColors.Surface,
                    unfocusedContainerColor = CFlipColors.Surface,
                    focusedIndicatorColor = CFlipColors.Accent,
                    unfocusedIndicatorColor = CFlipColors.CardStroke,
                    cursorColor = CFlipColors.Accent,
                ),
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("Tìm tên ứng dụng…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = CFlipColors.TextPrimary,
                    unfocusedTextColor = CFlipColors.TextPrimary,
                    focusedContainerColor = CFlipColors.Surface,
                    unfocusedContainerColor = CFlipColors.Surface,
                    focusedIndicatorColor = CFlipColors.Accent,
                    unfocusedIndicatorColor = CFlipColors.CardStroke,
                    cursorColor = CFlipColors.Accent,
                ),
            )
            Spacer(Modifier.height(8.dp))
            val query = search.trim()
            val filtered = if (query.isEmpty()) {
                apps
            } else {
                apps.filter { app ->
                    app.label.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    Text(
                        "Ứng dụng trong nhóm (${draftOrder.size}) — bấm ↑ ↓ để đổi vị trí",
                        color = CFlipColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(draftOrder, key = { "m:$it" }) { pkg ->
                    val idx = draftOrder.indexOf(pkg)
                    val app = apps.firstOrNull { it.packageName == pkg }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${idx + 1}.",
                            color = CFlipColors.TextFaint,
                            fontSize = 12.sp,
                            modifier = Modifier.width(28.dp),
                        )
                        val img = remember(pkg) {
                            app?.icon?.let { drawableBitmap(it, 96) }
                        }
                        if (img != null) {
                            Image(
                                img,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp)
                                    .padding(end = 8.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                app?.label ?: pkg.substringAfterLast('.'),
                                color = CFlipColors.TextPrimary,
                                fontSize = 14.sp,
                                maxLines = 1,
                            )
                            if (app == null) {
                                Text(
                                    "App đã gỡ — vẫn giữ trong nhóm, cài lại sẽ hiện",
                                    color = CFlipColors.TextFaint,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        TextButton(
                            onClick = { move(pkg, -1) },
                            enabled = idx > 0,
                        ) {
                            Text("↑", color = CFlipColors.Accent, fontSize = 15.sp)
                        }
                        TextButton(
                            onClick = { move(pkg, 1) },
                            enabled = idx < draftOrder.size - 1,
                        ) {
                            Text("↓", color = CFlipColors.Accent, fontSize = 15.sp)
                        }
                        TextButton(onClick = { draftOrder = draftOrder - pkg }) {
                            Text("✕", color = CFlipColors.Danger, fontSize = 13.sp)
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (query.isEmpty()) "Thêm ứng dụng"
                        else "Thêm ứng dụng (${filtered.size} kết quả)",
                        color = CFlipColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(filtered, key = { "p:" + it.packageName }) { app ->
                    val inDraft = app.packageName in draftOrder
                    val otherGroup = groups.firstOrNull { g ->
                        g.id != editing.id && app.packageName in g.packages
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                draftOrder = if (inDraft) {
                                    draftOrder - app.packageName
                                } else {
                                    draftOrder + app.packageName
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (inDraft) "☑" else "☐",
                            color = CFlipColors.Accent,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        val img = remember(app.packageName) {
                            app.icon?.let { drawableBitmap(it, 96) }
                        }
                        if (img != null) {
                            Image(
                                img,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp)
                                    .padding(end = 8.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                app.label,
                                color = CFlipColors.TextPrimary,
                                fontSize = 14.sp,
                                maxLines = 1,
                            )
                            if (otherGroup != null && !inDraft) {
                                Text(
                                    "đang ở “${otherGroup.title}” — bấm để chuyển qua đây",
                                    color = CFlipColors.TextFaint,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { closeEditor(false) }) {
                    Text("Hủy", color = CFlipColors.TextSecondary, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { closeEditor(true) }) {
                    Text("Xong", color = CFlipColors.Accent, fontSize = 13.sp)
                }
            }
        }
    }
}

private fun drawableBitmap(icon: Drawable, size: Int): androidx.compose.ui.graphics.ImageBitmap {
    val copy = icon.constantState?.newDrawable()?.mutate() ?: icon
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    copy.setBounds(0, 0, size, size)
    copy.draw(canvas)
    return bmp.asImageBitmap()
}
