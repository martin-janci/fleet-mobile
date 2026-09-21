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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.StatusChip
import dev.claudefleet.mobile.ui.theme.FleetIcons

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
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SessionsBar(
            needsAttentionOnly = state.needsAttentionOnly,
            attentionCount = state.attentionCount,
            status = state.status,
            onToggleNeedsAttention = onToggleNeedsAttention,
        )
        ConnectionBanner(state.status)
        ErrorBanner(state.error, onDismiss = onDismissError)

        if (state.isEmpty) {
            EmptyFleet(needsAttentionOnly = state.needsAttentionOnly)
            return@Column
        }

        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsBar(
    needsAttentionOnly: Boolean,
    attentionCount: Int,
    status: ConnectionStatus,
    onToggleNeedsAttention: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text("Sessions", style = MaterialTheme.typography.titleLarge)
                val live = when (status) {
                    is ConnectionStatus.Connected -> "live"
                    is ConnectionStatus.Reconnecting -> "reconnecting…"
                    is ConnectionStatus.Offline -> "offline"
                }
                Text(live, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = {
            FilterChip(
                selected = needsAttentionOnly,
                onClick = onToggleNeedsAttention,
                label = { Text("Needs attention") },
                leadingIcon = {
                    Icon(
                        FleetIcons.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                trailingIcon = if (attentionCount > 0) ({ Badge { Text("$attentionCount") } }) else null,
                modifier = Modifier.padding(end = 12.dp),
            )
        },
    )
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
