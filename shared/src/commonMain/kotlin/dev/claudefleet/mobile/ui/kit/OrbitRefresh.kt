package dev.claudefleet.mobile.ui.kit

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.Fleet
import kotlinx.coroutines.delay

/** The manual's `loader-delay`: a wait shorter than this shows nothing. */
const val LOADER_DELAY_MS: Long = 400

/**
 * The Orbit mark on the manual's 108-unit grid: slate tile, the orbit, the hub
 * and three hosts, the amber one the host waiting on you.
 *
 * [drawn] (0..1) is how much of it has appeared: the orbit sweeps round and
 * the hosts land one by one as a pull travels — "the Orbit draws as you pull"
 * (motion.md). [chase] turns the amber host round the orbit, the Chase loader
 * the pull hands over to while the fleet is fetched.
 */
@Composable
fun OrbitMark(modifier: Modifier = Modifier, size: Dp = 32.dp, drawn: Float = 1f, chase: Float? = null) {
    val o = Fleet.colors
    Canvas(modifier = modifier.size(size)) {
        val u = this.size.minDimension / 108f
        val c = Offset(54f * u, 54f * u)
        val p = drawn.coerceIn(0f, 1f)
        drawRoundRect(o.brandInk, size = Size(108f * u, 108f * u), cornerRadius = CornerRadius(24f * u), alpha = p.coerceAtLeast(0.15f))
        drawArc(
            color = o.brandLight,
            startAngle = -90f,
            sweepAngle = 360f * p,
            useCenter = false,
            topLeft = Offset(30f * u, 30f * u),
            size = Size(48f * u, 48f * u),
            style = Stroke(width = 5f * u),
            alpha = if (chase != null) 0.35f else 0.5f,
        )
        drawCircle(o.brandLight, radius = 10f * u * p, center = c)
        // The hosts at 12, 4 and 8 o'clock, landing at a third of the pull each.
        val hosts = listOf(Offset(54f, 30f) to false, Offset(33.22f, 66f) to false, Offset(74.78f, 66f) to true)
        hosts.forEachIndexed { i, (at, amber) ->
            val shown = ((p - i / 3f) * 3f).coerceIn(0f, 1f)
            if (shown <= 0f) return@forEachIndexed
            if (amber && chase != null) {
                rotate(degrees = chase * 360f, pivot = c) {
                    drawCircle(o.brandAmber, radius = 7.5f * u, center = Offset(at.x * u, at.y * u))
                }
            } else {
                drawCircle(if (amber) o.brandAmber else o.brandLight, radius = 7.5f * u * shown, center = Offset(at.x * u, at.y * u))
            }
        }
    }
}

/**
 * Pull to refresh with the Orbit (motion.md: "fleet-mobile pull to refresh:
 * the Orbit draws as you pull, then Chase").
 *
 * While a refresh runs the Chase shows only once it has taken
 * [LOADER_DELAY_MS]: a fetch that answers at once flashes nothing. The pull
 * itself is not a loader, it is the gesture's own feedback, so it draws from
 * the first pixel.
 *
 * [reducedMotion] turns the Chase into the manual's `loader-reduced` fade:
 * the platform's animation scale is read by the caller (redesign 10.11).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrbitPullToRefresh(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val state = rememberPullToRefreshState()
    var late by remember { mutableStateOf(false) }
    LaunchedEffect(isRefreshing) {
        late = false
        if (isRefreshing) {
            delay(LOADER_DELAY_MS)
            late = true
        }
    }
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            val pulled = state.distanceFraction
            val chasing = isRefreshing && late
            if (pulled > 0f || chasing) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .semantics { contentDescription = if (chasing) "Refreshing" else "Pull to refresh" },
                ) {
                    if (chasing) Chase(reducedMotion) else OrbitMark(size = 32.dp, drawn = pulled)
                }
            }
        },
        content = content,
    )
}

/** The Chase loader: the amber host looks for the hub, 1.2 s a turn; a fade with reduced motion. */
@Composable
private fun Chase(reducedMotion: Boolean) {
    val loop = rememberInfiniteTransition(label = "chase")
    if (reducedMotion) {
        val alpha by loop.animateFloat(
            initialValue = 1f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse),
            label = "chase-fade",
        )
        OrbitMark(modifier = Modifier.alpha(alpha), size = 32.dp)
    } else {
        val turn by loop.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "chase-turn",
        )
        OrbitMark(size = 32.dp, chase = turn)
    }
}
