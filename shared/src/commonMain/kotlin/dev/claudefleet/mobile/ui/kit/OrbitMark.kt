package dev.claudefleet.mobile.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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

/**
 * The Orbit Fleet mark (manual: OrbitMark): a hub, one orbit and three hosts
 * on a slate tile, the amber host being the one waiting on you. Drawn on the
 * manual's 108-unit grid from `brand-ink`, `brand-light` and `brand-amber`,
 * exactly as `assets/Logos/orbit-mark.svg`. Static; it moves only as a loader.
 *
 * Sizes from the manual: 22 in a header, 28 beside a title, 72 and up on
 * empty, error and pairing screens.
 */
@Composable
fun OrbitMark(size: Dp, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    val ink = o.brandInk
    val light = o.brandLight
    val amber = o.brandAmber
    Canvas(modifier = modifier.size(size).semantics { contentDescription = "Orbit Fleet" }) {
        val u = this.size.width / 108f
        fun at(x: Float, y: Float) = Offset(x * u, y * u)
        val tile = OrbitTokens.radius("radius-brand") * u
        drawRoundRect(ink, cornerRadius = CornerRadius(tile, tile))
        drawCircle(light.copy(alpha = 0.5f), radius = 24f * u, center = at(54f, 54f), style = Stroke(width = 5f * u))
        drawCircle(light, radius = 10f * u, center = at(54f, 54f))
        drawCircle(light, radius = 7.5f * u, center = at(54f, 30f))
        drawCircle(amber, radius = 7.5f * u, center = at(74.78f, 66f))
        drawCircle(light, radius = 7.5f * u, center = at(33.22f, 66f))
    }
}

/** The default size the pairing and empty screens draw the mark at. */
val OrbitMarkLarge: Dp = 72.dp
