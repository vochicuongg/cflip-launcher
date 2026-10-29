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
    ): Pair<List<GroupSection>, List<AppInfo>> {
        val byPkg = apps.associateBy { it.packageName }
        val used = mutableSetOf<String>()
        val sections = mutableListOf<GroupSection>()
        for (g in custom) {
            // vc153 ORDER: custom members keep their STORED order
            // (arranged with ↑/↓ in the editor), not A-Z — so the
            // folder preview and the group view show the user's order.
            val members = g.packages.mapNotNull { byPkg[it] }
            if (members.isEmpty()) continue
            sections.add(GroupSection("custom:" + g.id, g.title, members))
            used.addAll(members.map { it.packageName })
        }
        val rest = apps.filter { it.packageName !in used }
        val computed = AppGroups.groupedApps(rest).toMap()
        // vc156: iterate ALL auto groups (not just computed ones),
        // so an override can fill a group the auto rules left empty
        // (e.g. Game with 0 apps). Values() order == groupedApps
        // order, so the section order is unchanged.
        for (group in AppGroup.values()) {
            if (group.name in hiddenAuto) continue
            val effective = if (group.name in overrides) {
                // Edited group: exactly the stored list, in order
                // (installed only, custom-grouped apps excluded —
                // custom groups win single-membership).
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

    /** Cheap signature for the panel cache key: changes on any group edit. */
    fun signature(
        custom: List<CustomGroup>,
        hiddenAuto: Set<String> = emptySet(),
        overrides: Map<String, List<String>> = emptyMap(),
    ): String =
        custom.joinToString(";") { g -> g.id + "=" + g.packages.joinToString(",") } +
            "|hidden:" + hiddenAuto.sorted().joinToString(",") +
            "|overrides:" + overrides.entries.sortedBy { it.key }
                .joinToString(";") { (k, v) -> k + "=" + v.joinToString(",") }
}
