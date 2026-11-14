package com.cuong.cflip.service

/**
 * LiveBridge mirror unwrap — ONE shared implementation (vc252: moved
 * here from CoverControlCenter so the listener service's refresh()
 * and the panel use the same code; the two copies would drift).
 *
 * A LiveBridge mirror is a re-post of one source notification:
 * same title/text, plain actions copied verbatim from the source,
 * and id = abs(sourceKey.hashCode()). The mirror must never be
 * shown as its own row — it is always the source app's row.
 */
object LiveBridgeUnwrap {
    const val PACKAGE_KIMGISA = "com.locnall.KimGiSa"
    const val PACKAGE_LIVEBRIDGE = "com.appsfolder.livebridge"

    fun isMirrorPackage(pkg: String): Boolean =
        pkg == PACKAGE_KIMGISA || pkg == PACKAGE_LIVEBRIDGE

    // vc216: the source app a LiveBridge mirror is about,
    // read from the mirror's own actions - a plain action is
    // the source app's intent copied verbatim, so its creator
    // IS the source app (a proxied reply action's creator is
    // LiveBridge itself and is skipped). Null when this row is
    // not a mirror or no usable action exists.
    fun mirrorSourcePackage(n: CoverNotification): String? {
        if (!isMirrorPackage(n.packageName)) return null
        return n.actionCreatorPackages.firstOrNull { it != null && it != n.packageName }
    }

    // vc217: LiveBridge's mirror id, replicated exactly
    // (its mirrorIdForKey): abs(sourceKey.hashCode()), with
    // MIN_VALUE mapped to 0. String.hashCode is specified by
    // the language, so the value computed here equals the one
    // LiveBridge computed for the same source key.
    fun mirrorIdForKey(key: String): Int {
        val v = key.hashCode()
        return if (v == Int.MIN_VALUE) 0 else kotlin.math.abs(v)
    }

    // vc217 LIVEBRIDGE MIRROR UNWRAP (per app, layered):
    // Tier 0 - exact: hash every active key; the one whose
    // hash equals the mirror's id IS the source, regardless
    // of reformatted title/text or missing actions. This is
    // what fixes mirrors like Muse that beat vc216.
    // Tier 1 - source package from the mirror's own plain
    // actions' creators (vc216).
    // Tier 2 - title+text exact, any package (vc215).
    // Tier 3 - timing: the mirror is posted right after its
    // source, so a single candidate in the 60s before the
    // mirror (or a unique title match in that window) is the
    // source. Covers aggregate mirrors, whose id hashes an
    // internal aggregate key instead of a source key.
    // Tier 4 - title-only unique (vc215).
    // Every tier logs, so a misfire is visible in logcat.
    // [in] n: the mirror row. [in] active: the current
    // CoverNotification list to search. Null when no source
    // can be identified (caller then falls back to the
    // mirror's source package, if known, or drops the row).
    fun unwrapLiveBridge(n: CoverNotification, active: List<CoverNotification>): CoverNotification? {
        if (!isMirrorPackage(n.packageName)) return null
        val title = n.title.trim()
        val text = n.text.trim()
        val byHash = active.filter {
            it.packageName != n.packageName && mirrorIdForKey(it.key) == n.notifId
        }
        if (byHash.isNotEmpty()) {
            val exact = if (title.isNotEmpty() && text.isNotEmpty()) {
                byHash.filter { it.title.trim() == title && it.text.trim() == text }
            } else emptyList()
            val target = exact.firstOrNull() ?: byHash.minByOrNull { it.timeMs }!!
            android.util.Log.i("CFlipNotifListener", "unwrap: ${n.packageName} -> ${target.packageName} via mirror id hash")
            return target
        }
        val sourcePkg = mirrorSourcePackage(n)
        if (sourcePkg != null) {
            val fromPkg = active.filter { it.packageName == sourcePkg }
            if (fromPkg.isNotEmpty()) {
                val exact = if (title.isNotEmpty() && text.isNotEmpty()) {
                    fromPkg.filter { it.title.trim() == title && it.text.trim() == text }
                } else emptyList()
                val target = exact.minByOrNull { it.timeMs } ?: fromPkg.maxByOrNull { it.timeMs }!!
                android.util.Log.i("CFlipNotifListener", "unwrap: ${n.packageName} -> $sourcePkg via action creator (${if (exact.isNotEmpty()) "title+text" else "most recent"})")
                return target
            }
            // Known source app, no active notification from it:
            // openMirrorOrNotif launches the app itself below.
            return null
        }
        if (title.isNotEmpty() && text.isNotEmpty()) {
            val candidates = active.filter {
                it.packageName != n.packageName && it.title.trim() == title && it.text.trim() == text
            }
            if (candidates.isNotEmpty()) {
                val earliest = candidates.minByOrNull { it.timeMs }!!
                android.util.Log.i("CFlipNotifListener", "unwrap: ${n.packageName} -> ${earliest.packageName} (title+text)")
                return earliest
            }
        }
        val window = active.filter {
            it.packageName != n.packageName && it.timeMs <= n.timeMs && n.timeMs - it.timeMs <= 60_000
        }
        if (window.size == 1) {
            android.util.Log.i("CFlipNotifListener", "unwrap: ${n.packageName} -> ${window[0].packageName} (timing, single candidate)")
            return window[0]
        }
        if (window.isNotEmpty() && title.isNotEmpty()) {
            val byTitle = window.filter { it.title.trim() == title }
            if (byTitle.size == 1) {
                android.util.Log.i("CFlipNotifListener", "unwrap: ${n.packageName} -> ${byTitle[0].packageName} (timing+title)")
                return byTitle[0]
            }
        }
        if (title.isEmpty()) return null
        val byTitle = active.filter {
            it.packageName != n.packageName && it.title.trim() == title
        }
        if (byTitle.size != 1) return null
        android.util.Log.i("CFlipNotifListener", "unwrap: ${n.packageName} -> ${byTitle[0].packageName} (title-only)")
        return byTitle[0]
    }
}
