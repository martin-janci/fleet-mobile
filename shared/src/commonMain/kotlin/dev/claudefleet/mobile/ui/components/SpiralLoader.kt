package dev.claudefleet.mobile.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.SPIRAL_CYCLE_MS
import dev.claudefleet.mobile.ui.theme.SPIRAL_PATH
import dev.claudefleet.mobile.ui.theme.spiralFrame

/** The `viewBox` the squiggle's coordinates are written in; everything scales from it. */
private const val VIEW_BOX = 16f

/** Stroke width in [VIEW_BOX] units, scaled with the rest. */
private const val STROKE = 1.4f

/**
 * The spiral loader: a looped squiggle whose stroke runs along it, fast four
 * times then slow twice (see `theme/Spiral.kt`). The same animation the desktop
 * app draws, so a wait looks the same on both.
 *
 * Drawn in [color], which defaults to `LocalContentColor` — so inside an
 * `IconButton` it picks up the button's own colour, enabled or not, exactly as
 * an `Icon` would.
 *
 * Decorative by default; pass [contentDescription] when the loader is the only
 * thing on screen saying something is happening. [paused] freezes it on the
 * still frame without giving up its box, for an indicator that stays mounted
 * while idle.
 */
@Composable
fun SpiralLoader(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color = LocalContentColor.current,
    paused: Boolean = false,
    contentDescription: String? = null,
) {
    val still = remember { mutableFloatStateOf(0f) }
    val clock: State<Float> = if (paused) {
        still
    } else {
        rememberInfiniteTransition(label = "spiral").animateFloat(
            initialValue = 0f,
            targetValue = SPIRAL_CYCLE_MS.toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(SPIRAL_CYCLE_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "phase",
        )
    }

    // Built once and reused: `getSegment` rewrites `window` in place, so a
    // frame of this animation allocates nothing.
    val measure = remember { PathMeasure().apply { setPath(SPIRAL_PATH, false) } }
    val window = remember { Path() }

    Canvas(
        modifier = modifier
            .size(size)
            // The squiggle is 24 units wide in a 16-unit box and slides through
            // it; without this it would draw outside its own bounds.
            .clipToBounds()
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                }
            ),
    ) {
        // `clock.value` is read HERE, in the draw phase, not at composition —
        // so a frame of the spin invalidates drawing alone instead of
        // recomposing everything around it sixty times a second.
        val frame = spiralFrame(clock.value)
        val length = measure.length
        if (length <= 0f) return@Canvas
        window.reset()
        measure.getSegment(
            startDistance = length * frame.start / 100f,
            stopDistance = length * frame.end / 100f,
            destination = window,
            startWithMoveTo = true,
        )
        scale(this.size.width / VIEW_BOX, this.size.height / VIEW_BOX, pivot = Offset.Zero) {
            // The squiggle is centred on the origin (y -6..6), so half the box
            // down puts it in the middle; `frame.x` is the slide.
            translate(frame.x, VIEW_BOX / 2f) {
                drawPath(
                    path = window,
                    color = color,
                    style = Stroke(
                        width = STROKE,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                        join = androidx.compose.ui.graphics.StrokeJoin.Round,
                    ),
                )
            }
        }
    }
}
