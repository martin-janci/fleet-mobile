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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import kotlinx.coroutines.delay

/**
 * The Pulse sequence (manual: LogoMotion, "Session starting"): the Orbit
 * mark with its three satellites, one per real start step (worktree, tmux,
 * agent), each swelling in turn — 1.1 s, staggered 180 ms, looped.
 *
 * The loop is the loader, not a progress bar: it does not advance through
 * the steps on a timer. A step is marked done only when the hub says so.
 */
@Composable
fun PulseSequence(modifier: Modifier = Modifier, size: Dp = 72.dp) {
    val o = Fleet.colors
    val time by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = PULSE_MS,
        animationSpec = infiniteRepeatable(tween(PULSE_MS.toInt(), easing = LinearEasing), RepeatMode.Restart),
        label = "pulse-time",
    )
    Canvas(modifier = modifier.size(size).semantics { contentDescription = "Starting" }) {
        // The mark is drawn on the manual's 108-unit box.
        val u = this.size.minDimension / 108f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        drawRoundRect(o.brandInk, cornerRadius = CornerRadius(24f * u))
        drawCircle(o.brandLight.copy(alpha = 0.5f), radius = 24f * u, center = p(54f, 54f), style = Stroke(width = 5f * u))
        drawCircle(o.brandLight, radius = 10f * u, center = p(54f, 54f))
        SATELLITES.forEachIndexed { i, (x, y) ->
            val scale = pulseScale(time - i * PULSE_STAGGER_MS)
            drawCircle(if (i == 1) o.brandAmber else o.brandLight, radius = 7.5f * u * scale, center = p(x, y))
        }
    }
}

/**
 * True once [active] has held for the manual's `loader-delay` (400 ms), so
 * a start that answers at once never flashes a loader.
 */
@Composable
fun pastLoaderDelay(active: Boolean): Boolean {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        shown = false
        if (active) {
            delay(OrbitTokens.durationMs.getValue("loader-delay"))
            shown = true
        }
    }
    return active && shown
}

/** One satellite's scale at [ms] into its cycle: 1 → 1.4 at 30 % → 1 at 60 %, then rest. */
internal fun pulseScale(ms: Float): Float {
    val t = ((ms % PULSE_MS) + PULSE_MS) % PULSE_MS / PULSE_MS
    return when {
        t < 0.3f -> 1f + 0.4f * (t / 0.3f)
        t < 0.6f -> 1.4f - 0.4f * ((t - 0.3f) / 0.3f)
        else -> 1f
    }
}

private const val PULSE_MS = 1100f
private const val PULSE_STAGGER_MS = 180f

/** Worktree, tmux, agent: the three satellites, top then clockwise, on the 108 box. */
private val SATELLITES = listOf(54f to 30f, 74.78f to 66f, 33.22f to 66f)
