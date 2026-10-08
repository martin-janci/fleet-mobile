package dev.claudefleet.mobile.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.Fleet

/**
 * Sonar (manual: LoadersInFlows, "Waiting for the first heartbeat"): the hub
 * as a dot, sending rings out and listening for a host to answer. Three
 * rings, staggered by a third of the 2.4 s cycle, each fading as it widens.
 * It is the wait, not progress: the host lands when it answers.
 */
@Composable
fun Sonar(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val o = Fleet.colors
    val reduced = reducedMotion()
    val t by rememberInfiniteTransition(label = "sonar").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SONAR_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "sonar-time",
    )
    Canvas(modifier = modifier.size(size).semantics { contentDescription = "Waiting for the host to answer" }) {
        val r = this.size.minDimension / 2f
        for (ring in 0 until RINGS) {
            val phase = sonarRing(if (reduced) 0.5f else t, ring)
            drawCircle(
                o.loaderAccent.copy(alpha = (1f - phase) * 0.8f),
                radius = r * (0.2f + 0.8f * phase),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        drawCircle(o.loaderAccent, radius = r * 0.12f)
    }
}

/** How far ring [index] has spread at [t] of the cycle: 0 at the dot, 1 at the edge. */
internal fun sonarRing(t: Float, index: Int): Float = ((t + index.toFloat() / RINGS) % 1f + 1f) % 1f

private const val RINGS = 3
private const val SONAR_MS = 2_400
