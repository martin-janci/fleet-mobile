package dev.claudefleet.mobile.ui.kit

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/**
 * The motion rules from the manual, in one place, so no screen re-derives a
 * number: a loader never shows before `loader-delay`, reduced motion turns
 * every loop into one `loader-reduced` fade, and after `hub-lost-after`
 * without the hub the app says so instead of spinning.
 */
object OrbitMotion {
    val loaderDelayMs: Long = OrbitTokens.durationMs.getValue("loader-delay")
    val reducedFadeMs: Long = OrbitTokens.durationMs.getValue("loader-reduced")
    val hubLostAfterMs: Long = OrbitTokens.durationMs.getValue("hub-lost-after")
}

/** Whether a wait that has lasted [elapsedMs] has earned a loader. A wait under [delayMs] shows nothing. */
fun loaderDue(elapsedMs: Long, delayMs: Long = OrbitMotion.loaderDelayMs): Boolean = elapsedMs >= delayMs

/**
 * True once [waiting] has been true for [delayMs], false the moment it stops.
 *
 * Counted in frames rather than with `delay()`, so the frame clock decides:
 * on a phone that is the display, and in a test it is the time the scene is
 * rendered at, which is how `PhoneStatesTest` proves nothing draws at 399 ms.
 */
@Composable
fun rememberLoaderVisible(waiting: Boolean, delayMs: Long = OrbitMotion.loaderDelayMs): Boolean {
    var due by remember { mutableStateOf(false) }
    LaunchedEffect(waiting, delayMs) {
        due = false
        if (!waiting) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (!due) {
            val now = withFrameMillis { it }
            due = loaderDue(now - start, delayMs)
        }
    }
    return waiting && due
}

/**
 * Overrides the system's reduced-motion setting below it; null (the default)
 * follows the system. "This phone" settings (14.11) can provide the manual's
 * Motion choice here; tests use it to draw the reduced frame.
 */
val LocalReducedMotion = compositionLocalOf<Boolean?> { null }

/** Whether the system asks for less motion: Android's animator scale at 0, iOS's Reduce Motion. */
@Composable
expect fun systemReducedMotion(): Boolean

/** Whether loaders on this screen should fade instead of move. */
@Composable
fun reducedMotion(): Boolean = LocalReducedMotion.current ?: systemReducedMotion()

/**
 * One loader's clock: a phase from 0 to 1 every [periodMs], and the alpha to
 * draw it at. With reduced motion the phase stays at 0 (the still pose) and
 * the alpha does the manual's 2.4 s fade instead.
 */
class LoaderClock(val phase: Float, val alpha: Float, val reduced: Boolean)

@Composable
fun rememberLoaderClock(periodMs: Int, easing: Easing = LinearEasing): LoaderClock {
    val reduced = reducedMotion()
    val transition = rememberInfiniteTransition(label = "loader")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reduced) 0f else 1f,
        animationSpec = infiniteRepeatable(tween(periodMs, easing = easing), RepeatMode.Restart),
        label = "phase",
    )
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (reduced) 0.55f else 1f,
        animationSpec = infiniteRepeatable(tween((OrbitMotion.reducedFadeMs / 2).toInt()), RepeatMode.Reverse),
        label = "fade",
    )
    return LoaderClock(phase = if (reduced) 0f else phase, alpha = alpha, reduced = reduced)
}
