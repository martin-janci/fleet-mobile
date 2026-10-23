package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.claudefleet.mobile.model.AgentRule
import dev.claudefleet.mobile.model.AgentRunForm
import dev.claudefleet.mobile.model.RUN_COMPONENTS
import dev.claudefleet.mobile.model.RUN_REQUEST_MAX
import dev.claudefleet.mobile.model.RUN_WINDOWS
import dev.claudefleet.mobile.model.SelfDebugFinding
import dev.claudefleet.mobile.model.ruleRunnable
import dev.claudefleet.mobile.model.severityRank
import dev.claudefleet.mobile.model.FleetRun
import dev.claudefleet.mobile.model.PhaseState
import dev.claudefleet.mobile.model.Routine
import dev.claudefleet.mobile.model.ruleAgentName
import dev.claudefleet.mobile.model.ruleLine
import dev.claudefleet.mobile.model.RoutineExplain
import dev.claudefleet.mobile.model.RoutineMemory
import dev.claudefleet.mobile.model.notebookChars
import dev.claudefleet.mobile.model.RoutineNotebook
import dev.claudefleet.mobile.model.dollars
import dev.claudefleet.mobile.model.phaseRail
import dev.claudefleet.mobile.model.runHasTrace
import dev.claudefleet.mobile.model.tookWords
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.DotWave
import dev.claudefleet.mobile.ui.kit.PhoneRow
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible
import dev.claudefleet.mobile.ui.theme.Fleet
import kotlinx.coroutines.delay

data class AutomationHandlers(
    val onClose: () -> Unit = {},
    val onTab: (AutomationTab) -> Unit = {},
    val onSelect: (Long) -> Unit = {},
    val onBack: () -> Unit = {},
    /** Open a routine fire's or agent job's steps (a hub that keeps run traces). */
    val onOpenRun: (FleetRun) -> Unit = {},
    val onCloseRun: () -> Unit = {},
    /** Read on from the open run's last step; called while it runs. */
    val onRefreshRun: () -> Unit = {},
    val onToggle: (Routine) -> Unit = {},
    /**
     * Rewrite one of the open routine's notebooks (`routine` | `project`);
     * `""` clears it. The callback hears null once the hub took it, else why
     * not, and the editor stays open with the person's text until then.
     */
    val onSaveMemory: (String, String, (Friendly?) -> Unit) -> Unit = { _, _, done -> done(null) },
    val onSetPaused: (Boolean) -> Unit = {},
    val onOpenSession: (Long) -> Unit = {},
    /** Missions live beside the routines; null when the hub keeps none. */
    val onOpenMissions: (() -> Unit)? = null,
    val onDismissError: () -> Unit = {},
    /** End a rule (a fleet agent's instance) from the Agents tab; null where this device may not. */
    val onRevokeRule: ((AgentRule) -> Unit)? = null,
    /** Run a manual fleet agent's instance with a focus (Run…); null where this device may not. */
    val onRunRule: ((AgentRule, AgentRunForm) -> Unit)? = null,
    /** Read the agents' status again (the findings card's Check again). */
    val onRefreshAgents: () -> Unit = {},
)

/**
 * Automation on the New bar (redesign 8.9): Pause all at the top, then the
 * routines with their switch, or the runs they made. A routine opens to its
 * own last runs; a run with a session opens it. Writing a routine stays on
 * the desktop.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationSheet(
    state: AutomationUiState,
    nowSeconds: Long,
    handlers: AutomationHandlers,
    /** The agents' standing rules (the Inbox's view model reads them too). */
    agents: AgentActionsUiState = AgentActionsUiState(),
) {
    var confirmPause by remember { mutableStateOf(false) }
    val showLoader = rememberLoaderVisible(state.loading || state.busy != null)
    val detail = state.detail
    val runDetail = state.runDetail
    BottomSheet(
        title = runDetail?.run?.let(::fleetRunTitle) ?: detail?.routine?.name ?: "Automation",
        meta = if (detail == null && runDetail == null) automationLine(state.routines, state.paused, state.spentToday) else null,
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
            if (runDetail != null) {
                RunDetailView(runDetail, nowSeconds, handlers)
            } else if (detail != null) {
                TextButton(onClick = handlers.onBack) { Text("‹ Routines", color = o.accent) }
                RoutineRow(detail.routine, nowSeconds, state, handlers, canToggle = state.canToggle && detail.mayChange, open = false)
                state.explain?.let { WhyCard(it) }
                state.memory?.let { MemoryCard(it, nowSeconds, canEdit = state.canEditMemory && state.busy == null, onSave = handlers.onSaveMemory) }
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
                AutomationList(state, nowSeconds, handlers, agents, onPause = { confirmPause = true })
            }
        }
    }
    if (confirmPause) {
        PauseAllQuestion(onPause = { confirmPause = false; handlers.onSetPaused(true) }, onDismiss = { confirmPause = false })
    }
}

