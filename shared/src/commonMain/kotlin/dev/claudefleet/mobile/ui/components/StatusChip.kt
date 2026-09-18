package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A session's state in one word.
 *
 * The vocabulary is the hub's, not this app's: `claude_status` is one of
 * working / blocked / completed / failed / stopped / idle (null when unknown)
 * and `stuck_kind` one of auth_menu / reconnect / trust_prompt / oom /
 * press_enter (null when not stuck) — the enums in
 * `crates/fleet-core/src/service/pane_intel.rs`. Being stuck wins over the
 * status, because it is the thing a person has to go and clear.
 *
 * Unknown values are drawn as they arrive rather than folded into "other": the
 * hub may grow the vocabulary, and a word a person can read beats a label this
 * build happens to recognise.
 */
@Composable
fun StatusChip(
    claudeStatus: String?,
    stuckKind: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val (text, background) = when {
        stuckKind != null -> stuckKind.replace('_', ' ') to colors.errorContainer
        claudeStatus.isNullOrBlank() -> "unknown" to colors.surfaceVariant
        else -> claudeStatus to when (claudeStatus) {
            "blocked", "failed" -> colors.errorContainer
            "working" -> colors.primaryContainer
            "completed" -> colors.secondaryContainer
            else -> colors.surfaceVariant
        }
    }
    Surface(
        modifier = modifier,
        color = background,
        contentColor = onColorFor(background),
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * Material's paired `onX` colour for the container in use. Looked up rather than
 * computed: the theme names the pairs, and guessing at contrast is how a chip
 * ends up legible in one theme and not the other.
 */
@Composable
private fun onColorFor(background: Color): Color {
    val colors = MaterialTheme.colorScheme
    return when (background) {
        colors.errorContainer -> colors.onErrorContainer
        colors.primaryContainer -> colors.onPrimaryContainer
        colors.secondaryContainer -> colors.onSecondaryContainer
        else -> colors.onSurfaceVariant
    }
}
