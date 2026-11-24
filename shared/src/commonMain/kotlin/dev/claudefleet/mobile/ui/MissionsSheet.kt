package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.MissionCard
import dev.claudefleet.mobile.model.MissionStep
import dev.claudefleet.mobile.model.SpendAsk
import dev.claudefleet.mobile.model.dollars
import dev.claudefleet.mobile.model.isQuestion
import dev.claudefleet.mobile.model.key
import dev.claudefleet.mobile.model.line
import dev.claudefleet.mobile.model.options
import dev.claudefleet.mobile.model.pauseMove
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.InlineLoading

data class MissionsHandlers(
    val onClose: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onBack: () -> Unit = {},
    /** One step; null takes every next step. */
    val onStart: (MissionStep?) -> Unit = {},
    val onDecide: (MissionCard, Boolean, String?) -> Unit = { _, _, _ -> },
    /** Start a draft, pause a running mission or resume a paused one ([pauseMove]). */
    val onTogglePause: () -> Unit = {},
    /** Throw a draft away, after its confirm. */
    val onDiscardDraft: () -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** The spend ask's two answers (redesign 14.16); each only ever from a tap. */
    val onApproveSpend: (SpendAsk) -> Unit = {},
    val onDenySpend: (SpendAsk) -> Unit = {},
    /** Sign the grant the mission waits on: level, hours, budget in dollars (null: no cap). Only from the review sheet's Sign (G5.7). */
    val onSignGrant: (Int, Int, Long?) -> Unit = { _, _, _ -> },
    /** Try a failed task again (G5.7). */
    val onRetryItem: (Long) -> Unit = {},
    /** Open one of the mission's tasks (a work item's id); null leaves its rows plain. */
    val onOpenItem: ((Long) -> Unit)? = null,
    /** Open one of the mission's sessions by its id; null draws no Session buttons. */
    val onOpenSession: ((Long) -> Unit)? = null,
    /** "Steer this mission" through Control (claude-fleet #921); null draws no composer. */
    val steer: MissionSteerUi? = null,
)

/**
 * One mission as a sheet: its next steps (Go), its cards (Apply / Dismiss, a
 * question answered in words) and the autonomy that applies, with Pause or
 * Resume. A step naming a task opens that task; Sessions opens the mission's
 * live ones. Drawn by [OrbitMissionDetail]; the list of missions is the
 * Missions screen's, never this sheet's (review w08-6), so until the mission
 * reads the sheet shows only its spinner or why the read failed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionsSheet(state: MissionsUiState, handlers: MissionsHandlers) {
    ModalBottomSheet(onDismissRequest = handlers.onClose) {
        val detail = state.detail
        // One mission is drawn as the MobileControl board's panel.
        if (detail != null) {
            OrbitMissionDetail(detail, state, handlers)
            return@ModalBottomSheet
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = handlers.onBack) { Text("‹ Missions") }
                Spacer(Modifier.weight(1f))
            }
            InlineLoading(waiting = state.loading || state.busy != null, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            Outcome(state)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
internal fun Outcome(state: MissionsUiState) {
    val text = state.results?.let { results ->
        if (results.isEmpty()) {
            "Nothing to take now."
        } else {
            results.joinToString("\n") { r -> (if (r.ok) "✓ " else "✗ ") + r.step.line() + if (r.detail.isNotBlank()) " — ${r.detail}" else "" }
        }
    } ?: state.notice ?: return
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * One next step. `showGo = false` leaves the button out where this pairing
 * may not press a step at all (`mission_start` not listed): a Go that is
 * never enabled explains nothing, so the detail says why in one line instead.
 */
@Composable
internal fun StepRow(s: MissionStep, item: String?, enabled: Boolean, handlers: MissionsHandlers, showGo: Boolean = true) {
    Row(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        // A step that names a task opens it; Go stays the step's own button.
        val open = s.itemId?.let { id -> handlers.onOpenItem?.let { o -> { o(id) } } }
        Column(modifier = Modifier.weight(1f).openable(open).testTag("$MISSION_STEP_TAG${s.key()}")) {
            Text(s.line(), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            item?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        // Go on an ask puts its question in the cards, where it is answered.
        if (showGo) TextButton(onClick = { handlers.onStart(s) }, enabled = enabled) { Text("Go") }
    }
}

@Composable
internal fun CardRow(c: MissionCard, state: MissionsUiState, mayChange: Boolean, handlers: MissionsHandlers) {
    // Saveable: a LazyColumn item leaves composition when scrolled away,
    // and a typed answer must survive that (review w16-6).
    var answer by rememberSaveable(c.id) { mutableStateOf("") }
    val enabled = state.canDecide && mayChange && state.busy == null
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
        Text(c.line(), style = MaterialTheme.typography.bodyMedium)
        Text(
            listOfNotNull(c.source, c.note?.takeIf { it.isNotBlank() }).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (c.isQuestion && state.canDecide && mayChange) {
            val options = c.options
            if (options.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (o in options) TextButton(onClick = { answer = o }, enabled = enabled) { Text(o) }
                }
            }
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                label = { Text("Answer") },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.canDecide && mayChange) {
            Row {
                TextButton(
                    onClick = { handlers.onDecide(c, true, answer.takeIf { c.isQuestion }) },
                    enabled = enabled && (!c.isQuestion || answer.isNotBlank()),
                ) { Text(if (c.isQuestion) "Answer" else "Apply") }
                TextButton(onClick = { handlers.onDecide(c, false, null) }, enabled = enabled) { Text("Dismiss") }
            }
        }
    }
}

/** What a screen change does to a mission waiting to be put back up. */
enum class MissionReturn { REOPEN, KEEP, FORGET }

/**
 * A task or a session opened from a mission's detail closes the sheet; back
 * on [under], the screen the sheet was over, the mission comes up again.
 * While a task, a session or its worktree shows, it waits; anywhere else (a
 * tab tapped) it is forgotten, so a later visit does not reopen it.
 */
internal fun missionReturn(screen: Screen, under: Screen): MissionReturn = when {
    screen == under -> MissionReturn.REOPEN
    screen is Screen.Task || screen is Screen.Session || screen is Screen.Repo -> MissionReturn.KEEP
    else -> MissionReturn.FORGET
}
