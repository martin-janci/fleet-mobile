package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import dev.claudefleet.mobile.ui.components.DangerTextButton
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.kit.HubBanner
import dev.claudefleet.mobile.ui.kit.pastLoaderDelay
import dev.claudefleet.mobile.ui.kit.PulseSequence
import dev.claudefleet.mobile.ui.kit.SheetOption
import dev.claudefleet.mobile.ui.kit.StepBars
import dev.claudefleet.mobile.ui.kit.rememberPhoneConnection
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.FleetIcons
import dev.claudefleet.mobile.ui.theme.OrbitTokens

/**
 * The New session form as the Orbit Fleet wizard (redesign 14.6, board
 * MobileNewSession): three steps, Where, Project and Review, then the Pulse
 * sequence while the hub starts it. Drawn under the New navigation; Classic
 * keeps the one long form in [NewSessionScreen].
 *
 * Nothing is lost, only moved. The same [NewSessionUiState] and the same
 * taps drive both, so every rule the view model holds (unreachable hosts
 * cannot be picked, the branch is validated, a multi-start is confirmed
 * first) holds here unchanged. The step is the only thing the wizard adds,
 * and it is screen state: going back a step keeps everything filled in.
 */
@Composable
internal fun NewSessionWizard(
    state: NewSessionUiState,
    onBack: () -> Unit,
    onSelectHost: (String) -> Unit,
    onProjectQuery: (String) -> Unit,
    onSelectProject: (Long) -> Unit,
    onNewWorktree: (Boolean) -> Unit,
    onBranchChange: (String) -> Unit,
    onBaseBranchChange: (String) -> Unit,
    onFriendlyNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onDismissError: () -> Unit,
    multiStart: MultiStartHandlers,
    onStartBackground: (String, String) -> Unit,
    tools: ProjectToolsUiState,
    toolHandlers: ProjectToolsHandlers,
    onSelectWorktree: (Long?) -> Unit,
    /**
     * The step on screen, held by the route: `App` owns every back handler
     * (`BackGestureTest`), and a back gesture on Project or Review goes one
     * step back rather than leaving the form.
     */
    step: WizardStep,
    onStep: (WizardStep) -> Unit,
    modifier: Modifier = Modifier,
    /** Add a project's step (redesign 14.20), held by the route for the same reason as [step]. */
    addStep: AddProjectStep = AddProjectStep.Source,
    addHandlers: AddProjectHandlers = AddProjectHandlers(),
) {
    // A background agent shows the same Starting panel, without the
    // project's steps: it has none of them.
    var background by remember { mutableStateOf(false) }
    LaunchedEffect(state.creating) { if (!state.creating) background = false }

    tools.pendingCreate?.let { p ->
        AlertDialog(
            onDismissRequest = toolHandlers.onCancelCreate,
            title = { Text("Create ${p.owner}/${p.repo} on GitHub?") },
            text = { Text("The repository is created with the host's own gh login, then the project is added on the host.") },
            confirmButton = { TextButton(onClick = toolHandlers.onConfirmCreate) { Text("Create") } },
            dismissButton = { TextButton(onClick = toolHandlers.onCancelCreate) { Text("Cancel") } },
        )
    }
    // Add a project is a wizard of its own, over this one: Source, Where,
    // then the clone. Leaving it while a clone runs comes back here.
    if (tools.addingOn != null) {
        AddProjectWizard(
            tools = tools,
            step = addStep,
            hosts = state.hosts,
            projects = state.projects.map { it.label },
            handlers = addHandlers,
            modifier = modifier,
        )
        return
    }
    var askingBackground by remember { mutableStateOf(false) }
    if (askingBackground) {
        BackgroundAgentDialog(
            host = state.host.orEmpty(),
            onStart = { name, prompt ->
                askingBackground = false
                background = true
                onStartBackground(name, prompt)
            },
            onDismiss = { askingBackground = false },
        )
    }

    val previous = step.previous
    Column(modifier = modifier.fillMaxSize().background(Fleet.colors.bg)) {
        ScreenHeader(
            title = state.ticketKey?.let { "Start $it" } ?: "New session",
            subtitle = stepHeading(step, state),
            navigation = {
                IconButton(onClick = { if (previous != null && !state.creating) onStep(previous) else onBack() }) {
                    Icon(FleetIcons.ArrowBack, contentDescription = "Back")
                }
            },
        )
        StepBars(step = step.number, total = WizardStep.entries.size)
        HubBanner(rememberPhoneConnection(state.status))
        ErrorBanner(state.error, onDismiss = onDismissError)

        if (pastLoaderDelay(state.creating)) {
            StartingPanel(state, background = background, onLeave = onBack, modifier = Modifier.weight(1f))
        } else {
            WizardBody(
                step = step,
                state = state,
                onStep = onStep,
                onBack = onBack,
                onSelectHost = onSelectHost,
                onProjectQuery = onProjectQuery,
                onSelectProject = onSelectProject,
                onNewWorktree = onNewWorktree,
                onBranchChange = onBranchChange,
                onBaseBranchChange = onBaseBranchChange,
                onFriendlyNameChange = onFriendlyNameChange,
                onCreate = onCreate,
                onToggleAlsoIn = multiStart.onToggle,
                onBackground = { askingBackground = true },
                tools = tools,
                toolHandlers = toolHandlers,
                onSelectWorktree = onSelectWorktree,
            )
        }
    }

    state.confirm?.let { MultiStartConfirmSheet(it, onConfirm = multiStart.onConfirm, onCancel = multiStart.onCancel) }
    state.result?.let { MultiStartResultSheet(it, onOpen = multiStart.onOpen, onDone = multiStart.onDone) }
}

