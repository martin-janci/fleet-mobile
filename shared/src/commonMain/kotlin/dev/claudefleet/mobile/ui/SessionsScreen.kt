package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.StatusChip

/**
 * The home screen: every session in the fleet, grouped by host and then by
 * project, with a switch for the ones that want a person.
 *
 * Stateless by design — it draws a [SessionsUiState] and reports taps. The view
 * model is what is tested; this is what only a device can show.
 */
@Composable
fun SessionsScreen(
    state: SessionsUiState,
    onOpenSession: (Long) -> Unit,
    onToggleNeedsAttention: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SessionsBar(
            needsAttentionOnly = state.needsAttentionOnly,
            attentionCount = state.attentionCount,
            refreshing = state.refreshing,
            onToggleNeedsAttention = onToggleNeedsAttention,
            onRefresh = onRefresh,
        )
        ConnectionBanner(state.status)
        ErrorBanner(state.error)

        if (state.isEmpty) {
            EmptyFleet(needsAttentionOnly = state.needsAttentionOnly)
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            for (host in state.groups) {
                item(key = "host-${host.alias}") {
                    HostHeader(alias = host.alias, reachable = host.reachable, sessions = host.sessionCount)
                }
                for (project in host.projects) {
                    item(key = "project-${host.alias}-${project.projectId ?: "none"}") {
                        ProjectHeader(project.label)
                    }
                    items(project.sessions, key = { it.id }) { row ->
                        SessionRowItem(row = row, onClick = { onOpenSession(row.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionsBar(
    needsAttentionOnly: Boolean,
    attentionCount: Int,
    refreshing: Boolean,
    onToggleNeedsAttention: () -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sessions", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(12.dp))
            // The count is of the whole fleet, so the switch says what turning
            // it on would show rather than what is showing.
            Text(
                text = if (attentionCount == 0) "Needs attention" else "Needs attention ($attentionCount)",
                style = MaterialTheme.typography.bodySmall,
            )
            Switch(checked = needsAttentionOnly, onCheckedChange = { onToggleNeedsAttention() })
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onRefresh, enabled = !refreshing) {
                Text(if (refreshing) "Refreshing…" else "Refresh")
            }
        }
    }
}

@Composable
private fun HostHeader(alias: String, reachable: Boolean?, sessions: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = alias,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            // A host that is not in `list_hosts` is unknown, not unreachable,
            // and says nothing rather than accusing it of being down.
            Text(
                text = when (reachable) {
                    true -> "$sessions session${if (sessions == 1) "" else "s"}"
                    false -> "unreachable · $sessions"
                    null -> "$sessions"
                },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun ProjectHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun SessionRowItem(row: SessionRow, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            StatusChip(claudeStatus = row.claudeStatus, stuckKind = row.stuckKind)
        }
        // The hub already writes a one-line summary of what the session is
        // doing; this screen does not second-guess it.
        val activity = row.currentActivity
        if (!activity.isNullOrBlank()) {
            Text(
                text = activity,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun EmptyFleet(needsAttentionOnly: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (needsAttentionOnly) {
                "Nothing needs you right now."
            } else {
                "No sessions. Start one from the desktop app or the terminal."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}
