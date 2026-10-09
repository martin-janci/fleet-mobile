package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.relativeAgo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.kit.MarkMotion
import dev.claudefleet.mobile.ui.kit.OrbitMarkLoader
import dev.claudefleet.mobile.ui.kit.OrbitPullToRefresh
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible

/**
 * The machines: reachability, the versions the hub found, and how many sessions
 * are on each.
 *
 * Nothing here is a control. Adding, removing and hiding a host are fleet
 * administration, which the hub refuses a client token, so the screen does not
 * draw a button the app would only be told no about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostsScreen(
    state: HostsUiState,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    onOpenHost: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** A host's sheet (re-probe, recovery after a reboot); null where the hub offers none of it. */
    onHostDetails: ((String) -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "Hosts")
        ConnectionBanner(state.status)
        ErrorBanner(state.error, onDismiss = onDismissError)

        // Inside the pull-to-refresh, not instead of it: an empty host list is
        // exactly when a person pulls to ask whether the hub is answering, and
        // an early return made that gesture do nothing. `OrbitPullToRefresh`
        // takes the drag through a scrollable child, so the message is a
        // single item filling the viewport rather than a bare `Box`.
        OrbitPullToRefresh(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (state.isEmpty) {
                    item(key = "empty") {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            if (state.connecting) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    if (rememberLoaderVisible(true)) OrbitMarkLoader(MarkMotion.Chase, size = 48.dp)
                                    Text(
                                        "Connecting to the hub…",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(16.dp),
                                    )
                                }
                            } else Text(
                                text = "No hosts. Add one from the desktop app or the terminal.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(32.dp),
                            )
                        }
                    }
                }
                items(state.hosts, key = { it.alias }) { host ->
                    HostLineItem(host, onClick = { onOpenHost(host.alias) }, onDetails = onHostDetails?.let { { it(host.alias) } })
                }
            }
        }
    }
}

@Composable
private fun HostLineItem(host: HostLine, onClick: () -> Unit, onDetails: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.weight(1f)
                // The host's own sheet where the hub has one — it offers Show
                // sessions too — so a tap stays on this tab rather than jumping
                // to another one that Back would then leave the app from.
                .clickable(
                    onClickLabel = if (onDetails != null) "Open ${host.alias}" else "Show sessions on ${host.alias}",
                    onClick = onDetails ?: onClick,
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = host.alias,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Reachability(host)
            }
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "${host.sessions} session${if (host.sessions == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Only worth a word when it is not the ordinary case.
                if (host.transport != "ssh") {
                    Text(
                        text = host.transport,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (host.hidden) {
                    Text(
                        text = "hidden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // A host that has never been probed has no versions, and saying so is
            // different from saying it has none.
            val versions = listOfNotNull(
                host.claudeVersion?.let { "claude $it" },
                host.tmuxVersion?.let { "tmux $it" },
            )
            Text(
                text = if (versions.isEmpty()) "not probed yet" else versions.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun Reachability(host: HostLine) {
    val (label, color) = if (host.reachable) {
        "reachable" to MaterialTheme.colorScheme.primary
    } else {
        "unreachable" to MaterialTheme.colorScheme.error
    }
    Text(text = label, style = MaterialTheme.typography.labelMedium, color = color)
    // Since when: a host gone a minute is not one gone for days.
    if (!host.reachable) {
        relativeAgo(host.lastPingedAt, epochSeconds())?.let {
            Text(
                " · seen $it",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
