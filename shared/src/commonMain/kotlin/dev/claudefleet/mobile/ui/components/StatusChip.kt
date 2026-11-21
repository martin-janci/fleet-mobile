package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.LocalStatusColors
import dev.claudefleet.mobile.ui.theme.StatusTone

/** The 10dp dot a row leads with. */
@Composable
fun StatusDot(claudeStatus: String?, stuckKind: String?, modifier: Modifier = Modifier) {
    val tone = StatusTone.of(claudeStatus, stuckKind)
    val colors = LocalStatusColors.current(tone)
    val stroke = if (tone.outlined) BorderStroke(1.dp, colors.onContainer.copy(alpha = 0.5f)) else null
    Box(
        modifier = modifier.size(10.dp).clip(CircleShape).background(colors.dot)
            .then(if (stroke != null) Modifier.border(stroke, CircleShape) else Modifier),
    )
}
