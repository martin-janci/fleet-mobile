package dev.claudefleet.mobile.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.LocalStatusColors
import dev.claudefleet.mobile.ui.theme.StatusTone
import dev.claudefleet.mobile.ui.theme.statusLabel

/**
 * A session's state in one word.
 */
@Composable
fun StatusChip(
    claudeStatus: String?,
    stuckKind: String? = null,
    modifier: Modifier = Modifier,
) {
    val tone = StatusTone.of(claudeStatus, stuckKind)
    val colors = LocalStatusColors.current(tone)
    val text = statusLabel(claudeStatus, stuckKind)
    val border = when {
        tone.dotted -> BorderStroke(1.dp, colors.onContainer.copy(alpha = 0.4f))
        tone.outlined -> BorderStroke(1.dp, colors.onContainer.copy(alpha = 0.6f))
        else -> null
    }
    Surface(
        modifier = modifier.semantics { contentDescription = "status: $text" },
        color = colors.container,
        contentColor = colors.onContainer,
        shape = MaterialTheme.shapes.small,
        border = border,
    ) {
        AnimatedContent(targetState = text, label = "status") { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

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
