package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.CHECK_MODES
import dev.claudefleet.mobile.model.TicketCheck
import dev.claudefleet.mobile.ui.kit.OrbitChip
import dev.claudefleet.mobile.ui.kit.StatusWord

/** The chip of an AI check: a status word, then why (the desktop's `checkHeadline`). */
internal fun ticketCheckHeadline(check: TicketCheck): Pair<StatusWord, String> = when (check.state) {
    "running" -> StatusWord.WORKING to "Working · testing"
    "passed" -> StatusWord.DONE to "Done · tests passed"
    "accepted" -> StatusWord.DONE to "Done · accepted anyway"
    "failed" -> StatusWord.FAILED to "Failed · not ready"
    "error" -> StatusWord.FAILED to "Failed · could not run"
    else -> StatusWord.IDLE to "Idle · not tested yet"
}

/** A mode's words on the switch. */
internal fun checkModeLabel(mode: String): String = CHECK_MODES.firstOrNull { it.first == mode }?.second ?: mode

/**
 * The AI check on done: what the tester found for the task's finished work,
 * **Test now** / **Rerun**, **Accept anyway** with a note, and the project's
 * switch. Draws nothing when the hub has no check to show.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TicketCheckCard(state: TaskUiState, handlers: TaskHandlers, modifier: Modifier = Modifier) {
    val check = state.check ?: return
    var accepting by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var setupOpen by remember { mutableStateOf(false) }
    val (word, label) = ticketCheckHeadline(check)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI check", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            OrbitChip(label, word = word)
        }
        when (check.state) {
            "ready" -> Text("The work is finished and has not been tested yet.", style = MaterialTheme.typography.bodySmall)
            "running" -> Text("The tester is running the tests and reading the change.", style = MaterialTheme.typography.bodySmall)
        }
        check.summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        check.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        check.note?.takeIf { it.isNotBlank() }?.let { Text("Accepted: $it", style = MaterialTheme.typography.bodySmall) }
        if (check.blockers.isNotEmpty()) {
            Text("Must change", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            for (b in check.blockers) Text("• $b", style = MaterialTheme.typography.bodySmall)
        }
        if (check.testsRun.isNotEmpty()) {
            Text("Ran: ${check.testsRun.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        }
        if (!check.canRun && check.state != "running") {
            check.whyNot?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.canRunCheck) OutlinedButton(onClick = handlers.onRunCheck, enabled = !state.busy) { Text(check.runLabel) }
            if (state.canAcceptCheck && !accepting) TextButton(onClick = { accepting = true }, enabled = !state.busy) { Text("Accept anyway") }
            val setup = check.setup
            if (setup != null && state.canSetupCheck && !setupOpen) {
                TextButton(onClick = { setupOpen = true }, enabled = !state.busy) { Text("${setup.project}: ${checkModeLabel(setup.mode)}") }
            }
        }
        if (accepting) {
            OutlinedTextField(
                value = note,
                onValueChange = { note = it.take(500) },
                label = { Text("Why it is fine (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { handlers.onAcceptCheck(note); accepting = false; note = "" }, enabled = !state.busy) { Text("Accept") }
                TextButton(onClick = { accepting = false }) { Text("Cancel") }
            }
        }
        val setup = check.setup
        if (setupOpen && setup != null) {
            var mode by remember(setup) { mutableStateOf(setup.mode) }
            var command by remember(setup) { mutableStateOf(setup.command.orEmpty()) }
            Text("Test ${setup.project}'s finished work", style = MaterialTheme.typography.labelMedium)
            for ((m, words) in CHECK_MODES) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().selectable(selected = mode == m, role = Role.RadioButton, onClick = { mode = m }),
                ) {
                    RadioButton(selected = mode == m, onClick = null)
                    Text(words, style = MaterialTheme.typography.bodyMedium)
                }
            }
            OutlinedTextField(
                value = command,
                onValueChange = { command = it.take(200) },
                label = { Text("Test command") },
                placeholder = { Text("e.g. cargo test") },
                singleLine = true,
                enabled = mode != "off",
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Without one, the tester runs the ticket's test: lines. Each check is a headless Claude run and counts toward usage.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { handlers.onSetCheck(mode, command); setupOpen = false }, enabled = !state.busy) { Text("Save") }
                TextButton(onClick = { setupOpen = false }) { Text("Cancel") }
            }
        }
    }
}
