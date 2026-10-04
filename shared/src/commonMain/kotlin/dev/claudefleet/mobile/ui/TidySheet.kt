package dev.claudefleet.mobile.ui

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import dev.claudefleet.mobile.ui.components.DangerTextButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.ReopenedWork
import dev.claudefleet.mobile.model.TidyCandidate
import dev.claudefleet.mobile.ui.components.ErrorBanner

data class TidyHandlers(
    val onClose: () -> Unit = {},
    val onToggle: (Long) -> Unit = {},
    val onChoose: (Long, TidyChoice) -> Unit = { _, _ -> },
    val onApply: () -> Unit = {},
    val onOpenSession: (Long) -> Unit = {},
    val onDismissReopened: (Long) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * Tidy-up as a sheet: the candidates by reason, each ticked or not with the
 * choice it will get (tap the choice to change it), and Apply — asked first
 * when it kills anything. Then the outcome, and work that came back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TidySheet(state: TidyUiState, handlers: TidyHandlers) {
    var confirmKills by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Tidy up", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() })
            if (state.loading || state.applying) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp))
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            state.results?.let { results ->
                val failed = results.filter { !it.ok }
                Text(
                    "Applied ${results.size - failed.size} of ${results.size}." + if (failed.isNotEmpty()) " Not done: " + failed.joinToString { r -> tidyFailureLine(r.sessionId, r.error, r.action, state.candidates) } else "",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                if (state.candidates.isEmpty() && !state.loading) {
                    item { Text("Nothing to tidy.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp)) }
                }
                for ((reason, items) in tidyGroups(state.candidates)) {
                    item(key = "reason:$reason") {
                        Text(tidyReasonLabel(reason), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, top = 12.dp, bottom = 4.dp))
                    }
                    items(items, key = { "cand:${it.sessionId}" }) { c -> CandidateRow(c, state, handlers) }
                }
                if (state.reopened.isNotEmpty()) {
                    item {
                        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                        Text("Came back after it was done", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 24.dp, top = 12.dp))
                    }
                    items(state.reopened, key = { "reopened:${it.itemId}" }) { ReopenedRow(it, handlers) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            HorizontalDivider()
            val count = state.ticked.count { id -> state.candidates.any { it.sessionId == id } }
            Button(
                onClick = { if (state.kills > 0) confirmKills = true else handlers.onApply() },
                enabled = count > 0 && !state.applying,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            ) { Text(if (state.applying) "Applying…" else "Apply to ${sessionsWord(count)}") }
        }
    }
    if (confirmKills) {
        AlertDialog(
            onDismissRequest = { confirmKills = false },
            title = { Text("Kill ${sessionsWord(state.kills)}?") },
            text = { Text("A safe kill asks the session to commit and push first; a kill does not wait. The rest of the choices are applied too.") },
            confirmButton = { DangerTextButton(onClick = { confirmKills = false; handlers.onApply() }) { Text("Kill and apply") } },
            dismissButton = { TextButton(onClick = { confirmKills = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CandidateRow(c: TidyCandidate, state: TidyUiState, handlers: TidyHandlers) {
    var choosing by remember { mutableStateOf(false) }
    val choices = tidyChoices(c)
    Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // The row's words open the session, so the box carries its own name.
        val name = c.label?.takeIf { it.isNotBlank() } ?: c.tmuxName
        Checkbox(
            checked = c.sessionId in state.ticked,
            onCheckedChange = { handlers.onToggle(c.sessionId) },
            enabled = choices.isNotEmpty(),
            modifier = Modifier.semantics { contentDescription = "Tidy $name" },
        )
        Column(modifier = Modifier.weight(1f).clickable { handlers.onOpenSession(c.sessionId) }) {
            Text(c.label?.takeIf { it.isNotBlank() } ?: c.tmuxName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(c.hostAlias, c.key, c.idleSecs?.let { "idle ${formatIdle(it)}" }).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            TextButton(onClick = { choosing = true }, enabled = choices.size > 1) { Text(state.choiceOf(c)?.label ?: "—") }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                for (choice in choices) {
                    DropdownMenuItem(text = { Text(choice.label) }, onClick = { choosing = false; handlers.onChoose(c.sessionId, choice) })
                }
            }
        }
    }
}

@Composable
private fun ReopenedRow(w: ReopenedWork, handlers: TidyHandlers) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(listOfNotNull(w.key, w.title.takeIf { it.isNotBlank() }).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(w.statusName, "${w.pastSessions} past session" + if (w.pastSessions == 1) "" else "s", w.lastHost).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { handlers.onDismissReopened(w.itemId) }) { Text("Dismiss") }
    }
}

/** 90 → "1 min", 7200 → "2 h", 200000 → "2 d" — as the desktop's `formatIdle`. */
internal fun formatIdle(secs: Long): String {
    val s = secs.coerceAtLeast(0)
    return when {
        s >= 86_400 -> "${s / 86_400} d"
        s >= 3_600 -> "${s / 3_600} h"
        else -> "${(s / 60).coerceAtLeast(1)} min"
    }
}
