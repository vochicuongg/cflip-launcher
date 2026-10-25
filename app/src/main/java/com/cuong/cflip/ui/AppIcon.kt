package com.cuong.cflip.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp

fun drawableToBitmap(drawable: Drawable, sizePx: Int = 144): Bitmap {
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    return bmp
}

@Composable
fun AppIcon(drawable: Drawable?, sizeDp: Int, modifier: Modifier = Modifier) {
    val bitmap = remember(drawable) { drawable?.let { drawableToBitmap(it) } }
    if (bitmap != null) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = null,
            modifier = modifier.size(sizeDp.dp).clip(RoundedCornerShape(percent = 24)))
    }
}
