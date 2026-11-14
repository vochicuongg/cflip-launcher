package com.cuong.cflip.ui

import android.provider.Settings
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * vc242 MOTION — shared motion tokens + press feedback (UI/UX Pro Max).
 *
 * Timing follows the skill's rule: one shared token per distance, not one
 * duration copied everywhere. Enter is slower than exit (see call sites:
 * tab slide 300/200, badge 150, press spring ~100ms equivalent).
 * No width/height is ever animated; only alpha, offset, scale and colour.
 * Reduced motion: when the system animator scale is 0 (Settings > Developer
 * options > Animator duration scale, or the accessibility reduced-motion
 * setting on newer Android), every token collapses to its end state.
 */
object CFlipMotion {
    /** Press spring: tight and fast, so the finger gets an answer < 150 ms. */
    const val PressScale = 0.95f
    val PressSpring: SpringSpec<Float> = spring(dampingRatio = 0.7f, stiffness = 600f)

    /** Micro feedback (badge count, icon swap, label fade). */
    const val FastMs = 150

    /** Standard transition (tab pill colour, grid stagger step is below). */
    const val MediumMs = 250

    /** Large transition (tab slide, status-row expand). */
    const val SlowMs = 300

    /** Grid entrance: each cell starts 30 ms after the previous one. */
    const val StaggerStepMs = 30

    /** Cap the stagger so a long list never takes > ~600 ms to settle. */
    const val StaggerMaxItems = 12

    /** Entrance travel for grid cells (dp, vertical only — never width). */
    const val EntranceOffsetDp = 12f
}

/**
 * True when the user turned animations off at system level
 * (animator duration scale = 0). Read once per composition; call sites
 * snap to their end state instead of animating when this is true.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
        ) == 0f
    }
}

/**
 * Press feedback that never moves layout: the pressed element shrinks to
 * 95% (spring) while the finger is down and springs back on release.
 * The caller must pass the SAME [source] to its clickable/combinedClickable
 * (via interactionSource) — that is what drives the pressed state.
 * With reduced motion the scale stays 1f and only the ripple answers.
 */
@Composable
fun Modifier.pressScale(
    source: MutableInteractionSource,
    pressedScale: Float = CFlipMotion.PressScale,
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val reduced = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduced) pressedScale else 1f,
        animationSpec = CFlipMotion.PressSpring,
        label = "pressScale",
    )
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}