/**
 * Why the open routine does or does not fire (claude-fleet's `explain`): its
 * one line, then each check that stops a fire or carries a caveat. The full
 * trace is on the desktop's Debug tab.
 */
@Composable
private fun WhyCard(why: RoutineExplain) {
    val o = Fleet.colors
    Column(
        modifier = Modifier.fillMaxWidth().background(o.bgPane, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(why.summary, color = if (why.state == "block") o.danger else o.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        for (c in whyLines(why)) Text(c, color = o.fgMuted, fontSize = 13.sp)
    }
}

/**
 * The open routine's notebooks (claude-fleet migration 204): what its runs
 * read under their prompt and rewrite from their reply, kept by the fleet
 * outside the repo and the host. Each shows its text and who wrote it last;
 * Edit and Clear where this device may change the routine. Which notebooks
 * a routine keeps is chosen on the desktop.
 */
@Composable
private fun MemoryCard(memory: RoutineMemory, nowSeconds: Long, canEdit: Boolean, onSave: (String, String, (Friendly?) -> Unit) -> Unit) {
    val o = Fleet.colors
    var editing by remember { mutableStateOf<RoutineNotebook?>(null) }
    var clearing by remember { mutableStateOf<RoutineNotebook?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().background(o.bgPane, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Memory · ${memoryLine(memory)}", color = o.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        for (n in memory.notebooks()) {
            Text(notebookTitle(n.scope), color = o.fgMuted, fontSize = 13.sp)
            if (n.body.isNotBlank()) {
                Text(n.body, color = o.fg, fontSize = 13.sp, maxLines = 12, overflow = TextOverflow.Ellipsis)
            }
            Text(notebookLine(n, nowSeconds), color = o.fgMuted, fontSize = 12.sp)
            if (canEdit) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { editing = n }) { Text("Edit", color = o.accent) }
                    if (n.body.isNotEmpty()) DangerTextButton(onClick = { clearing = n }) { Text("Clear") }
                }
            }
        }
    }
    editing?.let { n ->
        var text by remember(n) { mutableStateOf(n.body) }
        // The editor closes only once the hub took the text: a failed save
        // keeps what the person typed, and says why, here in the dialog.
        var saving by remember(n) { mutableStateOf(false) }
        var failed by remember(n) { mutableStateOf<Friendly?>(null) }
        val count = notebookChars(text)
        val over = count > memory.maxChars
        AlertDialog(
            onDismissRequest = { if (!saving) editing = null },
            title = { Text(notebookTitle(n.scope)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        minLines = 4,
                        maxLines = 12,
                        isError = over,
                        supportingText = { Text("$count / ${memory.maxChars} characters · the next run reads it") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    failed?.let {
                        Text(
                            "${it.title}: ${it.body}",
                            color = o.danger,
                            fontSize = 13.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saving = true
                        failed = null
                        onSave(n.scope, text.trimEnd()) { why ->
                            saving = false
                            failed = why
                            if (why == null) editing = null
                        }
                    },
                    enabled = !saving && !over && text != n.body,
                ) { Text(if (saving) "Saving…" else "Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }, enabled = !saving) { Text("Cancel") } },
        )
    }
    clearing?.let { n ->
        AlertDialog(
            onDismissRequest = { clearing = null },
            title = { Text("Clear ${notebookTitle(n.scope).replaceFirstChar { it.lowercase() }}?") },
            text = { Text("The next run starts with no notes in it. Runs and the fleet may write it again.") },
            confirmButton = { DangerTextButton(onClick = { onSave(n.scope, "") {}; clearing = null }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearing = null }) { Text("Cancel") } },
        )
    }
}

/**
 * Pause all asks first, from the sheet and from More's Automation row alike:
 * what stops, what finishes, and that nothing is lost.
 */
@Composable
fun PauseAllQuestion(onPause: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pause all automation?") },
        text = {
            Text(
                "Routines stop starting runs, missions stop starting steps, and the background jobs that act on their own stand still. " +
                    "Sessions already working finish their current turn. Resume here or on the desktop; nothing is lost.",
            )
        },
        confirmButton = { DangerTextButton(onClick = onPause) { Text("Pause all") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
private fun AutomationList(
    state: AutomationUiState,
    nowSeconds: Long,
    handlers: AutomationHandlers,
    agents: AgentActionsUiState,
    onPause: () -> Unit,
) {
    val o = Fleet.colors
    PauseCard(state, onPause = onPause, onResume = { handlers.onSetPaused(false) })
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TabButton("Routines ${state.routines.size}", state.tab == AutomationTab.Routines) { handlers.onTab(AutomationTab.Routines) }
        TabButton("Runs", state.tab == AutomationTab.Runs) { handlers.onTab(AutomationTab.Runs) }
        TabButton("Agents", state.tab == AutomationTab.Agents) { handlers.onTab(AutomationTab.Agents) }
    }
    when (state.tab) {
        AutomationTab.Agents -> {
            // Read-only, as on the desktop: what fleet runs itself.
            val builtIn = state.agents(nowSeconds)
            builtIn.forEachIndexed { i, a ->
                PhoneRow(title = a.name, line = "${a.does} · ${a.state}", dot = false, divider = i < builtIn.lastIndex)
            }
            AgentRules(agents, handlers.onRevokeRule, handlers.onRunRule)
            agents.selfDebug?.let { SelfDebugCard(it, handlers.onRefreshAgents) }
        }
        AutomationTab.Routines -> {
            if (state.loops.isNotEmpty()) {
                // The fleet's own loops (8.1), read-only: Pause all stops the pausable ones.
                Text("Built in", color = o.fgMuted, fontSize = 13.sp)
                state.loops.forEachIndexed { i, loop ->
                    PhoneRow(
                        title = loop.label.ifBlank { loop.name },
                        line = loopLine(loop, nowSeconds, state.paused == true),
                        word = loopWord(loop, state.paused == true),
                        dot = loopWord(loop, state.paused == true) != null,
                        divider = i < state.loops.lastIndex,
                    )
                }
                HorizontalDivider(color = o.border)
                Text("Yours", color = o.fgMuted, fontSize = 13.sp)
            }
            if (state.routines.isEmpty() && !state.loading) {
                Text("No routines yet. Make one on the desktop: Automation, New routine.", color = o.fgMuted, fontSize = 14.sp)
            }
            for (r in state.routines) RoutineRow(r, nowSeconds, state, handlers, canToggle = state.canToggle, open = true)
        }
        AutomationTab.Runs -> state.fleetRuns?.let { all ->
            // Every run on the fleet's behalf (8.3): tasks, missions, Jev,
            // `claude -p` and routine fires. A run opens its first session.
            if (all.isEmpty() && !state.loading) Text("No runs yet.", color = o.fgMuted, fontSize = 14.sp)
            all.forEachIndexed { i, run ->
                PhoneRow(
                    title = fleetRunTitle(run),
                    line = fleetRunLine(run, nowSeconds),
                    word = fleetRunWord(run),
                    divider = i < all.lastIndex,
                    // A routine fire or an agent job opens to its steps where the
                    // hub keeps them; any other run opens its first session.
                    onClick = if (state.canTrace && runHasTrace(run)) {
                        { handlers.onOpenRun(run) }
                    } else {
                        run.sessionIds.firstOrNull()?.let { id -> { handlers.onOpenSession(id) } }
                    },
                )
            }
        } ?: run {
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

/**
 * The agents' standing rules (claude-fleet's configurable agents, step 5):
 * the PR reviewer's and the support agent's, and each fleet agent's
 * instance by its definition. A new instance and Edit are the desktop's
 * form (or a chat card); the phone revokes one, and starts a manual fleet
 * agent with a focus (Run…).
 */
@Composable
private fun AgentRules(
    agents: AgentActionsUiState,
    onRevoke: ((AgentRule) -> Unit)?,
    onRun: ((AgentRule, AgentRunForm) -> Unit)?,
) {
    val o = Fleet.colors
    var asking by remember { mutableStateOf<AgentRule?>(null) }
    asking?.let { r ->
        if (onRun != null) RunAgentDialog(r, onRun = { form -> asking = null; onRun(r, form) }, onDismiss = { asking = null })
    }
    if (agents.rules.isEmpty()) return
    Text("Instances", color = o.fgMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
    agents.rules.forEachIndexed { i, r ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            PhoneRow(
                title = ruleAgentName(r),
                line = ruleLine(r),
                dot = false,
                divider = i < agents.rules.lastIndex,
                modifier = Modifier.weight(1f),
            )
            if (onRun != null && agents.canRun && ruleRunnable(r)) {
                TextButton(
                    onClick = { asking = r },
                    enabled = r.id !in agents.running,
                    modifier = Modifier.semantics { contentDescription = "Run ${r.scope}" },
                ) { Text("Run…") }
            }
            if (onRevoke != null && agents.canAnswer) {
                TextButton(
                    onClick = { onRevoke(r) },
                    modifier = Modifier.semantics { contentDescription = "Revoke ${r.scope}" },
                ) { Text("Revoke") }
            }
        }
    }
    agents.ruleNotice?.let {
        Text(it, color = o.fgMuted, fontSize = 13.sp, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

/**
 * Run… for a manual fleet agent: what to look at (the request), how far
 * back, and optionally which component, host or session. Empty fields are
 * left out of the call ([agentRunArgs]).
 */
@Composable
private fun RunAgentDialog(r: AgentRule, onRun: (AgentRunForm) -> Unit, onDismiss: () -> Unit) {
    var form by remember(r.id) { mutableStateOf(AgentRunForm()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Run ${ruleAgentName(r)}") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.request,
                    onValueChange = { form = form.copy(request = it.take(RUN_REQUEST_MAX)) },
                    label = { Text("What to look at") },
                    placeholder = { Text("Optional") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Window", color = Fleet.colors.fgMuted, fontSize = 13.sp)
                ChipFlow {
                    for ((label, minutes) in RUN_WINDOWS) {
                        ChoiceChip(label, form.minutes == minutes, onClick = {
                            form = form.copy(minutes = if (form.minutes == minutes) null else minutes)
                        })
                    }
                }
                Text("Component", color = Fleet.colors.fgMuted, fontSize = 13.sp)
                ChipFlow {
                    for (c in RUN_COMPONENTS) {
                        ChoiceChip(c, form.component == c, onClick = {
                            form = form.copy(component = if (form.component == c) null else c)
                        })
                    }
                }
                OutlinedTextField(
                    value = form.host,
                    onValueChange = { form = form.copy(host = it) },
                    label = { Text("Host") },
                    placeholder = { Text("Optional") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = form.session,
                    onValueChange = { form = form.copy(session = it) },
                    label = { Text("Session") },
                    placeholder = { Text("Optional") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onRun(form) }) { Text("Run") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The self debug agent's last run: its summary, then one row per finding,
 * worst first (critical and high red, medium amber). A row opens to its fix
 * and evidence. A run still going says so, with Check again.
 */
@Composable
private fun SelfDebugCard(view: SelfDebugView, onRefresh: () -> Unit) {
    val o = Fleet.colors
    val report = view.report
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).background(o.bgPane, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Self debug", color = o.fgMuted, fontSize = 13.sp)
        when {
            report != null -> {
                Text(
                    report.summary.ifBlank { if (report.findings.isEmpty()) "Nothing found." else "Findings" },
                    color = if (report.healthy == false) o.danger else o.fg,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                report.findings.forEachIndexed { i, f -> FindingRow(f, divider = i < report.findings.lastIndex) }
            }
            view.job.state == "done" -> Text("Done, with no findings the phone can read.", color = o.fgMuted, fontSize = 14.sp)
            view.job.state in setOf("failed", "skipped", "needs_person") -> Text(
                view.job.error?.takeIf { it.isNotBlank() } ?: "The run ended ${view.job.state.replace('_', ' ')}.",
                color = o.statusFailed,
                fontSize = 14.sp,
            )
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Working on it.", color = o.fg, fontSize = 14.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = onRefresh) { Text("Check again", color = o.accent) }
            }
        }
    }
}

@Composable
private fun FindingRow(f: SelfDebugFinding, divider: Boolean) {
    val o = Fleet.colors
    var open by remember(f) { mutableStateOf(false) }
    val tint = when (severityRank(f.severity)) {
        0, 1 -> o.danger
        2 -> o.statusWaiting
        else -> o.fgMuted
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(tint, CircleShape))
            Text(f.title, color = o.fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (f.severity.isNotEmpty()) Text(f.severity, color = tint, fontSize = 12.sp)
        }
        val line = listOf(f.component, f.cause).filter { it.isNotBlank() }.joinToString(" · ")
        if (line.isNotEmpty()) {
            Text(line, color = o.fgMuted, fontSize = 13.sp, maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
        }
        if (open) {
            if (f.fix.isNotBlank()) Text("Fix: ${f.fix}", color = o.fg, fontSize = 13.sp)
            if (f.confidence.isNotBlank()) Text("Confidence: ${f.confidence}", color = o.fgMuted, fontSize = 12.sp)
            for (e in f.evidence) {
                Text(
                    listOf(e.source, e.at).filter { it.isNotBlank() }.joinToString(" · ") + if (e.quote.isNotBlank()) "\n${e.quote}" else "",
                    color = o.fgMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
    if (divider) HorizontalDivider(color = o.border)
}

/** How often an open running run reads on, in milliseconds. */
private const val RUN_REFRESH_MS = 5_000L

/**
 * One run's steps (claude-fleet's run timeline, on the phone): its phases
 * from trigger to outcome as a column of dots, then every step it
 * recorded, newest last. A running run reads on every few seconds; its
 * session opens from here.
 */
@Composable
private fun RunDetailView(d: RunDetail, nowSeconds: Long, handlers: AutomationHandlers) {
    val o = Fleet.colors
    val running = d.run.outcome == "running"
    LaunchedEffect(d.run.id, running) {
        while (running) {
            delay(RUN_REFRESH_MS)
            handlers.onRefreshRun()
        }
    }
    TextButton(onClick = handlers.onCloseRun) { Text("‹ Runs", color = o.accent) }
    PhoneRow(
        title = fleetRunTitle(d.run),
        line = fleetRunLine(d.run, nowSeconds),
        word = fleetRunWord(d.run),
        divider = false,
        onClick = d.run.sessionIds.firstOrNull()?.let { id -> { handlers.onOpenSession(id) } },
    )
    val skipped = d.run.source == "routine" && d.run.outcome == "nothing_to_do" && d.run.sessionIds.isEmpty()
    val rail = phaseRail(d.steps, d.run.outcome, skipped)
    Column(
        modifier = Modifier.fillMaxWidth().background(o.bgPane, RoundedCornerShape(8.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Phases", color = o.fgMuted, fontSize = 13.sp)
        for (cell in rail) {
            val tint = when (cell.state) {
                PhaseState.Done -> o.statusDone
                PhaseState.Live -> o.statusWorking
                PhaseState.Failed -> o.statusFailed
                PhaseState.Skipped, PhaseState.Todo -> o.border
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(tint, CircleShape))
                Text(
                    cell.label,
                    color = if (cell.state == PhaseState.Todo) o.fgMuted else o.fg,
                    fontSize = 14.sp,
                    fontWeight = if (cell.state == PhaseState.Live) FontWeight.SemiBold else FontWeight.Normal,
                )
                if (cell.meta.isNotEmpty()) {
                    Text(cell.meta, color = o.fgMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    HorizontalDivider(color = o.border)
    Text(if (running) "What it did · live" else "What it did", color = o.fgMuted, fontSize = 13.sp)
    if (d.loaded && d.steps.isEmpty()) {
        Text("No steps recorded. A run from before traces shows nothing here.", color = o.fgMuted, fontSize = 14.sp)
    }
    d.steps.forEachIndexed { i, step ->
        PhoneRow(
            title = step.summary,
            line = listOfNotNull(
                step.phase,
                step.actor,
                tookWords(step.durationMs).takeIf { it.isNotEmpty() },
                step.costMicros?.let { dollars(it) },
                "try ${step.attempt}".takeIf { step.attempt > 1 },
            ).joinToString(" · "),
            dot = false,
            divider = i < d.steps.lastIndex,
        )
    }
}
