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
import dev.claudefleet.mobile.model.reasonLabel

/**
 * A session's state in one word.
 */
@Composable
fun StatusChip(
    claudeStatus: String?,
    stuckKind: String? = null,
    modifier: Modifier = Modifier,
    /**
     * Why the hub says the session needs a person, when that is more than its
     * status says (`ci_failing`, `context_full`, `stale_working`, …): shown
     * in place of the status word, in the waiting tone.
     */
    reason: String? = null,
) {
    val shown = reason?.takeIf { it !in STATUS_REASONS }
    val tone = when {
        // An account at its limit is paused by the fleet, not waiting on a person.
        reason == "account_limit" -> StatusTone.PAUSED
        shown != null && StatusTone.of(claudeStatus, stuckKind) !in URGENT_TONES -> StatusTone.BLOCKED
        else -> StatusTone.of(claudeStatus, stuckKind)
    }
    val colors = LocalStatusColors.current(tone)
    val text = shown?.let { reasonLabel(it).lowercase().replace("ci ", "CI ") } ?: statusLabel(claudeStatus, stuckKind)
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

/** Reasons the status word already says: no need to replace it. */
private val STATUS_REASONS = setOf("waiting", "stuck", "failed")

/** Tones already loud enough that a reason keeps them rather than turning amber. */
private val URGENT_TONES = setOf(StatusTone.STUCK, StatusTone.FAILED, StatusTone.BLOCKED)
