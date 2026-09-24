package dev.claudefleet.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.components.StatusChip
import dev.claudefleet.mobile.ui.components.StatusDot
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.LocalStatusColors
import dev.claudefleet.mobile.ui.theme.StatusTone

/**
 * The home screen: every session in the fleet, grouped by host and then by
 * project, with a switch for the ones that want a person.
 *
 * Stateless by design — it draws a [SessionsUiState] and reports taps. The view
 * model is what is tested; this is what only a device can show.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionsScreen(
    state: SessionsUiState,
    onOpenSession: (Long) -> Unit,
    onToggleNeedsAttention: () -> Unit,
    onClearHostFilter: () -> Unit,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        SessionsBar(status = state.status) {
            FilterRow(
                needsAttentionOnly = state.needsAttentionOnly,
                attentionCount = state.attentionCount,
                hostFilter = state.hostFilter,
                onToggleNeedsAttention = onToggleNeedsAttention,
                onClearHostFilter = onClearHostFilter,
            )
        }
        ConnectionBanner(state.status)
        ErrorBanner(state.error, onDismiss = onDismissError)

        // The empty state is INSIDE the pull-to-refresh, and inside the
        // `LazyColumn` at that. It used to return early, so the one screen a
        // person would most want to pull on — no sessions yet, is the hub
        // really up? — was the one screen that did not respond to the
        // gesture. `PullToRefreshBox` needs a scrollable child to receive the
        // drag, which a bare `Box` is not, so the message rides as a single
        // item filling the viewport.
        PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.isEmpty) {
                    item(key = "empty") {
                        EmptyFleet(
                            needsAttentionOnly = state.needsAttentionOnly,
                            hostFilter = state.hostFilter,
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                }
                for (host in state.groups) {
                    stickyHeader(key = "host-${host.alias}") {
                        HostHeader(alias = host.alias, reachable = host.reachable, sessions = host.sessionCount)
                    }
                    for (project in host.projects) {
                        item(key = "project-${host.alias}-${project.projectId ?: "none"}") {
                            ProjectHeader(project.label)
                        }
                        items(project.sessions, key = { it.id }) { row ->
                            SessionRowItem(row = row, nowSeconds = state.nowSeconds, onClick = { onOpenSession(row.id) })
                        }
                    }
                }
            }
        }
    }
}

/** The Sessions header: the title, whether the list is live, and [filters] under both. */
@Composable
private fun SessionsBar(status: ConnectionStatus, filters: @Composable () -> Unit) {
    val live = when (status) {
        is ConnectionStatus.Connected -> "live"
        is ConnectionStatus.Reconnecting -> "reconnecting…"
        is ConnectionStatus.Offline -> "offline"
        // Not "offline": the hub is up and answering. The banner under this
        // header says which side is behind.
        is ConnectionStatus.Refused -> "refused"
    }
    ScreenHeader(title = "Sessions", subtitle = live, below = { filters() })
}

/**
 * The filters, on their own line under the title, inside the header.
 *
 * They used to ride in the `TopAppBar`'s `actions`, which is a plain `Row`
 * that neither wraps nor scrolls and is measured before the title: on a phone,
 * the moment a host filter joined the "Needs attention" chip the two overflowed
 * the bar and pushed the title clean off the screen. Here they own the full
 * width and *wrap* — a `FlowRow`, not a `Row`, because on a 320dp screen the
 * two chips genuinely do not fit side by side and the second one belongs on a
 * second line rather than half past the right edge. A long alias is capped so
 * one chip can never be the whole line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(
    needsAttentionOnly: Boolean,
    attentionCount: Int,
    hostFilter: String?,
    onToggleNeedsAttention: () -> Unit,
    onClearHostFilter: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
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
        )
        if (hostFilter != null) {
            InputChip(
                selected = true,
                onClick = onClearHostFilter,
                label = { Text("host: $hostFilter", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingIcon = {
                    Icon(
                        FleetIcons.Close,
                        contentDescription = "Clear host filter",
                        modifier = Modifier.size(InputChipDefaults.IconSize),
                    )
                },
                modifier = Modifier.widthIn(max = 220.dp),
            )
        }
    }
}

@Composable
private fun HostHeader(alias: String, reachable: Boolean?, sessions: Int) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp),
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
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun SessionRowItem(row: SessionRow, nowSeconds: Long, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { StatusDot(row.claudeStatus, row.stuckKind) },
        headlineContent = {
            Column {
                Text(row.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val pct = row.contextPct
                if (pct != null) {
                    LinearProgressIndicator(
                        progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) },
                        // Padding FIRST. `Modifier` applies left to right, so
                        // `.height(2.dp).padding(top = 2.dp)` sized the bar to
                        // 2 dp and then spent both of them on padding: a
                        // context meter that measured to zero and drew
                        // nothing. Padding first pads a 2 dp bar instead.
                        modifier = Modifier.padding(top = 2.dp).width(60.dp).height(2.dp),
                        color = if (pct >= 80) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            val line = row.supportingLine
            if (line != null) Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                StatusChip(claudeStatus = row.claudeStatus, stuckKind = row.stuckKind)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    relativeTime(row.lastActivityAt, nowSeconds)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    row.ciStatus?.let { ci ->
                        val tone = when (ci) { "passing" -> StatusTone.COMPLETED; "failing" -> StatusTone.FAILED; else -> StatusTone.IDLE }
                        Box(Modifier.padding(start = 4.dp).size(6.dp).clip(CircleShape).background(LocalStatusColors.current(tone).dot))
                    }
                }
            }
        },
    )
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}

@Composable
private fun EmptyFleet(needsAttentionOnly: Boolean, hostFilter: String?, modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = when {
                // Both filters on and nothing matches: name what is actually
                // being asked for, rather than the host-only message that
                // used to win here and said nothing about attention at all.
                hostFilter != null && needsAttentionOnly -> "Nothing on $hostFilter needs you"
                hostFilter != null -> "No sessions on $hostFilter"
                needsAttentionOnly -> "Nothing needs you right now."
                else -> "No sessions. Start one from the desktop app or the terminal."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}
