package com.cuong.cflip.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * vc255 WALLPAPER DECODE — one place that turns a stored wallpaperUri
 * into a Bitmap, shared by the Quick Panel background (and any later
 * surface). The cover's CoverApp keeps its own inline decode (proven);
 * this is the same recipe: downsample so the long edge is at most
 * [maxLongEdge] px, never decode at native size. `file:` URIs decode
 * from disk, anything else goes through the ContentResolver. Null
 * when there is no URI or the decode fails — callers fall back to
 * the solid background colour.
 */
object WallpaperBitmap {
    fun load(context: Context, uriStr: String, maxLongEdge: Int = 1024): Bitmap? {
        return runCatching {
            val uri = Uri.parse(uriStr)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decode(uriStr, uri, context, bounds)
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxLongEdge) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            decode(uriStr, uri, context, opts)
        }.getOrNull()
    }

    private fun decode(uriStr: String, uri: Uri, context: Context, opts: BitmapFactory.Options): Bitmap? {
        return if (uriStr.startsWith("file:")) {
            uri.path?.let { BitmapFactory.decodeFile(it, opts) }
        } else {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    }

    /**
     * Centre-cropped to exactly [targetW]x[targetH] px (the cover
     * display size for the panel background), so a photo of any
     * aspect fills the surface without stretching.
     */
    fun loadCropped(context: Context, uriStr: String, targetW: Int, targetH: Int): Bitmap? {
        val raw = load(context, uriStr, maxOf(targetW, targetH)) ?: return null
        if (raw.width <= 0 || raw.height <= 0) return raw
        val scale = maxOf(targetW.toFloat() / raw.width, targetH.toFloat() / raw.height)
        val scaledW = (raw.width * scale).toInt().coerceAtLeast(targetW)
        val scaledH = (raw.height * scale).toInt().coerceAtLeast(targetH)
        val scaled = Bitmap.createScaledBitmap(raw, scaledW, scaledH, true)
        if (scaled != raw) raw.recycle()
        val x = (scaled.width - targetW) / 2
        val y = (scaled.height - targetH) / 2
        val out = Bitmap.createBitmap(scaled, x, y, targetW, targetH)
        if (out != scaled) scaled.recycle()
        return out
    }
}
