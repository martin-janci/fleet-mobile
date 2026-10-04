package dev.claudefleet.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import dev.claudefleet.mobile.model.StatusCategory

/**
 * One row for a ticket or a task, wherever it is listed — My work and the
 * Tickets sheet drew the same ticket two different ways. The key, then the
 * title on its own lines, then the facts in one quiet line; "needs you" on
 * the right when something does.
 */
@Composable
fun WorkRow(
    label: String,
    title: String,
    details: String,
    onClick: () -> Unit,
    status: StatusCategory? = null,
    unavailable: Boolean = false,
    /** A suggestion still to review: a "?" after the key. */
    review: Boolean = false,
    needsYou: Boolean = false,
    selected: Boolean = false,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        leadingContent = status?.let { { WorkStatusDot(it) } },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (unavailable) TextDecoration.LineThrough else null,
                )
                if (review) Text(" ?", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
            }
        },
        supportingContent = if (title.isBlank() && details.isBlank()) null else {
            {
                Column {
                    if (title.isNotBlank() && title != label) {
                        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (details.isNotBlank()) {
                        Text(
                            details,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        trailingContent = if (needsYou) {
            { Text("needs you", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        } else null,
    )
}
