package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.relativeWithin
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.MovePreview
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.ui.components.ErrorBanner

data class MoveHandlers(
    val onClose: () -> Unit = {},
    val onTarget: (String) -> Unit = {},
    val onKeepSource: (Boolean) -> Unit = {},
    val onWhenIdle: (Boolean) -> Unit = {},
    val onMove: () -> Unit = {},
    val onCancelWait: () -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/** Move to host: where, what the move carries (a dry run), how, and Move — asked first. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MoveSheet(state: MoveUiState, handlers: MoveHandlers, nowSeconds: Long) {
    var confirming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Move to another host", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                "Its uncommitted and unpushed work, small git-ignored files, its Claude conversation and memory go with it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            state.waiting?.let { w ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        "Moving to ${w.toHost} once it is idle" + (relativeWithin(w.deadlineUnix, nowSeconds)?.let { " (within $it)" } ?: "") + ".",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = handlers.onCancelWait, enabled = !state.busy) { Text("Cancel the wait") }
                }
            }
            Text("To", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            if (state.targets.isEmpty()) {
                Text("No other reachable host.", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in state.targets) {
                    FilterChip(selected = h.alias == state.target, onClick = { handlers.onTarget(h.alias) }, label = { Text(h.alias) })
                }
            }
            state.preview?.let { PreviewLines(it) }
            Toggle("Keep the source running", "Leave this session as it is once the moved one is up.", state.keepSource, handlers.onKeepSource)
            Toggle("When it is idle", "Wait for the current turn to end, then move.", state.whenIdle, handlers.onWhenIdle)
            Button(
                onClick = { confirming = true },
                enabled = state.target != null && !state.busy && state.waiting == null,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            ) { Text(if (state.whenIdle) "Move when idle" else "Move now") }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Move to ${state.target}?") },
            text = {
                Text(
                    if (state.keepSource) "A copy starts on ${state.target}; this session keeps running."
                    else "The session starts on ${state.target} and this one stops once the new one is confirmed.",
                )
            },
            confirmButton = { TextButton(onClick = { confirming = false; handlers.onMove() }) { Text("Move") } },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PreviewLines(p: MovePreview) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text("It would carry", style = MaterialTheme.typography.titleSmall)
        val lines = buildList {
            add("branch ${p.branch}")
            p.unpushedCommits?.takeIf { it > 0 }?.let { add("$it unpushed commit" + if (it == 1) "" else "s") }
            if (p.dirty.isNotEmpty()) add("${p.dirty.size} uncommitted change" + if (p.dirty.size == 1) "" else "s")
            if (p.ignoredCarried.isNotEmpty()) add("${p.ignoredCarried.size} git-ignored file" + if (p.ignoredCarried.size == 1) "" else "s")
            if (p.ignoredLeftBehind.isNotEmpty()) add("${p.ignoredLeftBehind.size} left behind (too large or not a file)")
            add("target ${p.targetPath.ifBlank { "worktree" }}: " + when (p.target.state) {
                "absent" -> "new"
                "clean" -> "already there, clean"
                "dirty" -> "already there, with changes"
                else -> "could not be checked"
            })
        }
        for (l in lines) Text("• $l", style = MaterialTheme.typography.bodySmall)
        for (u in p.unknowns) Text("⚠ $u", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Toggle(title: String, help: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = on, role = Role.Switch, onValueChange = onChange).padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = null)
    }
}
