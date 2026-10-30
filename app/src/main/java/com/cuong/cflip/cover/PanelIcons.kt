package com.cuong.cflip.cover

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * vc209 HAND-DRAWN SYSTEMUI-STYLE ICONS.
 *
 * The Quick Panel tabs used to show text symbols (gear/bell/grid as
 * Unicode, and the bell even rendered as a colour emoji). The user
 * wants SystemUI-style icons instead, drawn (not emoji, not copied
 * Samsung assets - this file is 100% original Canvas paths, so it
 * is safe to ship on a public repo). All icons are monochrome and
 * tintable, drawn in a 100x100 box scaled to the view size, so they
 * stay crisp at any dp.
 */
enum class PanelIcon { GEAR, BELL, GRID, PERSON, LAYERS, CHEVRON }

class PanelIconView @JvmOverloads constructor(
    ctx: Context,
    private val icon: PanelIcon,
    tint: Int = 0xFFFFFFFF.toInt(),
    /** Fraction of the view's smaller side the glyph occupies. Tabs use
     *  ~0.9 (glyph nearly fills the box); rows use ~0.7 (glyph sits
     *  inside the 40dp icon box next to a real app icon). */
    private val scale: Float = 0.9f,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = tint
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND; color = tint
    }

    fun setTint(c: Int) {
        fill.color = c; stroke.color = c; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val box = min(width, height) * scale
        if (box <= 0f) return
        canvas.translate((width - box) / 2f, (height - box) / 2f)
        canvas.scale(box / 100f, box / 100f)
        stroke.strokeWidth = 9f
        when (icon) {
            PanelIcon.GEAR -> drawGear(canvas)
            PanelIcon.BELL -> drawBell(canvas)
            PanelIcon.GRID -> drawGrid(canvas)
            PanelIcon.PERSON -> drawPerson(canvas)
            PanelIcon.LAYERS -> drawLayers(canvas)
            PanelIcon.CHEVRON -> drawChevron(canvas)
        }
    }

    /** Gear: 8-tooth silhouette with a round hole (even-odd fill).
     *  Tooth profile is a trapezoid around the circle: flat tip,
     *  sloped flanks, flat root - reads as a gear even at 24dp. */
    private fun drawGear(canvas: Canvas) {
        val path = Path()
        val cx = 50f; val cy = 50f
        var first = true
        for (deg in 0 until 360 step 3) {
            val phase = (deg % 45).toFloat()
            val r = when {
                phase < 8f -> 48f
                phase < 16f -> 48f - (phase - 8f) / 8f * 10f
                phase < 29f -> 38f
                phase < 37f -> 38f + (phase - 29f) / 8f * 10f
                else -> 48f
            }
            val rad = Math.toRadians(deg.toDouble())
            val x = cx + (r * cos(rad)).toFloat()
            val y = cy + (r * sin(rad)).toFloat()
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
        }
        path.close()
        path.addCircle(cx, cy, 19f, Path.Direction.CW)
        path.fillType = Path.FillType.EVEN_ODD
        canvas.drawPath(path, fill)
    }

    /** Bell: stroked dome + rim + top loop, filled clapper dot. */
    private fun drawBell(canvas: Canvas) {
        val dome = Path().apply {
            moveTo(28f, 66f)
            lineTo(28f, 50f)
            cubicTo(28f, 26f, 72f, 26f, 72f, 50f)
            lineTo(72f, 66f)
        }
        canvas.drawPath(dome, stroke)
        canvas.drawLine(22f, 66f, 78f, 66f, stroke)
        canvas.drawCircle(50f, 16f, 4f, stroke)
        canvas.drawCircle(50f, 78f, 7f, fill)
    }

    /** Apps grid: 3x3 dots, like the One UI app-drawer icon. */
    private fun drawGrid(canvas: Canvas) {
        for (x in listOf(28f, 50f, 72f)) {
            for (y in listOf(28f, 50f, 72f)) {
                canvas.drawCircle(x, y, 8f, fill)
            }
        }
    }

    /** Person (accessibility): filled head + stroked body, arms out. */
    private fun drawPerson(canvas: Canvas) {
        canvas.drawCircle(50f, 24f, 12f, fill)
        canvas.drawLine(20f, 48f, 80f, 48f, stroke)
        canvas.drawLine(50f, 48f, 50f, 68f, stroke)
        canvas.drawLine(50f, 68f, 33f, 90f, stroke)
        canvas.drawLine(50f, 68f, 67f, 90f, stroke)
    }

    /** Overlay / windows: two offset rounded squares. */
    private fun drawLayers(canvas: Canvas) {
        canvas.drawRoundRect(24f, 24f, 64f, 64f, 8f, 8f, stroke)
        canvas.drawRoundRect(36f, 36f, 76f, 76f, 8f, 8f, stroke)
    }

    /** Chevron ">", for row affordance. */
    private fun drawChevron(canvas: Canvas) {
        val p = Path().apply {
            moveTo(36f, 24f); lineTo(64f, 50f); lineTo(36f, 76f)
        }
        canvas.drawPath(p, stroke)
    }
}
