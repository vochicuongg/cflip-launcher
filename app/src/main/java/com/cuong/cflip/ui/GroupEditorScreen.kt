package com.cuong.cflip.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
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
    var autoOrder by remember { mutableStateOf(CustomGroupStore.loadAutoOrder(context)) }
    // vc160 UNIFIED ORDER: one list for custom + default groups.
    // Migrated once from the old two orders (custom list order,
    // then auto order); afterwards the list view, panel and widget
    // all read this list, so a group can sit anywhere.
    var unified by remember {
        mutableStateOf(
            run {
                val stored = CustomGroupStore.loadUnifiedOrder(context)
                if (stored.isNotEmpty()) stored
                else {
                    val built = groups.map { "c:" + it.id } +
                        CustomGroupStore.orderedAutoGroups(autoOrder).map { "a:" + it.name }
                    if (built.isNotEmpty()) CustomGroupStore.saveUnifiedOrder(context, built)
                    built
                }
            }
        )
    }
    // vc163 DELETED SECTIONS, vc166 TRASH PAGE: every deleted
    // group (custom + default) lives in the trash page
    // (restorable); Xóa hẳn there purges completely — only the
    // tombstone stays, so a permanently-deleted group never
    // resurrects. sections() skips deleted groups, so their apps
    // fall back to auto groups / ungrouped.
    var deletedSections by remember { mutableStateOf(CustomGroupStore.loadDeletedSections(context)) }
    // Display order: unified minus stale entries and truly
    // deleted defaults, plus any group the list forgot
    // (defensive — creation always appends, so this only fires
    // on a stale/foreign state). Deleted customs STAY in the
    // list as Restore rows.
    val displayEntries: List<String> = run {
        val list = unified.filter { e ->
            (e.startsWith("c:") && groups.any { it.id == e.removePrefix("c:") } &&
                "custom:" + e.removePrefix("c:") !in deletedSections) ||
                (e.startsWith("a:") && AppGroup.values().any { it.name == e.removePrefix("a:") } &&
                    "auto:" + e.removePrefix("a:") !in deletedSections)
        }.toMutableList()
        for (g in groups) {
            val e = "c:" + g.id
            if (e !in list && "custom:" + g.id !in deletedSections) list.add(e)
        }
        for (ag in CustomGroupStore.orderedAutoGroups(autoOrder)) {
            val e = "a:" + ag.name
            if (e !in list && "auto:" + ag.name !in deletedSections) list.add(e)
        }
        list
    }
    // Trash entries: EVERY deleted group (custom + default) in
    // unified order, so Restore puts each back exactly where it
    // was, plus any deleted group the order forgot (defensive).
    // Entries are "c:<id>" / "a:<NAME>", like displayEntries.
    val trashEntries: List<String> = run {
        val list = unified.filter { e ->
            (e.startsWith("c:") && groups.any { it.id == e.removePrefix("c:") } &&
                "custom:" + e.removePrefix("c:") in deletedSections) ||
                (e.startsWith("a:") && AppGroup.values().any { it.name == e.removePrefix("a:") } &&
                    "auto:" + e.removePrefix("a:") in deletedSections)
        }.toMutableList()
        for (g in groups) {
            val e = "c:" + g.id
            if (e !in list && "custom:" + g.id in deletedSections) list.add(e)
        }
        for (ag in AppGroup.values()) {
            val e = "a:" + ag.name
            if (e !in list && "auto:" + ag.name in deletedSections) list.add(e)
        }
        list
    }
    // Ids of soft-deleted customs, for membership logic that must
    // ignore them (their apps are free again).
    val deletedCustomIds = deletedSections
        .filter { it.startsWith("custom:") }
        .map { it.removePrefix("custom:") }.toSet()
    // vc163 SCROLL: hoisted above the list/editor switch, so
    // Back from the member editor returns to the list's old scroll
    // position instead of the top (same bug class as MainScreen's
    // settings scroll, fixed the same way).
    val listState = rememberLazyListState()
    // vc166 TRASH PAGE: tapping Thùng rác opens a full page like
    // the member editor (back returns to the list), instead of
    // the vc165 expandable zone below the footer.
    var showTrashPage by remember { mutableStateOf(false) }
    // vc157 drag state (member grid long-press drag)
    var dragPkg by remember { mutableStateOf<String?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    var dragTarget by remember { mutableStateOf<Int?>(null) }
    var gridWidthPx by remember { mutableStateOf(0) }
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

    fun persistDeleted(next: Set<String>) {
        CustomGroupStore.saveDeletedSections(context, next)
        deletedSections = next
        AppGridWidgetProvider.refreshAll(context)
    }

    fun persistUnified(next: List<String>) {
        CustomGroupStore.saveUnifiedOrder(context, next)
        unified = next
        AppGridWidgetProvider.refreshAll(context)
    }

    /** Move a group entry anywhere in the unified list — across
     * the old custom/default boundary too. Saving the moved list
     * also purges any stale entry, since displayEntries is the
     * source of truth for what gets saved. */
    fun moveUnified(entry: String, delta: Int) {
        val list = displayEntries.toMutableList()
        val i = list.indexOf(entry)
        val j = i + delta
        if (i < 0 || j < 0 || j >= list.size) return
        val e = list.removeAt(i)
        list.add(j, e)
        persistUnified(list)
    }

    fun clearDrag() {
        dragPkg = null
        dragOffset = Offset.Zero
        dragTarget = null
    }

    fun openEditor(g: CustomGroup) {
        editingId = g.id
        editingAuto = null
        selectedPkg = null
        clearDrag()
        draftTitle = g.title
        draftOrder = g.packages
        search = ""
    }

    /** Effective members of a default group, as panel/widget show them. */
    fun effectiveAutoMembers(ag: AppGroup): List<String> {
        val stored = overrides[ag.name]
        if (stored != null) return stored
        val rest = apps.filter { app -> groups.none { g -> g.id !in deletedCustomIds && app.packageName in g.packages } }
        return AppGroups.groupedApps(rest).toMap()[ag]?.map { it.packageName }
            ?: emptyList()
    }

    fun openAutoEditor(ag: AppGroup) {
        editingId = null
        editingAuto = ag
        selectedPkg = null
        clearDrag()
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
                if (g.id in deletedCustomIds) g
                else g.copy(packages = g.packages.filter { it !in draft })
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
                    g.id in deletedCustomIds -> g
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
            // A group created in this session joins unified in
            // memory at creation; its entry is saved here with it.
            persistUnified(unified)
        } else {
            // Cancel reverts to the stored groups, so a group added
            // in this session but never saved simply disappears.
            groups = CustomGroupStore.load(context)
            overrides = CustomGroupStore.loadOverrides(context)
            unified = CustomGroupStore.loadUnifiedOrder(context)
            deletedSections = CustomGroupStore.loadDeletedSections(context)
        }
        editingId = null
        editingAuto = null
        selectedPkg = null
        clearDrag()
        search = ""
    }

    // vc161 BACK: inside the member editor, back goes to the
    // group list (draft discarded, like Hủy) instead of exiting
    // to settings. In the list view this handler is disabled, so
    // MainScreen's handler exits to settings as before. vc166:
    // the trash page counts as "inside" too (back -> list).
    val inEditor = editing != null || editingAuto != null
    BackHandler(enabled = inEditor || showTrashPage) {
        if (showTrashPage) showTrashPage = false else closeEditor(false)
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
        TextButton(
            onClick = {
                if (showTrashPage) showTrashPage = false
                else if (inEditor) closeEditor(false)
                else onBack()
            },
            modifier = Modifier.align(Alignment.Start),
        ) {
            Text("← Quay lại", color = CFlipColors.Accent)
        }
        if (showTrashPage) {
            Text(
                "Thùng rác",
                color = CFlipColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            // Preview members ignoring the deleted filter, so a
            // trashed default shows what restoring would bring
            // back (its override, or its computed members).
            val trashPreview = CustomGroupStore.sections(
                apps, groups, emptySet(), overrides, autoOrder, unified,
            ).first.associateBy { it.id }
            if (trashEntries.isEmpty()) {
                Text(
                    "Chưa có nhóm nào trong thùng rác.",
                    color = CFlipColors.TextSecondary,
                    fontSize = 12.sp,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(trashEntries, key = { it }) { entry ->
                        val customGroup = if (entry.startsWith("c:")) {
                            groups.firstOrNull { it.id == entry.removePrefix("c:") }
                        } else null
                        val autoGroup = if (entry.startsWith("a:")) {
                            AppGroup.values().firstOrNull { it.name == entry.removePrefix("a:") }
                        } else null
                        if (customGroup != null) {
                            val g = customGroup
                            val members = g.packages.mapNotNull { pkg ->
                                apps.firstOrNull { it.packageName == pkg }
                            }
                            val installed = members.size
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GroupIconPreview(members)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            g.title,
                                            color = CFlipColors.TextPrimary,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "Đã xóa — $installed ứng dụng",
                                            color = CFlipColors.TextSecondary,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    TextButton(onClick = {
                                        persistDeleted(deletedSections - ("custom:" + g.id))
                                    }) {
                                        Text("Khôi phục", color = CFlipColors.Accent, fontSize = 13.sp)
                                    }
                                    TextButton(onClick = {
                                        persist(groups.filter { it.id != g.id })
                                        persistUnified(unified.filter { it != entry })
                                        persistDeleted(deletedSections - ("custom:" + g.id))
                                    }) {
                                        Text("Xóa hẳn", color = CFlipColors.Danger, fontSize = 13.sp)
                                    }
                                }
                                HorizontalDivider(color = CFlipColors.CardStroke, thickness = 1.dp)
                            }
                        } else if (autoGroup != null) {
                            val ag = autoGroup
                            val members = trashPreview["auto:" + ag.name]?.members ?: emptyList()
                            val count = members.size
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    GroupIconPreview(members)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            ag.title,
                                            color = CFlipColors.TextPrimary,
                                            fontSize = 15.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "Đã xóa — $count ứng dụng",
                                            color = CFlipColors.TextSecondary,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    TextButton(onClick = {
                                        persistDeleted(deletedSections - ("auto:" + ag.name))
                                    }) {
                                        Text("Khôi phục", color = CFlipColors.Accent, fontSize = 13.sp)
                                    }
                                    TextButton(onClick = {
                                        // vc164 COMPLETE DELETE, now
                                        // from trash: purge order,
                                        // override and hidden flag;
                                        // only the tombstone stays.
                                        persistDeleted(deletedSections - ("auto:" + ag.name))
                                        persistUnified(unified.filter { it != entry })
                                        persistOverrides(overrides - ag.name)
                                        persistHidden(hiddenAuto - ag.name)
                                    }) {
                                        Text("Xóa hẳn", color = CFlipColors.Danger, fontSize = 13.sp)
                                    }
                                }
                                HorizontalDivider(color = CFlipColors.CardStroke, thickness = 1.dp)
                            }
                        }
                    }
                }
            }
        } else if (editing == null && editingAuto == null) {
            Text(
                "Nhóm tùy chỉnh",
                color = CFlipColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Thứ tự này hiện ở cả panel lẫn widget Samsung. Bấm ↑ ↓ để đổi vị trí; xóa nhóm không xóa app.",
                color = CFlipColors.TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            // Effective members ignoring the hidden filter, so a
            // deleted default group still previews the icons it
            // would restore — via the same sections() merge the
            // panel and widget use, so the preview never diverges.
            val sectionsMap = CustomGroupStore.sections(
                apps, groups, emptySet(), overrides, autoOrder, unified, deletedSections,
            ).first.associateBy { it.id }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(displayEntries, key = { _, e -> e }) { idx, entry ->
                    val customGroup = if (entry.startsWith("c:")) {
                        groups.firstOrNull { it.id == entry.removePrefix("c:") }
                    } else null
                    val autoGroup = if (entry.startsWith("a:")) {
                        AppGroup.values().firstOrNull { it.name == entry.removePrefix("a:") }
                    } else null
                    if (customGroup != null) {
                        val g = customGroup
                        val members = g.packages.mapNotNull { pkg ->
                            apps.firstOrNull { it.packageName == pkg }
                        }
                        val installed = members.size
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GroupIconPreview(members)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        g.title,
                                        color = CFlipColors.TextPrimary,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "$installed ứng dụng",
                                        color = CFlipColors.TextSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                                OrderArrow("↑", idx > 0) { moveUnified(entry, -1) }
                                OrderArrow("↓", idx < displayEntries.size - 1) { moveUnified(entry, 1) }
                                TextButton(onClick = { openEditor(g) }) {
                                    Text("Sửa", color = CFlipColors.Accent, fontSize = 13.sp)
                                }
                                TextButton(onClick = {
                                    // Soft-delete: the row leaves the
                                    // main list for the trash page;
                                    // open it so the move is visible.
                                    persistDeleted(deletedSections + ("custom:" + g.id))
                                    showTrashPage = true
                                }) {
                                    Text("Xóa", color = CFlipColors.Danger, fontSize = 13.sp)
                                }
                            }
                            HorizontalDivider(color = CFlipColors.CardStroke, thickness = 1.dp)
                        }
                    } else if (autoGroup != null) {
                        val ag = autoGroup
                        val hidden = ag.name in hiddenAuto
                        val members = sectionsMap["auto:" + ag.name]?.members ?: emptyList()
                        val count = members.size
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                GroupIconPreview(members)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        ag.title,
                                        color = CFlipColors.TextPrimary,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        if (hidden) "Đã xóa — app vẫn hiện ở cuối"
                                        else "$count ứng dụng · Mặc định",
                                        color = CFlipColors.TextSecondary,
                                        fontSize = 12.sp,
                                    )
                                }
                                OrderArrow("↑", idx > 0) { moveUnified(entry, -1) }
                                OrderArrow("↓", idx < displayEntries.size - 1) { moveUnified(entry, 1) }
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
                                        if (hidden) "Khôi phục" else "Ẩn",
                                        color = CFlipColors.Accent,
                                        fontSize = 13.sp,
                                    )
                                }
                                TextButton(onClick = {
                                    // vc166: ANY deleted group goes to
                                    // trash (this supersedes vc164's
                                    // complete-delete here — the purge
                                    // now lives on the trash page's
                                    // Xóa hẳn). Order, override and
                                    // hidden flag stay, so Restore is
                                    // exact.
                                    persistDeleted(deletedSections + ("auto:" + ag.name))
                                    showTrashPage = true
                                }) {
                                    Text("Xóa", color = CFlipColors.Danger, fontSize = 13.sp)
                                }
                            }
                            HorizontalDivider(color = CFlipColors.CardStroke, thickness = 1.dp)
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = {
                        val n = (1..Int.MAX_VALUE).first { k ->
                            groups.none { it.title == "Nhóm $k" }
                        }
                    val g = CustomGroup(CustomGroupStore.newId(), "Nhóm $n", emptyList())
                    val entry = "c:" + g.id
                    // New custom groups join the custom block: right
                    // after the last custom entry, or at the top if
                    // there is no custom entry yet. Saved together
                    // with the group on Xong; Hủy drops both.
                    val at = unified.indexOfLast { it.startsWith("c:") }
                    unified = if (at >= 0) {
                        unified.toMutableList().also { it.add(at + 1, entry) }
                    } else {
                        listOf(entry) + unified
                    }
                        groups = groups + g
                        openEditor(g)
                    },
                ) {
                    Text("＋ Thêm nhóm", color = CFlipColors.Accent, fontSize = 13.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showTrashPage = true }) {
                    Text("🗑 Thùng rác", color = CFlipColors.Danger, fontSize = 13.sp)
                }
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
                            groups.none { g -> g.id !in deletedCustomIds && app.packageName in g.packages }
                        }
                        draftOrder = AppGroups.groupedApps(rest).toMap()[auto]
                            ?.map { it.packageName } ?: emptyList()
                        selectedPkg = null
                    }) {
                        Text("Đặt lại mặc định", color = CFlipColors.Accent, fontSize = 13.sp)
                    }
                }
            } else {
            // vc157 SYSTEM INPUT: a rounded filled box with hint text
            // (One UI style) — no Material label, no underline.
            TextField(
                value = draftTitle,
                onValueChange = { draftTitle = it },
                placeholder = { Text("Tên nhóm", color = CFlipColors.TextFaint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = CFlipColors.TextPrimary,
                    unfocusedTextColor = CFlipColors.TextPrimary,
                    focusedContainerColor = CFlipColors.Surface,
                    unfocusedContainerColor = CFlipColors.Surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedPlaceholderColor = CFlipColors.TextFaint,
                    unfocusedPlaceholderColor = CFlipColors.TextFaint,
                    cursorColor = CFlipColors.Accent,
                ),
            )
            }
            Spacer(Modifier.height(8.dp))
            TextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("Tìm tên ứng dụng…", color = CFlipColors.TextFaint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedTextColor = CFlipColors.TextPrimary,
                    unfocusedTextColor = CFlipColors.TextPrimary,
                    focusedContainerColor = CFlipColors.Surface,
                    unfocusedContainerColor = CFlipColors.Surface,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedPlaceholderColor = CFlipColors.TextFaint,
                    unfocusedPlaceholderColor = CFlipColors.TextFaint,
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
                        "Ứng dụng trong nhóm (${draftOrder.size}) — giữ để kéo đổi vị trí, bấm một ô để chọn, ✕ để gỡ",
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
                        // vc157 DRAG GEOMETRY: fixed 96dp tiles (panel
                        // button height) so a pointer position maps
                        // to a slot: column from x, row from y.
                        val density = LocalDensity.current
                        val dragSpacingPx = with(density) { 8.dp.toPx() }
                        val dragRowHPx = with(density) { 96.dp.toPx() } + dragSpacingPx
                        // Tile UNDER the pointer (floor slot) — only
                        // used to identify which tile a long-press
                        // grabbed. The DROP position uses insertion
                        // below, never this slot.
                        fun dragSlotAt(pos: Offset): Int? {
                            if (gridWidthPx <= 0 || draftOrder.isEmpty()) return null
                            val tileW = (gridWidthPx - 3 * dragSpacingPx) / 4f
                            val col = (pos.x / (tileW + dragSpacingPx)).toInt().coerceIn(0, 3)
                            val rows = (draftOrder.size + 3) / 4
                            val row = (pos.y / dragRowHPx).toInt().coerceIn(0, rows - 1)
                            return (row * 4 + col).coerceIn(0, draftOrder.size - 1)
                        }
                        // vc160 EXACT DROP RULE (user, 29/10): the drop
                        // position is an INSERTION index (0..size),
                        // counted from tile CENTERS in row-major
                        // order — the number of tiles whose center
                        // the pointer has passed. Consequences, all
                        // exact: held in the gap between two tiles
                        // (every point of the gap is past the left
                        // tile's center and before the right tile's)
                        // lands exactly BETWEEN them; held over a
                        // tile's left half lands before it, over its
                        // right half lands after it; past the last
                        // tile lands last. The old floor-slot rule
                        // mapped the whole gap to the left tile,
                        // which landed a backward drag one slot too
                        // early (e.g. dragging app 4 into the gap
                        // between apps 2 and 3 landed it between 1
                        // and 2 instead of between 2 and 3).
                        fun dragInsertionAt(pos: Offset): Int? {
                            if (gridWidthPx <= 0 || draftOrder.isEmpty()) return null
                            val tileW = (gridWidthPx - 3 * dragSpacingPx) / 4f
                            val pitch = tileW + dragSpacingPx
                            val rows = (draftOrder.size + 3) / 4
                            val row = (pos.y / dragRowHPx).toInt().coerceIn(0, rows - 1)
                            var colIns = 0
                            for (c in 0 until 4) {
                                if (pos.x > c * pitch + tileW / 2f) colIns = c + 1 else break
                            }
                            return (row * 4 + colIns).coerceIn(0, draftOrder.size)
                        }
                        // vc156 COVER GROUP VIEW: the member grid is
                        // the same 4-column icon grid the cover shows
                        // after tapping into a group — tap a tile to
                        // select it, ← → (below) moves it, ✕ removes
                        // it. Keyed remember(pkg) keeps each tile's
                        // icon correct when the order changes.
                        Column(
                            modifier = Modifier.onSizeChanged { gridWidthPx = it.width }
                                .pointerInput(draftOrder, gridWidthPx) {
                                    // Gesture-local pkg: the block's
                                    // state reads would be stale
                                    // mid-gesture, so the in-flight
                                    // drag lives in locals; only the
                                    // visuals go through state.
                                    var gesturePkg: String? = null
                                    var gestureStart = Offset.Zero
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { pos ->
                                            val slot = dragSlotAt(pos)
                                            if (slot != null) {
                                                gesturePkg = draftOrder[slot]
                                                gestureStart = pos
                                                dragPkg = gesturePkg
                                                dragStart = pos
                                                dragOffset = Offset.Zero
                                                dragTarget = dragInsertionAt(pos)
                                            }
                                        },
                                        onDrag = { change, _ ->
                                            if (gesturePkg != null) {
                                                dragOffset = change.position - gestureStart
                                                dragTarget = dragInsertionAt(change.position)
                                            }
                                        },
                                        onDragEnd = {
                                            val pkg = gesturePkg
                                            val ins = dragTarget
                                            if (pkg != null && ins != null) {
                                                val cur = draftOrder.indexOf(pkg)
                                                if (cur >= 0) {
                                                    // Insertion was counted with the
                                                    // dragged item still in the list:
                                                    // an insertion past it shifts down
                                                    // by one once it is removed.
                                                    // ins == cur or cur+1 both mean
                                                    // "stay" (its own slot / just
                                                    // after itself) — no move.
                                                    val adjusted = if (ins > cur) ins - 1 else ins
                                                    if (adjusted != cur) {
                                                        val list = draftOrder.toMutableList()
                                                        list.removeAt(cur)
                                                        list.add(adjusted.coerceIn(0, list.size), pkg)
                                                        draftOrder = list
                                                    }
                                                }
                                            }
                                            gesturePkg = null
                                            clearDrag()
                                        },
                                        onDragCancel = {
                                            gesturePkg = null
                                            clearDrag()
                                        },
                                    )
                                },
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (rowPkgs in draftOrder.chunked(4)) {
                                Row(
                                    modifier = Modifier.zIndex(
                                        if (rowPkgs.contains(dragPkg)) 1f else 0f
                                    ),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    for (pkg in rowPkgs) {
                                        val idx = draftOrder.indexOf(pkg)
                                        val app = apps.firstOrNull { it.packageName == pkg }
                                        val selected = selectedPkg == pkg
                                        val isDragged = dragPkg == pkg
                                        // vc160: with insertion semantics, the
                                        // highlighted tile is the one the dragged
                                        // app will land BEFORE (none when it lands
                                        // last — the finger past the last tile says
                                        // that already).
                                        val isTarget = dragTarget == idx && !isDragged
                                        Box(
                                            modifier = Modifier.weight(1f)
                                                .height(96.dp)
                                                .zIndex(if (isDragged) 1f else 0f)
                                                .graphicsLayer {
                                                    if (isDragged) {
                                                        translationX = dragOffset.x
                                                        translationY = dragOffset.y
                                                        scaleX = 1.06f
                                                        scaleY = 1.06f
                                                        shadowElevation = 8.dp.toPx()
                                                    }
                                                }
                                                .background(
                                                    if (isDragged || selected) CFlipColors.AccentDeep
                                                    else CFlipColors.Surface,
                                                    RoundedCornerShape(12.dp),
                                                )
                                                .border(
                                                    if (isDragged || isTarget || selected) BorderStroke(2.dp, CFlipColors.Accent)
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
                        g.id != editing?.id && g.id !in deletedCustomIds &&
                            app.packageName in g.packages
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

/**
 * vc157 ORDER ARROW: slim ↑/↓ control for reordering groups in the
 * list. A TextButton would force a wide min width and crowd the
 * row; a plain Text with a tap area stays slim. Disabled arrows
 * render faint and ignore taps.
 */
@Composable
private fun OrderArrow(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) CFlipColors.Accent else CFlipColors.TextFaint,
        fontSize = 16.sp,
        modifier = Modifier
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 6.dp, vertical = 12.dp),
    )
}
