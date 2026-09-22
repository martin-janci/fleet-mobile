package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ConvItem

/**
 * The three ways a turn's non-text items are drawn, split out of
 * `SessionScreen.kt` so that file's own `Item(...)` stays a dispatch table
 * rather than growing a composable body per kind. [dev.claudefleet.mobile.ui.SessionScreen]'s
 * `Item` only decides *which* of these a [ConvItem] gets; every kind's own
 * layout lives here.
 */

/**
 * A `Task`/`Agent` call: a chevron marker (it read as a call rather than
 * plain output, the same way [ConvItem.Tool]'s own one-liner does) plus the
 * subagent's own final line, and its result underneath once it is done.
 */
@Composable
fun SubagentRow(item: ConvItem.Subagent, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = if (item.error) "✗" else "›",
            style = MaterialTheme.typography.bodySmall,
            color = if (item.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = item.agentType?.takeIf { it.isNotBlank() }?.let { "$it — ${item.label}" } ?: item.label,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Only once it is actually done: a subagent still running has no
            // result yet, and showing its stale `null` as blank space reads
            // as a bug rather than as "still working".
            if (item.done && !item.result.isNullOrBlank()) {
                Text(
                    text = item.result,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A compaction: a labelled divider, the same visual break a `HorizontalDivider` already gives a turn. */
@Composable
fun CompactDivider(item: ConvItem.Compact, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * One italic line for the things that are neither said nor a tool call: a
 * slash command, a background task reporting in, or an interruption. Shared
 * by all three kinds — [ConvItem.Command], [ConvItem.Notification] and
 * [ConvItem.Interrupt] each already reduce to one sentence through their own
 * `label`, so there is nothing kind-specific left to lay out.
 */
@Composable
fun SystemLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontStyle = FontStyle.Italic,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
