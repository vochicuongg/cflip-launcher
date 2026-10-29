package com.cuong.cflip.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * vc152 CUSTOM GROUPS (user, 29/10).
 *
 * Mechanism modelled on 9Flip's CoverFloatingMenuGroupDetail, learned
 * from the v149 decompile: a user-defined group is an id + a title +
 * a list of package names, stored persistently and shown as a folder
 * in the app grid (9Flip also has a built-in "special:favorites"
 * group and per-widget open state). This is CFlip's own clean-room
 * version: its own store, its own editor screen, its own merge rules.
 * No 9Flip code, strings, or UI is copied here.
 *
 * Merge rules (sections()): custom groups first, in creation order,
 * then the auto groups (AppGroups) for the apps that are left, then
 * the ungrouped apps. An app lives in AT MOST ONE custom group: if
 * a save puts it in two, the first group (creation order) wins, so
 * no app ever appears twice. A custom group with no installed member
 * is skipped by sections() (the editor still lists it, so the user
 * can refill or delete it).
 */
data class CustomGroup(val id: String, val title: String, val packages: List<String>)

/** One rendered group: a stable id, a display title, its members. */
data class GroupSection(val id: String, val title: String, val members: List<AppInfo>)

object CustomGroupStore {
    private const val PREFS = "custom_groups"
    private const val KEY = "groups_json"
    private const val KEY_HIDDEN_AUTO = "hidden_auto"
    private const val KEY_OVERRIDES = "auto_overrides"
    private const val KEY_AUTO_ORDER = "auto_order"
    private const val KEY_GROUP_ORDER = "group_order"
    private const val KEY_DELETED_SECTIONS = "deleted_sections"

