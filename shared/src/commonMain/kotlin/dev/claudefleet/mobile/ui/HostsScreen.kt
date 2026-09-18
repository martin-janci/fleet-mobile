package dev.claudefleet.mobile.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner

/**
 * The machines: reachability, the versions the hub found, and how many sessions
 * are on each.
 *
 * Nothing here is a control. Adding, removing and hiding a host are fleet
 * administration, which the hub refuses a client token, so the screen does not
 * draw a button the app would only be told no about.
 */
@Composable
fun HostsScreen(
    state: HostsUiState,
    onRefresh: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Hosts", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRefresh, enabled = !state.refreshing) {
                    Text(if (state.refreshing) "Refreshing…" else "Refresh")
                }
            }
        }
        ConnectionBanner(state.status)
        ErrorBanner(state.error, onDismiss = onDismissError)

        if (state.isEmpty) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No hosts. Add one from the desktop app or the terminal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.hosts, key = { it.alias }) { host -> HostLineItem(host) }
        }
    }
}

@Composable
private fun HostLineItem(host: HostLine) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
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
}