/** The step's content over the footer: Cancel or Back, then Next or Create. */
@Composable
private fun ColumnScope.WizardBody(
    step: WizardStep,
    state: NewSessionUiState,
    onStep: (WizardStep) -> Unit,
    onBack: () -> Unit,
    onSelectHost: (String) -> Unit,
    onProjectQuery: (String) -> Unit,
    onSelectProject: (Long) -> Unit,
    onNewWorktree: (Boolean) -> Unit,
    onBranchChange: (String) -> Unit,
    onBaseBranchChange: (String) -> Unit,
    onFriendlyNameChange: (String) -> Unit,
    onCreate: () -> Unit,
    onToggleAlsoIn: (Long) -> Unit,
    onBackground: () -> Unit,
    tools: ProjectToolsUiState,
    toolHandlers: ProjectToolsHandlers,
    onSelectWorktree: (Long?) -> Unit,
) {
    val previous = step.previous
    val editable = !state.creating
    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
        when (step) {
            WizardStep.Where -> WhereStep(state, editable, onSelectHost, onBackground = onBackground)
            WizardStep.Project -> ProjectStep(
                state, editable, onProjectQuery, onSelectProject, onNewWorktree, onBranchChange, onBaseBranchChange,
                tools, toolHandlers, onSelectWorktree,
            )
            WizardStep.Review -> ReviewStep(
                state, editable, onFriendlyNameChange, onToggleAlsoIn,
                onChange = onStep,
                onBackground = onBackground,
                worktreeName = tools.worktrees?.worktrees?.firstOrNull { it.id == state.worktreeId }?.let { it.name.ifBlank { it.path } },
            )
        }
    }
    WizardFooter(
        caption = if (step == WizardStep.Review) state.missing else nextBlocker(step, state),
        backLabel = if (previous == null) "Cancel" else "Back",
        onBackTap = { if (previous == null) onBack() else onStep(previous) },
        primaryLabel = if (step == WizardStep.Review) createLabel(state) else "Next",
        primaryEnabled = if (step == WizardStep.Review) state.canCreate else editable && nextBlocker(step, state) == null,
        onPrimary = { step.next?.let(onStep) ?: onCreate() },
        editable = editable,
    )
}

/** The wizard's three steps, in order. */
enum class WizardStep(val title: String) {
    Where("Where"),
    Project("Project"),
    Review("Review");

    val number: Int get() = ordinal + 1
    val next: WizardStep? get() = entries.getOrNull(ordinal + 1)
    val previous: WizardStep? get() = entries.getOrNull(ordinal - 1)
}

