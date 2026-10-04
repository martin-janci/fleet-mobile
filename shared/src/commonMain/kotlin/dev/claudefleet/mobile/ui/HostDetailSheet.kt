package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.LostCandidate
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.components.ErrorBanner

data class HostDetailHandlers(
    val onClose: () -> Unit = {},
    val onProbe: () -> Unit = {},
    val onShowSessions: () -> Unit = {},
    val onCheckLost: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onResume: (LostCandidate) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * One host: what the hub knows about it and a re-probe; what a reboot took
 * from it — the sessions fleet can restore (a plan, then Restore, asked
 * first) and the conversations it has no row for (each resumable into a new
 * session).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostDetailSheet(state: HostDetailUiState, handlers: HostDetailHandlers, nowSeconds: Long) {
    var confirming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                val host = state.host
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                    Text(state.alias.orEmpty(), style = MaterialTheme.typography.titleLarge)
                    Text(
                        listOfNotNull(
                            if (host?.reachable == true) "reachable" else "unreachable",
                            host?.transport?.takeIf { it != "ssh" },
                            relativeTime(host?.lastPingedAt, nowSeconds)?.let { "pinged $it ago" },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        listOfNotNull(host?.claudeVersion?.let { "claude $it" }, host?.tmuxVersion?.let { "tmux $it" })
                            .joinToString(" · ").ifEmpty { "not probed yet" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        if (state.canProbe) {
                            OutlinedButton(onClick = handlers.onProbe, enabled = !state.probing) { Text(if (state.probing) "Probing…" else "Re-probe") }
                        }
                        OutlinedButton(onClick = handlers.onShowSessions) {
                            Text("${state.sessionCount} session${if (state.sessionCount == 1) "" else "s"}")
                        }
                    }
                }
                ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            }
            if (state.canRestore || state.canDiscover) {
                item {
                    HorizontalDivider()
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("After a reboot", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = handlers.onCheckLost) { Text("Check again") }
                    }
                }
            }
            if (state.canRestore) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                        val plan = state.plan
                        when {
                            plan == null -> Text("Looking for lost sessions…", style = MaterialTheme.typography.bodySmall)
                            plan.plan.isEmpty() -> Text("No lost sessions to restore.", style = MaterialTheme.typography.bodySmall)
                            else -> {
                                for (entry in plan.plan) {
                                    Text(
                                        entry.name + if (entry.restores) "" else " — ${entry.action}" + (entry.reason?.let { ": $it" } ?: ""),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (state.restorable > 0) {
                                    OutlinedButton(
                                        onClick = { confirming = true },
                                        enabled = !state.restoring,
                                        modifier = Modifier.padding(top = 8.dp),
                                    ) { Text(if (state.restoring) "Restoring…" else "Restore ${sessionsWord(state.restorable)}") }
                                }
                            }
                        }
                        state.restored?.results?.takeIf { it.isNotEmpty() }?.let { results ->
                            val ok = results.count { it.ok }
                            Text("Restored $ok of ${results.size}.", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                            for (r in results.filter { !it.ok }) {
                                Text("${r.tmuxName}: ${r.error ?: "failed"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            if (state.canDiscover) {
                val resumable = state.resumable
                item {
                    Text(
                        if (state.candidates == null) "Looking for untracked conversations…"
                        else if (resumable.isEmpty()) "No untracked conversations to resume."
                        else "Conversations fleet has no session for",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                    )
                }
                items(resumable, key = { it.claudeSessionId }) { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.cwd, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(c.gitBranch, c.rankHint.replace('_', ' ').takeIf { it.isNotBlank() }, relativeTime(c.transcriptMtime, nowSeconds)?.let { "$it ago" })
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (state.canRestore) {
                            TextButton(onClick = { handlers.onResume(c) }, enabled = state.resuming == null) {
                                Text(if (state.resuming == c.claudeSessionId) "Starting…" else "Resume")
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Restore ${sessionsWord(state.restorable)}?") },
            text = { Text("Each resumes its conversation in its own worktree, under its own name, a few at a time.") },
            confirmButton = { TextButton(onClick = { confirming = false; handlers.onRestore() }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}
