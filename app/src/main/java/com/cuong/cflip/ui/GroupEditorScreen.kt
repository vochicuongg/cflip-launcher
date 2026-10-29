package com.cuong.cflip.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cuong.cflip.data.AppGroup
import com.cuong.cflip.data.AppGroups
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
 *
 * vc156 (user, 29/10): default groups can be EDITED too (their apps
 * are stored as an override in CustomGroupStore — the group then
 * stops following the auto rules until reset). The member section
 * is now the COVER GROUP VIEW: the same 4-column icon grid you see
 * after tapping a group on the cover screen — tap a tile to select
 * it, ← → moves it, ✕ removes it. The picker is unchanged (apps not
 * yet in the group, tap to add).
 */
@Composable
fun GroupEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var groups by remember { mutableStateOf(CustomGroupStore.load(context)) }
    var hiddenAuto by remember { mutableStateOf(CustomGroupStore.loadHiddenAuto(context)) }
    var overrides by remember { mutableStateOf(CustomGroupStore.loadOverrides(context)) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingAuto by remember { mutableStateOf<AppGroup?>(null) }
    var selectedPkg by remember { mutableStateOf<String?>(null) }
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

    fun persistOverrides(next: Map<String, List<String>>) {
        CustomGroupStore.saveOverrides(context, next)
        overrides = next
        AppGridWidgetProvider.refreshAll(context)
    }

    fun openEditor(g: CustomGroup) {
        editingId = g.id
        editingAuto = null
        selectedPkg = null
        draftTitle = g.title
        draftOrder = g.packages
        search = ""
    }

    /** Effective members of a default group, as panel/widget show them. */
    fun effectiveAutoMembers(ag: AppGroup): List<String> {
        val stored = overrides[ag.name]
        if (stored != null) return stored
        val rest = apps.filter { app -> groups.none { g -> app.packageName in g.packages } }
        return AppGroups.groupedApps(rest).toMap()[ag]?.map { it.packageName }
            ?: emptyList()
    }

    fun openAutoEditor(ag: AppGroup) {
        editingId = null
        editingAuto = ag
        selectedPkg = null
        draftTitle = ag.title
        draftOrder = effectiveAutoMembers(ag)
        search = ""
    }

    fun closeEditor(save: Boolean) {
        val auto = editingAuto
        if (save && auto != null) {
            val draft = draftOrder
            // Single membership, both directions: the saved apps
            // leave every custom group and every OTHER auto override.
            val nextGroups = groups.map { g ->
                g.copy(packages = g.packages.filter { it !in draft })
            }
            val nextOverrides = overrides
                .mapValues { (k, v) -> v.filter { it !in draft } }
                .filterValues { it.isNotEmpty() } + (auto.name to draft)
            persist(nextGroups)
            persistOverrides(nextOverrides)
        } else if (save && editing != null) {
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
            // …and every auto override (an app in a custom group
            // must not stay claimed by an edited default group).
            val nextOverrides = overrides
                .mapValues { (_, v) -> v.filter { it !in draft } }
                .filterValues { it.isNotEmpty() }
            persist(next)
            persistOverrides(nextOverrides)
        } else {
            // Cancel reverts to the stored groups, so a group added
            // in this session but never saved simply disappears.
            groups = CustomGroupStore.load(context)
            overrides = CustomGroupStore.loadOverrides(context)
        }
        editingId = null
        editingAuto = null
        selectedPkg = null
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
        if (editing == null && editingAuto == null) {
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
            // Effective members ignoring the hidden filter, so a
            // deleted default group still previews the icons it
            // would restore — via the same sections() merge the
            // panel and widget use, so the preview never diverges.
            val autoPreview = CustomGroupStore.sections(
                apps, groups, emptySet(), overrides,
            ).first.filter { it.id.startsWith("auto:") }
                .associate { it.id.removePrefix("auto:") to it.members }
                .mapKeys { (k, _) -> AppGroup.values().first { it.name == k } }
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
                    val members = g.packages.mapNotNull { pkg ->
                        apps.firstOrNull { it.packageName == pkg }
                    }
                    val installed = members.size
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GroupIconPreview(members)
                        Spacer(Modifier.width(12.dp))
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
                        "Xóa nhóm mặc định chỉ ẩn nhóm — app trong nhóm vẫn hiện ở cuối, và khôi phục được bất cứ lúc nào. Bấm Sửa để đổi ứng dụng trong nhóm mặc định (tên nhóm không đổi được).",
                        color = CFlipColors.TextSecondary,
                        fontSize = 12.sp,
                    )
                }
                items(AppGroup.values(), key = { "a:" + it.name }) { ag ->
                    val hidden = ag.name in hiddenAuto
                    val members = autoPreview[ag] ?: emptyList()
                    val count = members.size
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GroupIconPreview(members)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(ag.title, color = CFlipColors.TextPrimary, fontSize = 15.sp)
                            Text(
                                if (hidden) "Đã xóa — app vẫn hiện ở cuối"
                                else "$count ứng dụng",
                                color = CFlipColors.TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                        TextButton(onClick = { openAutoEditor(ag) }) {
                            Text("Sửa", color = CFlipColors.Accent, fontSize = 13.sp)
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
            val auto = editingAuto
            if (auto != null) {
                Text(
                    auto.title,
                    color = CFlipColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Nhóm mặc định — chỉ sửa ứng dụng trong nhóm, tên không đổi được.",
                    color = CFlipColors.TextSecondary,
                    fontSize = 12.sp,
                )
                if (auto.name in overrides) {
                    TextButton(onClick = {
                        persistOverrides(overrides - auto.name)
                        // Compute directly: overrides state is stale
                        // in this same click, so effectiveAutoMembers
                        // would return the just-cleared list.
                        val rest = apps.filter { app ->
                            groups.none { g -> app.packageName in g.packages }
                        }
                        draftOrder = AppGroups.groupedApps(rest).toMap()[auto]
                            ?.map { it.packageName } ?: emptyList()
                        selectedPkg = null
                    }) {
                        Text("Đặt lại mặc định", color = CFlipColors.Accent, fontSize = 13.sp)
                    }
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
            }
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
            // vc155 NO DUPLICATES: the picker lists only apps NOT
            // yet in the group. Tapping one moves it into the group
            // section above (it becomes a group app); removing it with
            // ✕ sends it back here. A selected app never appears in
            // both sections at once.
            val query = search.trim()
            val available = apps.filter { it.packageName !in draftOrder }
            val filtered = if (query.isEmpty()) {
                available
            } else {
                available.filter { app ->
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
                        "Ứng dụng trong nhóm (${draftOrder.size}) — bấm một ô để chọn, ← → để đổi vị trí, ✕ để gỡ",
                        color = CFlipColors.TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                item {
                    if (draftOrder.isEmpty()) {
                        Text(
                            "Chưa có ứng dụng nào — bấm + ở mục Thêm ứng dụng bên dưới.",
                            color = CFlipColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    } else {
                        // vc156 COVER GROUP VIEW: the member grid is
                        // the same 4-column icon grid the cover shows
                        // after tapping into a group — tap a tile to
                        // select it, ← → (below) moves it, ✕ removes
                        // it. Keyed remember(pkg) keeps each tile's
                        // icon correct when the order changes.
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (rowPkgs in draftOrder.chunked(4)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    for (pkg in rowPkgs) {
                                        val idx = draftOrder.indexOf(pkg)
                                        val app = apps.firstOrNull { it.packageName == pkg }
                                        val selected = selectedPkg == pkg
                                        Box(
                                            modifier = Modifier.weight(1f)
                                                .background(
                                                    if (selected) CFlipColors.AccentDeep
                                                    else CFlipColors.Surface,
                                                    RoundedCornerShape(12.dp),
                                                )
                                                .border(
                                                    if (selected) BorderStroke(2.dp, CFlipColors.Accent)
                                                    else BorderStroke(1.dp, CFlipColors.CardStroke),
                                                    RoundedCornerShape(12.dp),
                                                )
                                                .clickable {
                                                    selectedPkg = if (selected) null else pkg
                                                }
                                                .padding(6.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Column(
                                                horizontalAlignment = Alignment.CenterHorizontally,
                                            ) {
                                                val img = remember(pkg) {
                                                    app?.icon?.let { drawableBitmap(it, 96) }
                                                }
                                                if (img != null) {
                                                    Image(
                                                        img,
                                                        contentDescription = null,
                                                        modifier = Modifier.size(40.dp),
                                                    )
                                                } else {
                                                    Box(
                                                        modifier = Modifier.size(40.dp),
                                                        contentAlignment = Alignment.Center,
                                                    ) {
                                                        Text("?", color = CFlipColors.TextFaint, fontSize = 20.sp)
                                                    }
                                                }
                                                Spacer(Modifier.height(4.dp))
                                                Text(
                                                    app?.label ?: pkg.substringAfterLast('.'),
                                                    color = CFlipColors.TextPrimary,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Text(
                                                "${idx + 1}",
                                                color = CFlipColors.TextFaint,
                                                fontSize = 10.sp,
                                                modifier = Modifier.align(Alignment.TopStart),
                                            )
                                            Box(
                                                modifier = Modifier.align(Alignment.TopEnd)
                                                    .clickable {
                                                        draftOrder = draftOrder - pkg
                                                        if (selectedPkg == pkg) selectedPkg = null
                                                    }
                                                    .size(20.dp),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                Text("✕", color = CFlipColors.Danger, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    repeat(4 - rowPkgs.size) {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
                val sel = selectedPkg
                if (sel != null && sel in draftOrder) {
                    item {
                        val idx = draftOrder.indexOf(sel)
                        val app = apps.firstOrNull { it.packageName == sel }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Đã chọn: " + (app?.label ?: sel.substringAfterLast('.')),
                                    color = CFlipColors.TextPrimary,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
                                onClick = { move(sel, -1) },
                                enabled = idx > 0,
                            ) {
                                Text("←", color = CFlipColors.Accent, fontSize = 15.sp)
                            }
                            TextButton(
                                onClick = { move(sel, 1) },
                                enabled = idx < draftOrder.size - 1,
                            ) {
                                Text("→", color = CFlipColors.Accent, fontSize = 15.sp)
                            }
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
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            if (query.isEmpty()) "Tất cả ứng dụng đã ở trong nhóm."
                            else "Không tìm thấy ứng dụng nào.",
                            color = CFlipColors.TextSecondary,
                            fontSize = 12.sp,
                        )
                    }
                }
                items(filtered, key = { "p:" + it.packageName }) { app ->
                    val otherGroup = groups.firstOrNull { g ->
                        g.id != editing?.id && app.packageName in g.packages
                    }
                    val otherAuto = if (otherGroup == null) {
                        AppGroup.values().firstOrNull { ag ->
                            ag.name != editingAuto?.name &&
                                app.packageName in (overrides[ag.name] ?: emptyList())
                        }
                    } else null
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { draftOrder = draftOrder + app.packageName }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
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
                            if (otherGroup != null) {
                                Text(
                                    "đang ở “${otherGroup.title}” — bấm để chuyển qua đây",
                                    color = CFlipColors.TextFaint,
                                    fontSize = 11.sp,
                                )
                            } else if (otherAuto != null) {
                                Text(
                                    "đang ở “${otherAuto.title}” — bấm để chuyển qua đây",
                                    color = CFlipColors.TextFaint,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Text(
                            "+",
                            color = CFlipColors.Accent,
                            fontSize = 18.sp,
                            modifier = Modifier.padding(start = 8.dp),
                        )
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

/**
 * vc155 GROUP ICON PREVIEW (user, 29/10): a group's row in the list
 * shows its grouped icons with its name — the same 3x3 folder look
 * as the widget tile, at 48dp so the icons stay recognizable.
 * Empty slots render as blank boxes so the 3x3 shape holds.
 */
@Composable
private fun GroupIconPreview(members: List<AppInfo>) {
    val icons = remember(members) {
        members.take(9).map { app -> app.icon?.let { drawableBitmap(it, 96) } }
    }
    Column(modifier = Modifier.size(48.dp)) {
        for (row in 0 until 3) {
            Row(modifier = Modifier.weight(1f)) {
                for (col in 0 until 3) {
                    val idx = row * 3 + col
                    Box(
                        modifier = Modifier.weight(1f)
                            .fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        val img = icons.getOrNull(idx)
                        if (img != null) {
                            Image(
                                img,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
