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
     * Merge custom + auto groups over the given apps (apps are
     * expected A-Z already, as AppRepository returns them).
     * Returns (sections, ungroupedApps).
     */
    fun sections(
        apps: List<AppInfo>,
        custom: List<CustomGroup>,
    ): Pair<List<GroupSection>, List<AppInfo>> {
        val byPkg = apps.associateBy { it.packageName }
        val used = mutableSetOf<String>()
        val sections = mutableListOf<GroupSection>()
        for (g in custom) {
            val members = g.packages.mapNotNull { byPkg[it] }
                .sortedBy { it.label.lowercase() }
            if (members.isEmpty()) continue
            sections.add(GroupSection("custom:" + g.id, g.title, members))
            used.addAll(members.map { it.packageName })
        }
        val rest = apps.filter { it.packageName !in used }
        for ((group, members) in AppGroups.groupedApps(rest)) {
            sections.add(GroupSection("auto:" + group.name, group.title, members))
            used.addAll(members.map { it.packageName })
        }
        val ungrouped = rest.filter { it.packageName !in used }
        return sections to ungrouped
    }

    /** Cheap signature for the panel cache key: changes on any group edit. */
    fun signature(custom: List<CustomGroup>): String =
        custom.joinToString(";") { g -> g.id + "=" + g.packages.sorted().joinToString(",") }
}
