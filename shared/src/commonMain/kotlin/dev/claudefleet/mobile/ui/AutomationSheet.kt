package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.Routine
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.PhoneRow
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet

data class AutomationHandlers(
    val onClose: () -> Unit = {},
    val onTab: (AutomationTab) -> Unit = {},
    val onSelect: (Long) -> Unit = {},
    val onBack: () -> Unit = {},
    val onToggle: (Routine) -> Unit = {},
    val onSetPaused: (Boolean) -> Unit = {},
    val onOpenSession: (Long) -> Unit = {},
    /** Missions live beside the routines; null when the hub keeps none. */
    val onOpenMissions: (() -> Unit)? = null,
    val onDismissError: () -> Unit = {},
)

/**
 * Automation on the New bar (redesign 8.9): Pause all at the top, then the
 * routines with their switch, or the runs they made. A routine opens to its
 * own last runs; a run with a session opens it. Writing a routine stays on
 * the desktop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationSheet(state: AutomationUiState, nowSeconds: Long, handlers: AutomationHandlers) {
    var confirmPause by remember { mutableStateOf(false) }
    val showLoader = rememberLoaderVisible(state.loading || state.busy != null)
    val detail = state.detail
    BottomSheet(
        title = detail?.routine?.name ?: "Automation",
        meta = if (detail == null) automationLine(state.routines, state.paused) else null,
        onDismiss = handlers.onClose,
        cancelLabel = "Close",
    ) {
        val o = Fleet.colors
        Column(modifier = Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorBanner(state.error, onDismiss = handlers.onDismissError)
            if (showLoader) DotWave()
            state.notice?.let {
                Text(it, color = o.fg, fontSize = 14.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (detail != null) {
                TextButton(onClick = handlers.onBack) { Text("‹ Routines", color = o.accent) }
                RoutineRow(detail.routine, nowSeconds, state, handlers, canToggle = state.canToggle && detail.mayChange, open = false)
                HorizontalDivider(color = o.border)
                Text("Last runs · each run opens as a session", color = o.fgMuted, fontSize = 13.sp)
                if (detail.runs.isEmpty() && !state.loading) Text("No runs yet.", color = o.fgMuted, fontSize = 14.sp)
                detail.runs.forEachIndexed { i, run ->
                    PhoneRow(
                        title = runTitle(run),
                        line = runLine(run, nowSeconds),
                        word = runWord(run),
                        // The title already says Done or Failed when the run gave no reason.
                        lead = runWord(run)?.label?.takeIf { run.reason != null },
                        divider = i < detail.runs.lastIndex,
                        onClick = run.sessionId?.let { id -> { handlers.onOpenSession(id) } },
                    )
                }
            } else {
                AutomationList(state, nowSeconds, handlers, onPause = { confirmPause = true })
            }
        }
    }
    if (confirmPause) {
        AlertDialog(
            onDismissRequest = { confirmPause = false },
            title = { Text("Pause all automation?") },
            text = {
                Text(
                    "Routines stop starting runs, missions stop starting steps, and the background jobs that act on their own stand still. " +
                        "Sessions already working finish their current turn. Resume here or on the desktop; nothing is lost.",
                )
            },
            confirmButton = { DangerTextButton(onClick = { confirmPause = false; handlers.onSetPaused(true) }) { Text("Pause all") } },
            dismissButton = { TextButton(onClick = { confirmPause = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PauseCard(state: AutomationUiState, onPause: () -> Unit, onResume: () -> Unit) {
    val o = Fleet.colors
    val paused = state.paused == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (paused) o.waitingSoft else o.bgRaise, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            when (state.paused) {
                true -> "Paused. Nothing starts on its own."
                false -> "Running. Routines and missions start on their own."
                null -> "Automation"
            },
            color = o.fg,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        if (state.canPause && state.paused != null) {
            val enabled = state.busy == null
            if (paused) {
                TextButton(onClick = onResume, enabled = enabled) { Text("Resume", color = o.accent) }
            } else {
                DangerTextButton(onClick = onPause, enabled = enabled) { Text("Pause all") }
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val o = Fleet.colors
    TextButton(onClick = onClick) {
        Text(label, color = if (selected) o.fg else o.fgMuted, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun RoutineRow(
    r: Routine,
    nowSeconds: Long,
    state: AutomationUiState,
    handlers: AutomationHandlers,
    canToggle: Boolean,
    open: Boolean,
) {
    val o = Fleet.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .then(if (open) Modifier.clickable { handlers.onSelect(r.id) } else Modifier)
                .padding(vertical = 8.dp),
        ) {
            Text(r.name, color = o.fg, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(routineLine(r, nowSeconds), color = if (r.pausedReason != null && !r.enabled) o.statusFailed else o.fgMuted, fontSize = 13.sp)
        }
        Switch(
            checked = r.enabled,
            onCheckedChange = if (canToggle) ({ handlers.onToggle(r) }) else null,
            enabled = canToggle && state.busy == null,
            modifier = Modifier.semantics { contentDescription = "${r.name} on" },
        )
    }
}

/** The sheet's front: Pause all, the two tabs, and Missions below them. */
@Composable
private fun AutomationList(state: AutomationUiState, nowSeconds: Long, handlers: AutomationHandlers, onPause: () -> Unit) {
    val o = Fleet.colors
    PauseCard(state, onPause = onPause, onResume = { handlers.onSetPaused(false) })
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TabButton("Routines ${state.routines.size}", state.tab == AutomationTab.Routines) { handlers.onTab(AutomationTab.Routines) }
        TabButton("Runs", state.tab == AutomationTab.Runs) { handlers.onTab(AutomationTab.Runs) }
    }
    when (state.tab) {
        AutomationTab.Routines -> {
            if (state.routines.isEmpty() && !state.loading) {
                Text("No routines yet. Make one on the desktop: Automation, New routine.", color = o.fgMuted, fontSize = 14.sp)
            }
            for (r in state.routines) RoutineRow(r, nowSeconds, state, handlers, canToggle = state.canToggle, open = true)
        }
        AutomationTab.Runs -> {
            if (state.runs.isEmpty() && !state.loading) Text("No runs yet.", color = o.fgMuted, fontSize = 14.sp)
            state.runs.forEachIndexed { i, line ->
                PhoneRow(
                    title = line.routine,
                    line = listOfNotNull(runTitle(line.run).takeIf { line.run.reason != null || runWord(line.run) == null }, runLine(line.run, nowSeconds)).joinToString(" · "),
                    word = runWord(line.run),
                    divider = i < state.runs.lastIndex,
                    onClick = line.run.sessionId?.let { id -> { handlers.onOpenSession(id) } },
                )
            }
        }
    }
    handlers.onOpenMissions?.let { openMissions ->
        HorizontalDivider(color = o.border)
        PhoneRow(title = "Missions", line = "Plans the loop works through, step by step", dot = false, divider = false, onClick = openMissions)
    }
}
