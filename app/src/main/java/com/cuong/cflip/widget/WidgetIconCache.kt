package com.cuong.cflip.widget

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import java.util.concurrent.ConcurrentHashMap

/**
 * Widget icon bitmap cache (widget-lag fix, 2026-11-20).
 *
 * Why this exists: REA decompilation of the two reference cover
 * launchers showed both keep rendered icon bitmaps around instead of
 * re-rendering them in RemoteViewsFactory.getViewAt():
 *  - Cover Power (g4.c) holds a static ConcurrentHashMap of labels and
 *    a g4.a LruCache (sizeOf) of icon Bitmaps; getViewAt() checks the
 *    cache first and only converts a Drawable on a miss.
 *  - 9 Flip (t5.a) goes further and pre-renders the Bitmap into its
 *    persisted render snapshot (v5.a.f8241d), so getViewAt() is a pure
 *    memory read.
 *
 * CFlip's factory used to call createBitmap + mutate + draw for EVERY
 * cell on EVERY getViewAt() (9 times per folder tile), which is the
 * repeatable part of the 3-5 s widget freeze after the phone has been
 * folded a while. This cache is a process-wide object, so it survives
 * factory re-creation (host rebinds, notify storms) for as long as the
 * process lives; a package change can leave a stale icon until the
 * process restarts, which matches the reference apps' trade-off and is
 * bounded by the LRU size.
 *
 * Scroll fix (2026-11-20): the cache was 8 MiB, sized for 96 px icons —
 * but app cells render at dpPx(iconDp) * 2 (about 294 px on the cover,
 * ~338 KiB each), so only ~24 cell icons fit. Scrolling through the
 * grid evicted icons faster than they were reused and every returning
 * cell re-rendered (and re-asked PackageManager for its icon), which
 * read as the grid "reloading apps" on scroll. Now:
 *  - the bitmap cache is 24 MiB, and
 *  - Drawables are cached per package too, so even a bitmap miss
 *    never goes back to PackageManager — it just re-draws.
 */
object WidgetIconCache {

    private const val MAX_SIZE_KIB = 24 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_SIZE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val drawables = ConcurrentHashMap<String, Drawable>()

    private fun key(packageName: String, sizePx: Int): String = "$packageName@$sizePx"

    fun get(packageName: String, sizePx: Int): Bitmap? = cache.get(key(packageName, sizePx))

    /** The app's icon Drawable, loaded from PackageManager at most once per process. */
    fun drawable(packageName: String, pm: PackageManager): Drawable? {
        drawables[packageName]?.let { return it }
        val loaded = runCatching { pm.getApplicationIcon(packageName) }.getOrNull() ?: return null
        drawables[packageName] = loaded
        return loaded
    }

    /** Render [drawable] at [sizePx] once, cache it, and return it. */
    fun render(packageName: String, drawable: Drawable, sizePx: Int): Bitmap {
        get(packageName, sizePx)?.let { return it }
        val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        copy.setBounds(0, 0, sizePx, sizePx)
        copy.draw(canvas)
        cache.put(key(packageName, sizePx), bmp)
        return bmp
    }

    fun clear() {
        cache.evictAll()
        drawables.clear()
    }
}