/** "Step 2 of 3 · Project on mercury". */
internal fun stepHeading(step: WizardStep, s: NewSessionUiState): String {
    val title = if (step == WizardStep.Project && s.host != null) "Project on ${s.host}" else step.title
    return "Step ${step.number} of ${WizardStep.entries.size} · $title"
}

/**
 * Why Next is off on [step], in words, or null when it may go on. Review
 * has no Next: its Create follows [NewSessionUiState.canCreate], and says
 * [NewSessionUiState.missing].
 */
internal fun nextBlocker(step: WizardStep, s: NewSessionUiState): String? = when (step) {
    WizardStep.Where -> when {
        s.host != null -> null
        s.hosts.none { it.reachable } -> "No host is reachable right now."
        else -> "Pick a host."
    }
    // With a host picked, what the form still misses is the project's or
    // the branch's: the view model names the host first.
    WizardStep.Project -> if (s.host == null) "Pick a host." else s.missing
    WizardStep.Review -> null
}

/** A host's sub-line: its load by status word, or why it cannot be picked. */
internal fun hostLoad(h: HostChoice): String {
    if (!h.reachable) return "Signal lost · cannot start there now"
    val parts = buildList {
        if (h.needsYou > 0) add("${h.needsYou} needs you")
        if (h.working > 0) add("${h.working} working")
        if (h.idle > 0) add("${h.idle} idle")
    }
    return parts.joinToString(" · ").ifEmpty { "No sessions" }
}

/** The Create button's words, as the long form says them. */
internal fun createLabel(s: NewSessionUiState): String = when {
    s.creating -> if (s.ticketKey != null) "Starting…" else "Creating…"
    s.alsoInIds.isNotEmpty() -> "Start in ${s.alsoInIds.size + 1} projects…"
    s.ticketKey != null -> "Start here"
    else -> "Create session"
}

/**
 * The real steps the hub runs for this start, in order: a worktree when one
 * is made (a new branch, or a ticket's), then the tmux pane, then the agent.
 */
internal fun startSteps(s: NewSessionUiState): List<String> = buildList {
    if (s.ticketKey != null || s.newWorktree) add("Worktree")
    add("tmux pane")
    add("Claude Code")
}

/** "Starting orbit-redesign", "Starting FLEET-151 in 2 projects". */
internal fun startingTitle(s: NewSessionUiState, background: Boolean): String = when {
    background -> "Starting a background agent"
    s.ticketKey != null && s.alsoInIds.isNotEmpty() -> "Starting ${s.ticketKey} in ${s.alsoInIds.size + 1} projects"
    s.ticketKey != null -> "Starting ${s.ticketKey}"
    else -> "Starting " + (s.friendlyName.trim().ifEmpty { null } ?: s.branch.trim().takeIf { s.newWorktree && it.isNotEmpty() } ?: "a session")
}

