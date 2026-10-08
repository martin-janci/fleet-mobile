package dev.claudefleet.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.TidyApplyResult
import dev.claudefleet.mobile.model.TidyCandidate
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.OrbitChip
import dev.claudefleet.mobile.ui.kit.SheetAction
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet

/** What the New Tidy sheet reports on top of [TidyHandlers]. */
data class PhoneTidyHandlers(
    val tidy: TidyHandlers = TidyHandlers(),
    val onUndo: (Long) -> Unit = {},
    val onRetry: (Long) -> Unit = {},
)

/**
 * Tidy up on the New bar (MobileTidyTickets). The rules list what could be
 * closed, each with its reason and the exact action, marked "Suggested by
 * rule" (clean-up readiness is on the never-decides list, so never the Jev
 * mark). Nothing is ticked when it opens; the button counts only what the
 * person ticked, and a kill is asked about first. Kill never deletes a
 * worktree.
 *
 * After Apply the same sheet shows the outcome per session: Undo on an
 * archive, Retry on what the hub refused. The list behind it has already
 * moved.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneTidySheet(state: TidyUiState, handlers: PhoneTidyHandlers) {
    val h = handlers.tidy
    var confirmKills by remember { mutableStateOf(false) }
    val results = state.results
    val count = state.ticked.count { id -> state.candidates.any { it.sessionId == id } }
    val showLoader = rememberLoaderVisible(state.loading || state.applying || state.pending.isNotEmpty())
    BottomSheet(
        title = "Tidy up",
        meta = when {
            results != null -> "Done"
            state.candidates.isEmpty() -> null
            else -> "${sessionsWord(state.candidates.size)} could be closed"
        },
        onDismiss = h.onClose,
        cancelLabel = if (results != null) "Close" else "Cancel",
        primary = if (results != null || state.candidates.isEmpty()) null else SheetAction(
            label = if (state.applying) "Applying…" else "Apply to ${sessionsWord(count)}",
            enabled = count > 0 && !state.applying,
            onClick = { if (state.kills > 0) confirmKills = true else h.onApply() },
        ),
    ) {
        val o = Fleet.colors
        Column(modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorBanner(state.error, onDismiss = h.onDismissError)
            if (showLoader) DotWave()
            if (results != null) {
                Text(
                    tidyDoneLine(results, state.undone),
                    color = o.fg,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text("The list behind this already shows it.", color = o.fgMuted, fontSize = 13.sp)
                for (r in results) ResultRow(r, state, handlers)
                if (state.candidates.isEmpty() && !state.loading) {
                    Text("Nothing left to tidy. Next suggestion when a PR merges or a ticket closes.", color = o.fgMuted, fontSize = 13.sp)
                }
            } else {
                if (state.candidates.isEmpty() && !state.loading) {
                    Text("Nothing to tidy. Next suggestion when a PR merges or a ticket closes.", color = o.fgMuted, fontSize = 14.sp)
                }
                if (state.candidates.isNotEmpty()) {
                    Text("Nothing is ticked: pick what to close.", color = o.fgMuted, fontSize = 13.sp)
                }
                for (c in state.candidates) CandidateCard(c, state, h)
                if (state.candidates.any { tidyChoices(it).any { ch -> ch == TidyChoice.Kill || ch == TidyChoice.SafeKill } }) {
                    Text("Kill never deletes the worktree; uncommitted files stay on disk.", color = o.fgMuted, fontSize = 13.sp)
                }
            }
            if (state.reopened.isNotEmpty()) {
                HorizontalDivider(color = o.border)
                Text("Came back after it was done", color = o.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                for (w in state.reopened) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(listOfNotNull(w.key, w.title.takeIf { it.isNotBlank() }).joinToString(" · "), color = o.fg, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(w.statusName, "${w.pastSessions} past session" + if (w.pastSessions == 1) "" else "s", w.lastHost).joinToString(" · "),
                                color = o.fgMuted,
                                fontSize = 12.sp,
                            )
                        }
                        TextButton(onClick = { h.onDismissReopened(w.itemId) }) { Text("Dismiss", color = o.accent) }
                    }
                }
            }
        }
    }
    if (confirmKills) {
        AlertDialog(
            onDismissRequest = { confirmKills = false },
            title = { Text("Kill ${sessionsWord(state.kills)}?") },
            text = { Text("A safe kill asks the session to commit and push first; a kill does not wait. Neither deletes the worktree. The other choices you ticked are applied too.") },
            confirmButton = { DangerTextButton(onClick = { confirmKills = false; h.onApply() }) { Text("Kill and apply") } },
            dismissButton = { TextButton(onClick = { confirmKills = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CandidateCard(c: TidyCandidate, state: TidyUiState, h: TidyHandlers) {
    val o = Fleet.colors
    var choosing by remember { mutableStateOf(false) }
    val choices = tidyChoices(c)
    val name = c.label?.takeIf { it.isNotBlank() } ?: c.tmuxName
    Row(verticalAlignment = Alignment.Top) {
        Checkbox(
            checked = c.sessionId in state.ticked,
            onCheckedChange = { h.onToggle(c.sessionId) },
            enabled = choices.isNotEmpty(),
            colors = CheckboxDefaults.colors(checkedColor = o.accent),
            modifier = Modifier.semantics { contentDescription = "Tidy $name" },
        )
        Column(modifier = Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOfNotNull(name, c.hostAlias.takeIf { it.isNotBlank() }).joinToString(" · "),
                color = o.fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { h.onOpenSession(c.sessionId) },
            )
            Text(tidyReasonLine(c), color = o.fgMuted, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    Text(
                        (state.choiceOf(c)?.label ?: "—") + if (choices.size > 1) " ▾" else "",
                        color = o.accent,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .heightIn(min = 32.dp)
                            .clickable(enabled = choices.size > 1) { choosing = true }
                            .padding(vertical = 6.dp),
                    )
                    DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        for (choice in choices) {
                            DropdownMenuItem(text = { Text(choice.label) }, onClick = { choosing = false; h.onChoose(c.sessionId, choice) })
                        }
                    }
                }
                OrbitChip("Suggested by rule")
            }
        }
    }
}

@Composable
private fun ResultRow(r: TidyApplyResult, state: TidyUiState, handlers: PhoneTidyHandlers) {
    val o = Fleet.colors
    val undone = r.sessionId in state.undone
    val busy = r.sessionId in state.pending
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(state.resultNames[r.sessionId] ?: "Session ${r.sessionId}", color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tidyOutcomeLine(r, undone), color = if (!r.ok) o.statusFailed else o.fgMuted, fontSize = 13.sp)
        }
        when {
            r.ok && !undone && r.action == TidyChoice.Archive.wire ->
                TextButton(onClick = { handlers.onUndo(r.sessionId) }, enabled = !busy) { Text("Undo", color = o.accent) }
            !r.ok -> TextButton(onClick = { handlers.onRetry(r.sessionId) }, enabled = !busy) { Text("Retry", color = o.accent) }
        }
    }
}
