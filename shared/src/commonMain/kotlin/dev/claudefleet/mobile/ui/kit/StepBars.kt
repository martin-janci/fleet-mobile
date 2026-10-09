package dev.claudefleet.mobile.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/**
 * The wizard's step bars (board MobileWizards: "step bars from two steps
 * on"): one bar per step under the header, the steps reached in the accent,
 * the rest on the track. Nothing for a one-step wizard.
 */
@Composable
fun StepBars(step: Int, total: Int, modifier: Modifier = Modifier, gutter: Boolean = true) {
    if (!stepBarsShown(total)) return
    val o = Fleet.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = if (gutter) OrbitTokens.spacing("phone-gutter").dp else 0.dp, end = if (gutter) OrbitTokens.spacing("phone-gutter").dp else 0.dp, bottom = 10.dp)
            .semantics { contentDescription = "Step $step of $total" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (i in 1..total) {
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(if (stepBarReached(i, step)) o.accent else o.track, RoundedCornerShape(2.dp)),
            )
        }
    }
}

internal fun stepBarsShown(total: Int): Boolean = total >= 2

internal fun stepBarReached(bar: Int, step: Int): Boolean = bar <= step