@Composable
private fun WhereStep(s: NewSessionUiState, editable: Boolean, onSelectHost: (String) -> Unit, onBackground: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = gutterPadding()) {
        item(key = "host-label") { StepLabel("Host") }
        if (s.hosts.isEmpty()) {
            item(key = "no-hosts") { StepHint("No hosts yet. They appear once the hub has listed them.") }
        }
        items(s.hosts, key = { "host-${it.alias}" }) { h ->
            SheetOption(
                title = h.alias,
                selected = h.alias == s.host,
                onSelect = { onSelectHost(h.alias) },
                sub = hostLoad(h),
                enabled = editable && h.reachable,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        // The desktop's other kind: headless, supervised, given its task up
        // front. It needs a host and nothing else, so it is offered here.
        val host = s.host
        if (s.backgroundAvailable && host != null) item(key = "background") {
            TextButton(onClick = onBackground, enabled = editable) { Text("Or a background agent on $host…") }
        }
    }
}

@Composable
private fun ProjectStep(
    s: NewSessionUiState,
    editable: Boolean,
    onProjectQuery: (String) -> Unit,
    onSelectProject: (Long) -> Unit,
    onNewWorktree: (Boolean) -> Unit,
    onBranchChange: (String) -> Unit,
    onBaseBranchChange: (String) -> Unit,
    tools: ProjectToolsUiState,
    toolHandlers: ProjectToolsHandlers,
    onSelectWorktree: (Long?) -> Unit,
) {
    var projectsOpen by remember(s.projectId) { mutableStateOf(false) }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = gutterPadding()) {
        item(key = "project-query") {
            OutlinedTextField(
                value = s.projectQuery,
                onValueChange = onProjectQuery,
                label = { Text("Search projects") },
                leadingIcon = { Icon(FleetIcons.Search, contentDescription = null) },
                singleLine = true,
                enabled = editable,
                keyboardOptions = IDENTIFIER_KEYBOARD,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            // Ticket mode: the hub picks the project that last worked on the key's prefix.
            if (s.ticketKey != null) StepHint("Optional: left empty, the hub picks the project that last worked on ${s.ticketKey}.")
        }
        if (s.projects.isEmpty()) item(key = "no-projects") {
            StepHint(if (s.projectQuery.isBlank()) "The hub knows no projects yet." else "No project matches.")
        }
        // Once one is picked the list folds to it, so the worktree and branch
        // are not forty rows down; a search or Change opens it again.
        val folded = s.projectId != null && s.projectQuery.isBlank() && !projectsOpen && s.projects.any { it.id == s.projectId }
        val shown = if (folded) s.projects.filter { it.id == s.projectId } else s.projects
        item(key = "projects-gap") { Box(Modifier.height(8.dp)) }
        items(shown, key = { "project-${it.id}" }) { p ->
            SheetOption(
                title = p.label,
                selected = p.id == s.projectId,
                onSelect = { onSelectProject(p.id) },
                enabled = editable,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (folded) item(key = "project-change") {
            TextButton(onClick = { projectsOpen = true }, enabled = editable) { Text("Change project (${s.projects.size})") }
        }
        val host = s.host
        if (tools.canAdd && host != null && s.ticketKey == null) item(key = "add-project") {
            TextButton(onClick = { toolHandlers.onOpenAdd(host) }, enabled = editable && !tools.adding) { Text("Add a project on $host…") }
            if (tools.adding) {
                StepHint("Adding ${tools.addingWhat ?: "a project"} on ${tools.addingWhere ?: host}. It is picked here once the hub is done.")
            }
        }

        // Starting work names the worktree after the ticket on the hub, and
        // the label too: nothing to ask here.
        if (s.ticketKey == null) item(key = "worktree") {
            HorizontalDivider(color = Fleet.colors.border, modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier = Modifier.fillMaxWidth()
                    .defaultMinSize(minHeight = OrbitTokens.spacing("touch-min").dp)
                    .toggleable(value = s.newWorktree, enabled = editable, role = Role.Switch, onValueChange = onNewWorktree),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("New worktree", color = Fleet.colors.fg, style = Fleet.type.textLg)
                    Text(
                        if (s.newWorktree) "On a fresh branch, beside the project's own checkout." else "Off: the session runs in the project's own checkout.",
                        color = Fleet.colors.fgMuted,
                        style = Fleet.type.textSm,
                    )
                }
                Switch(checked = s.newWorktree, onCheckedChange = null, enabled = editable)
            }
        }
        // The project's worktrees on this host: start in one of them, or
        // delete one no session lives in.
        val worktrees = tools.worktrees
        if (s.ticketKey == null && !s.newWorktree && worktrees != null && worktrees.worktrees.isNotEmpty()) {
            item(key = "worktrees-label") { StepHint("Or start in one of its worktrees on ${worktrees.hostAlias}:") }
            items(worktrees.worktrees, key = { "wt-${it.id}" }) { wt ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
                    SheetOption(
                        title = wt.name.ifBlank { wt.path },
                        sub = wt.branch,
                        selected = wt.id == s.worktreeId,
                        onSelect = { onSelectWorktree(if (wt.id == s.worktreeId) null else wt.id) },
                        enabled = editable,
                        modifier = Modifier.weight(1f),
                    )
                    if (tools.canDeleteWorktree && wt.name != "main") {
                        // Asked first: a deleted worktree takes its branch's
                        // checkout with it, and there is no undo.
                        var asking by remember { mutableStateOf(false) }
                        DangerTextButton(onClick = { asking = true }, enabled = tools.deleting == null) {
                            Text(if (tools.deleting == wt.id) "Deleting…" else "Delete")
                        }
                        if (asking) {
                            AlertDialog(
                                onDismissRequest = { asking = false },
                                title = { Text("Delete the worktree ${wt.name}?") },
                                text = { Text("Its directory goes from the host. Refused while a session is in it or it holds changes not committed.") },
                                confirmButton = {
                                    DangerTextButton(onClick = { asking = false; toolHandlers.onDeleteWorktree(wt.id) }) { Text("Delete") }
                                },
                                dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
                            )
                        }
                    }
                }
            }
        }
        if (s.newWorktree && s.ticketKey == null) item(key = "branch") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                OutlinedTextField(
                    value = s.branch,
                    onValueChange = onBranchChange,
                    label = { Text("Branch") },
                    placeholder = { Text("feat/something") },
                    isError = s.branchInvalid,
                    supportingText = if (s.branchInvalid) ({ Text("A branch name has no spaces.") }) else null,
                    singleLine = true,
                    enabled = editable,
                    keyboardOptions = IDENTIFIER_KEYBOARD,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.baseBranch,
                    onValueChange = onBaseBranchChange,
                    label = { Text("Base branch (optional)") },
                    placeholder = { Text("the default branch") },
                    singleLine = true,
                    enabled = editable,
                    keyboardOptions = IDENTIFIER_KEYBOARD,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun ReviewStep(
    s: NewSessionUiState,
    editable: Boolean,
    onFriendlyNameChange: (String) -> Unit,
    onToggleAlsoIn: (Long) -> Unit,
    onChange: (WizardStep) -> Unit,
    onBackground: () -> Unit,
    worktreeName: String?,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = gutterPadding()) {
        item(key = "summary") {
            Column(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .background(Fleet.colors.bgPane, RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp))
                    .border(1.dp, Fleet.colors.border, RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)),
            ) {
                ReviewRow("Host", s.host ?: "Not picked", onChange = { onChange(WizardStep.Where) }, enabled = editable)
                ReviewRow(
                    "Project",
                    s.projectLabel ?: if (s.ticketKey != null) "The hub picks it" else "Not picked",
                    onChange = { onChange(WizardStep.Project) },
                    enabled = editable,
                )
                val worktree = when {
                    s.ticketKey != null -> "Named after ${s.ticketKey} by the hub"
                    s.newWorktree -> "New: ${s.branch.trim().ifEmpty { "branch not named" }} from ${s.baseBranch.trim().ifEmpty { "the default branch" }}"
                    s.worktreeId != null -> worktreeName ?: "An existing worktree"
                    else -> "The project's own checkout"
                }
                ReviewRow("Worktree", worktree, onChange = { onChange(WizardStep.Project) }, enabled = editable, divider = s.ticketKey != null)
                if (s.ticketKey != null) {
                    ReviewRow("Ticket", s.ticketKey + (s.orgLabel?.let { " · $it" } ?: ""), divider = false)
                }
            }
        }
        if (s.ticketKey == null) item(key = "name") {
            OutlinedTextField(
                value = s.friendlyName,
                onValueChange = onFriendlyNameChange,
                label = { Text("Name (optional)") },
                supportingText = { Text("Left empty, the hub names it after the branch.") },
                singleLine = true,
                enabled = editable,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
        }
        // Multi-start (M13.4d): once a first project is picked, the others it
        // may also start in. Ticks only; the confirm sheet sends.
        if (s.canMultiStart && s.projectId != null) {
            item(key = "also-in-label") {
                StepLabel("Also start in")
                StepHint(
                    "One session in each, on the same branch, up to $ALSO_IN_MAX more. " +
                        (s.orgLabel?.let { "${s.ticketKey} is $it's; a project in another organisation is refused." }
                            ?: "A project in another organisation than the ticket's is refused."),
                )
            }
            items(s.alsoIn, key = { "also-${it.id}" }) { p ->
                val ticked = p.id in s.alsoInIds
                val allowed = ticked || s.alsoInIds.size < ALSO_IN_MAX
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .defaultMinSize(minHeight = OrbitTokens.spacing("touch-min").dp)
                        .toggleable(value = ticked, enabled = editable && allowed, role = Role.Checkbox) { onToggleAlsoIn(p.id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = ticked, onCheckedChange = null, enabled = editable && allowed)
                    Text(p.label, color = Fleet.colors.fg, style = Fleet.type.textMd, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        val host = s.host
        if (s.backgroundAvailable && host != null) item(key = "background") {
            TextButton(onClick = onBackground, enabled = editable, modifier = Modifier.padding(top = 8.dp)) {
                Text("Background agent on $host instead…")
            }
        }
    }
}

@Composable
private fun ReviewRow(label: String, value: String, onChange: (() -> Unit)? = null, enabled: Boolean = true, divider: Boolean = true) {
    val o = Fleet.colors
    Row(
        modifier = Modifier.fillMaxWidth()
            .defaultMinSize(minHeight = OrbitTokens.spacing("touch-min").dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = o.fgMuted, style = Fleet.type.textSm, modifier = Modifier.padding(end = 12.dp).width(76.dp))
        Text(value, color = o.fg, style = Fleet.type.textMd, modifier = Modifier.weight(1f))
        if (onChange != null) TextButton(onClick = onChange, enabled = enabled) { Text("Change") }
    }
    if (divider) HorizontalDivider(color = o.border)
}

/**
 * While the hub starts it: the Pulse sequence over the real steps, and the
 * word that leaving is safe. No step is ticked off on a timer; the session
 * opens the moment the hub has made it.
 */
@Composable
private fun StartingPanel(s: NewSessionUiState, background: Boolean, onLeave: () -> Unit, modifier: Modifier = Modifier) {
    val o = Fleet.colors
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PulseSequence()
        Text(startingTitle(s, background), color = o.fg, style = Fleet.type.textXl)
        val host = s.host
        if (host != null) {
            Text(
                if (background) "on $host" else "${s.projectLabel ?: "the hub's pick"} on $host",
                color = o.fgMuted,
                style = Fleet.type.textMd,
            )
        }
        if (!background) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                for (line in startSteps(s)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).border(1.5.dp, o.loaderAccent, CircleShape))
                        Text(line, color = o.fg2, style = Fleet.type.textMd, modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
        Text(
            "The hub runs these in one go and the session opens as soon as it exists. You can leave: it keeps starting and shows in Sessions.",
            color = o.fgMuted,
            style = Fleet.type.textSm,
        )
        OutlinedButton(
            onClick = onLeave,
            border = BorderStroke(1.dp, o.controlBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = o.fg2),
            shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
            modifier = Modifier.height(OrbitTokens.spacing("touch-min").dp),
        ) { Text("Leave") }
    }
}

@Composable
internal fun WizardFooter(
    caption: String?,
    backLabel: String,
    onBackTap: () -> Unit,
    primaryLabel: String,
    primaryEnabled: Boolean,
    onPrimary: () -> Unit,
    editable: Boolean,
) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    val touch = OrbitTokens.spacing("touch-min").dp
    Column(modifier = Modifier.fillMaxWidth().background(o.bgPane)) {
        HorizontalDivider(color = o.border)
        if (caption != null) {
            Text(caption, color = o.fgMuted, style = Fleet.type.textSm, modifier = Modifier.padding(start = gutter, end = gutter, top = 8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onBackTap,
                enabled = editable,
                modifier = Modifier.weight(1f).height(touch),
                border = BorderStroke(1.dp, o.controlBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = o.fg2),
                shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
            ) { Text(backLabel, fontSize = 15.sp) }
            Button(
                onClick = onPrimary,
                enabled = primaryEnabled,
                modifier = Modifier.weight(1f).height(touch),
                colors = ButtonDefaults.buttonColors(containerColor = o.accent, contentColor = o.accentFg),
                shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
            ) { Text(primaryLabel, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
internal fun StepLabel(text: String) {
    Text(
        text,
        color = Fleet.colors.fgMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
internal fun StepHint(text: String) {
    Text(text, color = Fleet.colors.fgMuted, style = Fleet.type.textSm, modifier = Modifier.padding(vertical = 6.dp))
}

@Composable
internal fun gutterPadding() =
    androidx.compose.foundation.layout.PaddingValues(horizontal = OrbitTokens.spacing("phone-gutter").dp, vertical = 4.dp)