    fun load(context: Context): List<CustomGroup> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                val pkgs = o.optJSONArray("packages") ?: JSONArray()
                CustomGroup(
                    id = o.optString("id"),
                    title = o.optString("title"),
                    packages = List(pkgs.length()) { j -> pkgs.optString(j) }
                        .filter { it.isNotEmpty() },
                )
            }.filter { it.id.isNotEmpty() }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, groups: List<CustomGroup>) {
        val arr = JSONArray()
        for (g in groups) {
            val o = JSONObject()
            o.put("id", g.id)
            o.put("title", g.title)
            val pkgs = JSONArray()
            for (p in g.packages) pkgs.put(p)
            o.put("packages", pkgs)
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun newId(): String = java.util.UUID.randomUUID().toString()

    /**
     * vc153 DELETE DEFAULT GROUPS (user, 29/10): auto groups are
     * computed, so "deleting" one only hides it — its apps fall
     * through to ungrouped (never hidden) and it can be restored
     * any time from the editor's default-groups section.
     */
    fun loadHiddenAuto(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN_AUTO, null)?.toSet() ?: emptySet()
    }

    fun saveHiddenAuto(context: Context, hidden: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_HIDDEN_AUTO, hidden).apply()
    }

    /**
     * vc156 EDIT DEFAULT GROUPS (user, 29/10): a default group the
     * user edited stops following the auto rules — its membership
     * becomes exactly the stored package list (in the user's order).
     * Stored as one JSON object: group name -> package array. An
     * entry that becomes empty by stripping is dropped by callers,
     * so a group reverts to auto rules; an entry saved empty from
     * the editor is kept, so an emptied group stays empty.
     */
    fun loadOverrides(context: Context): Map<String, List<String>> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OVERRIDES, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            val out = mutableMapOf<String, List<String>>()
            for (key in o.keys()) {
                val arr = o.optJSONArray(key) ?: continue
                val pkgs = List(arr.length()) { j -> arr.optString(j) }
                    .filter { it.isNotEmpty() }
                if (key.isNotEmpty()) out[key] = pkgs
            }
            out.toMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * vc157 GROUP ORDER (user, 29/10): the display order of the
     * default groups, stored as one comma-joined string of group
     * names. Empty/missing = enum order. Groups added later (or
     * dropped from the stored string) append in enum order, so a
     * stale string never loses a group.
     */
    fun loadAutoOrder(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_AUTO_ORDER, null) ?: return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveAutoOrder(context: Context, order: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUTO_ORDER, order.joinToString(",")).apply()
    }

    /**
     * vc160 UNIFIED GROUP ORDER (user, 29/10): ONE display order
     * for custom and default groups together, so a group can move
     * to ANY position — including across the old custom/default
     * boundary. Entries are "c:<custom-id>" or "a:<GROUP-NAME>",
     * stored as one JSON array. Empty = legacy behaviour (custom
     * in list order, then auto in autoOrder) until the editor
     * migrates it once; sections() always appends any group the
     * list forgot, so no group can ever disappear from the order.
     */
    fun loadUnifiedOrder(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_GROUP_ORDER, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i -> arr.optString(i) }
                .filter { it.startsWith("c:") || it.startsWith("a:") }
                .filter { it.length > 2 }
        }.getOrDefault(emptyList())
    }

    fun saveUnifiedOrder(context: Context, order: List<String>) {
        val arr = JSONArray()
        for (e in order) arr.put(e)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_GROUP_ORDER, arr.toString()).apply()
    }

    /**
     * vc163 DELETED SECTIONS: one StringSet of section ids
     * ("custom:<id>" / "auto:<NAME>") for groups the user removed
     * from display. vc166: every deleted group lives in the trash
     * page (restorable); Xóa hẳn there purges its order entry,
     * override and hidden flag — only this tombstone stays, so a
     * permanently-deleted group never resurrects. sections()
     * skips any id in this set, so a deleted group's apps fall
     * back to auto groups / ungrouped exactly like a hidden
     * group's do.
     */
    fun loadDeletedSections(context: Context): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_DELETED_SECTIONS, null) ?: return emptySet()
        return raw.filter { it.startsWith("custom:") || it.startsWith("auto:") }
            .filter { it.length > 7 }.toSet()
    }

    fun saveDeletedSections(context: Context, sections: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_DELETED_SECTIONS, sections).apply()
    }

    /** Stored order first (valid names only), then any missing group in enum order. */
    fun orderedAutoGroups(autoOrder: List<String>): List<AppGroup> {
        val valid = AppGroup.values().map { it.name }.toSet()
        val stored = autoOrder.filter { it in valid }
        return (stored + AppGroup.values().map { it.name }.filter { it !in stored })
            .map { AppGroup.valueOf(it) }
    }

    fun saveOverrides(context: Context, overrides: Map<String, List<String>>) {
        val o = JSONObject()
        for ((key, pkgs) in overrides) {
            val arr = JSONArray()
            for (p in pkgs) arr.put(p)
            o.put(key, arr)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_OVERRIDES, o.toString()).apply()
    }

    /**
     * Merge custom + auto groups over the given apps (apps are
     * expected A-Z already, as AppRepository returns them).
     * Returns (sections, ungroupedApps).
     */
    fun sections(
        apps: List<AppInfo>,
        custom: List<CustomGroup>,
        hiddenAuto: Set<String> = emptySet(),
        overrides: Map<String, List<String>> = emptyMap(),
        autoOrder: List<String> = emptyList(),
        unifiedOrder: List<String> = emptyList(),
        deletedSections: Set<String> = emptySet(),
    ): Pair<List<GroupSection>, List<AppInfo>> {
        val byPkg = apps.associateBy { it.packageName }
        val used = mutableSetOf<String>()
        val sections = mutableListOf<GroupSection>()
        if (unifiedOrder.isEmpty()) {
            // Legacy path (pre-migration): custom in list order,
            // then auto in autoOrder. Kept verbatim so panel/widget
            // behave identically until the editor migrates once.
            for (g in custom) {
                // vc163: soft-deleted customs are skipped here,
                // so their apps fall back to auto groups.
                if ("custom:" + g.id in deletedSections) continue
                // vc153 ORDER: custom members keep their STORED
                // order, not A-Z — so the folder preview and the
                // group view show the user's order.
                val members = g.packages.mapNotNull { byPkg[it] }
                if (members.isEmpty()) continue
                sections.add(GroupSection("custom:" + g.id, g.title, members))
                used.addAll(members.map { it.packageName })
            }
            val rest = apps.filter { it.packageName !in used }
            val computed = AppGroups.groupedApps(rest).toMap()
            // vc156: iterate ALL auto groups (not just computed
            // ones), so an override can fill a group the auto rules
            // left empty (e.g. Game with 0 apps). vc157: in the
            // user's stored order (default = enum order).
            for (group in orderedAutoGroups(autoOrder)) {
                if (group.name in hiddenAuto) continue
                if ("auto:" + group.name in deletedSections) continue
                val effective = if (group.name in overrides) {
                    // Edited group: exactly the stored list, in
                    // order (installed only, custom-grouped apps
                    // excluded — custom groups win single-membership).
                    overrides.getValue(group.name).mapNotNull { byPkg[it] }
                        .filter { it.packageName !in used }
                } else {
                    computed[group] ?: emptyList()
                }
                if (effective.isEmpty()) continue
                sections.add(GroupSection("auto:" + group.name, group.title, effective))
                used.addAll(effective.map { it.packageName })
            }
            val ungrouped = rest.filter { it.packageName !in used }
            return sections to ungrouped
        }
        // vc160 UNIFIED path: one order for every group. Custom
        // groups always own their members first (single-membership
        // holds whatever the display order is), so auto groups are
        // computed from the apps no custom group claims.
        val customById = custom.associateBy { it.id }
        // vc163: a soft-deleted custom owns nothing — its apps
        // must fall back to auto groups / ungrouped.
        val customUsed = custom.filter { "custom:" + it.id !in deletedSections }
            .flatMap { it.packages }.toSet()
        val rest = apps.filter { it.packageName !in customUsed }
        val computed = AppGroups.groupedApps(rest).toMap()
        val entries = unifiedOrder.toMutableList()
        for (g in custom) {
            val e = "c:" + g.id
            if (e !in entries) entries.add(e)
        }
        for (ag in orderedAutoGroups(autoOrder)) {
            val e = "a:" + ag.name
            if (e !in entries) entries.add(e)
        }
        for (e in entries) {
            if (e.startsWith("c:")) {
                val g = customById[e.removePrefix("c:")] ?: continue
                if ("custom:" + g.id in deletedSections) continue
                val members = g.packages.mapNotNull { byPkg[it] }
                    .filter { it.packageName !in used }
                if (members.isEmpty()) continue
                sections.add(GroupSection("custom:" + g.id, g.title, members))
                used.addAll(members.map { it.packageName })
            } else {
                val name = e.removePrefix("a:")
                if (name in hiddenAuto) continue
                if ("auto:" + name in deletedSections) continue
                val group = AppGroup.values().firstOrNull { it.name == name } ?: continue
                val effective = if (name in overrides) {
                    overrides.getValue(name).mapNotNull { byPkg[it] }
                        .filter { it.packageName !in used }
                } else {
                    (computed[group] ?: emptyList())
                        .filter { it.packageName !in used }
                }
                if (effective.isEmpty()) continue
                sections.add(GroupSection("auto:" + group.name, group.title, effective))
                used.addAll(effective.map { it.packageName })
            }
        }
        val ungrouped = apps.filter { it.packageName !in used }
        return sections to ungrouped
    }

    /** Cheap signature for the panel cache key: changes on any group edit. */
    fun signature(
        custom: List<CustomGroup>,
        hiddenAuto: Set<String> = emptySet(),
        overrides: Map<String, List<String>> = emptyMap(),
        autoOrder: List<String> = emptyList(),
        unifiedOrder: List<String> = emptyList(),
        deletedSections: Set<String> = emptySet(),
    ): String =
        custom.joinToString(";") { g -> g.id + "=" + g.packages.joinToString(",") } +
            "|hidden:" + hiddenAuto.sorted().joinToString(",") +
            "|overrides:" + overrides.entries.sortedBy { it.key }
                .joinToString(";") { (k, v) -> k + "=" + v.joinToString(",") } +
            "|autoOrder:" + autoOrder.joinToString(",") +
            "|unified:" + unifiedOrder.joinToString(",") +
            "|deleted:" + deletedSections.sorted().joinToString(",")
}
