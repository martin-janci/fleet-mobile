package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.relativeWithin
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.RadioButton
import dev.claudefleet.mobile.model.HostRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.InlineLoading
import dev.claudefleet.mobile.ui.kit.SheetAction

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
fun MoveSheet(state: MoveUiState, handlers: MoveHandlers, nowSeconds: Long, orbit: Boolean = false) {
    var confirming by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).verticalScroll(rememberScrollState())) {
            Text("Move to host", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                "Its uncommitted and unpushed work, small git-ignored files, its Claude conversation and memory go with it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            InlineLoading(waiting = state.busy, modifier = Modifier.padding(vertical = 4.dp))
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
            if (orbit) {
                OrbitMoveBody(state, handlers, onMove = { confirming = true })
                return@Column
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
        // A sheet, not a dialog (MobileFormsSession): Cancel and Move at the thumb.
        BottomSheet(
            title = "Move to ${state.target}?",
            meta = if (state.keepSource) "A copy starts on ${state.target}; this session keeps running."
            else "The session starts on ${state.target} and this one stops once the new one is confirmed.",
            onDismiss = { confirming = false },
            primary = SheetAction("Move") { confirming = false; handlers.onMove() },
        ) {}
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

/** One host's facts for the New bar's Move sheet: how it is reached and what runs there. */
internal fun moveHostFacts(host: HostRow, running: Int): String = buildList {
    if (host.transport == "agent") add("agent")
    add("$running running")
}.joinToString(" · ")

/** Why an offline host is listed but cannot be picked. */
internal const val MOVE_OFFLINE = "Signal lost · cannot move there now"

/** The New bar's Move button: it names the missing choice until a host is picked. */
internal fun moveButtonLabel(target: String?, whenIdle: Boolean): String = when {
    target == null -> "Choose a host"
    whenIdle -> "Move when idle"
    else -> "Move"
}

/**
 * Move on the New bar (redesign 14.5, MobileRecovery): every shown host as
 * a row with its facts and nothing pre-selected; an offline host listed with
 * why it cannot be picked; what the move would carry once a host is picked;
 * and a button that names the missing choice. Host choice by numbers is not
 * a decision for Jev, so the sheet shows facts and proposes nothing.
 */
@Composable
private fun OrbitMoveBody(state: MoveUiState, handlers: MoveHandlers, onMove: () -> Unit) {
    Spacer(Modifier.height(8.dp))
    if (state.targets.isEmpty() && state.offline.isEmpty()) {
        Text("No other host.", style = MaterialTheme.typography.bodySmall)
    }
    for (h in state.targets) {
        HostOption(
            alias = h.alias,
            facts = moveHostFacts(h, state.running[h.alias] ?: 0),
            selected = h.alias == state.target,
            enabled = !state.busy && state.waiting == null,
            onClick = { handlers.onTarget(h.alias) },
        )
    }
    for (h in state.offline) {
        HostOption(alias = h.alias, facts = MOVE_OFFLINE, selected = false, enabled = false, onClick = {})
    }
    state.preview?.let { PreviewLines(it) }
    Toggle("Keep the source running", "Leave this session as it is once the moved one is up.", state.keepSource, handlers.onKeepSource)
    Toggle("Wait until it is idle", "Let the current turn end, then move.", state.whenIdle, handlers.onWhenIdle)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = handlers.onClose) { Text("Cancel") }
        Spacer(Modifier.weight(1f))
        Button(
            onClick = onMove,
            enabled = state.target != null && !state.busy && state.waiting == null,
        ) { Text(moveButtonLabel(state.target, state.whenIdle)) }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun HostOption(alias: String, facts: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
            val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            Text(alias, style = MaterialTheme.typography.bodyLarge, color = tint)
            Text(facts, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
