package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.ui.components.DangerTextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.components.ConnectionBanner
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import dev.claudefleet.mobile.ui.theme.FleetIcons
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import dev.claudefleet.mobile.ui.kit.Comet
import dev.claudefleet.mobile.ui.kit.InlineLoading
import dev.claudefleet.mobile.ui.kit.rememberLoaderVisible

/**
 * The New session form. Stateless, like every screen here: it draws a
 * [NewSessionUiState] and reports taps, and [NewSessionViewModel] is what is
 * tested.
 *
 * One list rather than a column of sections, because the project list is the
 * part that grows — a fleet with forty repositories must scroll, and the
 * fields under it must scroll with it. Create sits in a footer outside the
 * list so it is never forty rows away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewSessionScreen(
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
    modifier: Modifier = Modifier,
    multiStart: MultiStartHandlers = MultiStartHandlers(),
    /** Start a background agent on the chosen host, with a name and a prompt. */
    onStartBackground: (String, String) -> Unit = { _, _ -> },
    /** Adding a project, and the chosen project's worktrees on the chosen host. */
    tools: ProjectToolsUiState = ProjectToolsUiState(),
    toolHandlers: ProjectToolsHandlers = ProjectToolsHandlers(),
    onSelectWorktree: (Long?) -> Unit = {},
    /** The New navigation's three-step wizard ([NewSessionWizard]) instead of the long form. */
    wizard: Boolean = false,
    wizardStep: WizardStep = WizardStep.Where,
    onWizardStep: (WizardStep) -> Unit = {},
    /** The wizard's Add a project (redesign 14.20); Classic keeps [AddProjectSheet]. */
    addStep: AddProjectStep = AddProjectStep.Source,
    addHandlers: AddProjectHandlers = AddProjectHandlers(),
) {
    if (wizard) {
        NewSessionWizard(
            state = state,
            onBack = onBack,
            onSelectHost = onSelectHost,
            onProjectQuery = onProjectQuery,
            onSelectProject = onSelectProject,
            onNewWorktree = onNewWorktree,
            onBranchChange = onBranchChange,
            onBaseBranchChange = onBaseBranchChange,
            onFriendlyNameChange = onFriendlyNameChange,
            onCreate = onCreate,
            onDismissError = onDismissError,
            multiStart = multiStart,
            onStartBackground = onStartBackground,
            tools = tools,
            toolHandlers = toolHandlers,
            onSelectWorktree = onSelectWorktree,
            step = wizardStep,
            onStep = onWizardStep,
            modifier = modifier,
            addStep = addStep,
            addHandlers = addHandlers,
        )
        return
    }
    if (tools.addingOn != null) AddProjectSheet(tools, toolHandlers)
    tools.pendingCreate?.let { p ->
        AlertDialog(
            onDismissRequest = toolHandlers.onCancelCreate,
            title = { Text("Create ${p.owner}/${p.repo} on GitHub?") },
            text = { Text("The repository is created with the host's own gh login, then the project is added on the host.") },
            confirmButton = { TextButton(onClick = toolHandlers.onConfirmCreate) { Text("Create") } },
            dismissButton = { TextButton(onClick = toolHandlers.onCancelCreate) { Text("Cancel") } },
        )
    }
    val editable = !state.creating
    var askingBackground by remember { mutableStateOf(false) }
    if (askingBackground) {
        BackgroundAgentDialog(
            host = state.host.orEmpty(),
            onStart = { name, prompt -> askingBackground = false; onStartBackground(name, prompt) },
            onDismiss = { askingBackground = false },
        )
    }
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(
            title = state.ticketKey?.let { "Start $it" } ?: "New session",
            subtitle = state.host?.let { host -> state.projectLabel?.let { "$it on $host" } ?: host },
            navigation = {
                IconButton(onClick = onBack) { Icon(FleetIcons.ArrowBack, contentDescription = "Back") }
            },
        )
        ConnectionBanner(state.status)
        ErrorBanner(state.error, onDismiss = onDismissError)

        var projectsOpen by remember(state.projectId) { mutableStateOf(false) }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            item(key = "host-label") { SectionLabel("Host") }
            item(key = "hosts") {
                if (state.hosts.isEmpty()) {
                    Hint("No hosts yet. They appear once the hub has listed them.")
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (host in state.hosts) {
                            FilterChip(
                                selected = host.alias == state.host,
                                onClick = { onSelectHost(host.alias) },
                                enabled = editable && host.reachable,
                                label = { Text(if (host.reachable) host.alias else "${host.alias} · unreachable") },
                            )
                        }
                    }
                }
            }

            item(key = "project-label") {
                SectionLabel("Project")
                when {
                    state.projectLabel != null -> Hint("Selected: ${state.projectLabel}")
                    // Ticket mode: the hub picks the project that last worked on the key's prefix.
                    state.ticketKey != null -> Hint("Optional — left empty, the hub picks the project that last worked on it.")
                }
                val host = state.host
                if (tools.canAdd && host != null && state.ticketKey == null) {
                    TextButton(onClick = { toolHandlers.onOpenAdd(host) }, enabled = editable, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("Add a project on $host…")
                    }
                }
            }
            item(key = "project-query") {
                OutlinedTextField(
                    value = state.projectQuery,
                    onValueChange = onProjectQuery,
                    label = { Text("Search projects") },
                    singleLine = true,
                    enabled = editable,
                    keyboardOptions = IDENTIFIER_KEYBOARD,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }
            if (state.projects.isEmpty()) {
                item(key = "no-projects") {
                    Hint(if (state.projectQuery.isBlank()) "The hub knows no projects yet." else "No project matches.")
                }
            }
            // Once one is picked the list folds to it, so the fields still to
            // fill are not forty rows down; a search or Change opens it again.
            val folded = state.projectId != null && state.projectQuery.isBlank() && !projectsOpen &&
                state.projects.any { it.id == state.projectId }
            val shownProjects = if (folded) state.projects.filter { it.id == state.projectId } else state.projects
            if (folded) item(key = "project-change") {
                TextButton(onClick = { projectsOpen = true }, enabled = editable, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("Change project (${state.projects.size})")
                }
            }
            items(shownProjects, key = { "project-${it.id}" }) { project ->
                val selected = project.id == state.projectId
                ListItem(
                    headlineContent = { Text(project.label) },
                    leadingContent = { RadioButton(selected = selected, onClick = null, enabled = editable) },
                    modifier = Modifier.clickable(enabled = editable) { onSelectProject(project.id) },
                )
            }

            // Multi-start (M13.4d): once a first project is picked, the others
            // it may also start in. Ticks only — the confirm sheet sends.
            if (state.canMultiStart && state.projectId != null) {
                item(key = "also-in-label") {
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                    SectionLabel("Also start in")
                    Hint(
                        "One session in each, on the same branch — up to $ALSO_IN_MAX more. " +
                            (state.orgLabel?.let { "${state.ticketKey} is $it's; a project in another organisation is refused." }
                                ?: "A project in another organisation than the ticket's is refused."),
                    )
                }
                items(state.alsoIn, key = { "also-${it.id}" }) { project ->
                    val ticked = project.id in state.alsoInIds
                    val allowed = ticked || state.alsoInIds.size < ALSO_IN_MAX
                    ListItem(
                        headlineContent = { Text(project.label) },
                        leadingContent = { Checkbox(checked = ticked, onCheckedChange = null, enabled = editable && allowed) },
                        modifier = Modifier.clickable(enabled = editable && allowed) { multiStart.onToggle(project.id) },
                    )
                }
            }

            // Starting work names the worktree after the ticket on the hub, and
            // the label too: nothing to ask here.
            if (state.ticketKey == null) item(key = "worktree") {
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .toggleable(value = state.newWorktree, enabled = editable, role = Role.Switch, onValueChange = onNewWorktree)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("New worktree", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (state.newWorktree) "On a fresh branch, beside the project's own checkout."
                            else "In the project's own checkout.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = state.newWorktree, onCheckedChange = null, enabled = editable)
                }
            }
            // The project's worktrees on this host: start in one of them, or
            // delete one no session lives in.
            val worktrees = tools.worktrees
            if (state.ticketKey == null && !state.newWorktree && worktrees != null && worktrees.worktrees.isNotEmpty()) {
                item(key = "worktrees-label") { Hint("Or start in one of its worktrees on ${worktrees.hostAlias}:") }
                items(worktrees.worktrees, key = { "wt-${it.id}" }) { wt ->
                    val picked = wt.id == state.worktreeId
                    ListItem(
                        headlineContent = { Text(wt.name.ifBlank { wt.path }) },
                        supportingContent = wt.branch?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
                        leadingContent = { RadioButton(selected = picked, onClick = null, enabled = editable) },
                        trailingContent = if (tools.canDeleteWorktree && wt.name != "main") {
                            {
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
                        } else {
                            null
                        },
                        modifier = Modifier.clickable(enabled = editable) { onSelectWorktree(if (picked) null else wt.id) },
                    )
                }
            }
            if (state.newWorktree && state.ticketKey == null) {
                item(key = "branch") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = state.branch,
                            onValueChange = onBranchChange,
                            label = { Text("Branch") },
                            placeholder = { Text("feat/something") },
                            isError = state.branchInvalid,
                            supportingText = if (state.branchInvalid) ({ Text("A branch name has no spaces.") }) else null,
                            singleLine = true,
                            enabled = editable,
                            keyboardOptions = IDENTIFIER_KEYBOARD,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = state.baseBranch,
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
            if (state.ticketKey == null) item(key = "name") {
                OutlinedTextField(
                    value = state.friendlyName,
                    onValueChange = onFriendlyNameChange,
                    label = { Text("Name (optional)") },
                    supportingText = { Text("Left empty, the hub names it after the branch.") },
                    singleLine = true,
                    enabled = editable,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
          Column {
            state.missing?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                )
            }
            Button(
                onClick = onCreate,
                enabled = state.canCreate,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (state.creating) {
                    if (rememberLoaderVisible(true)) {
                        Comet(size = 16.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (state.ticketKey != null) "Starting…" else "Creating…")
                } else {
                    Text(
                        when {
                            state.alsoInIds.isNotEmpty() -> "Start in ${state.alsoInIds.size + 1} projects…"
                            state.ticketKey != null -> "Start here"
                            else -> "Create session"
                        },
                    )
                }
            }
            // The desktop's other kind: headless, supervised, given its task
            // up front — needs a host, not a project.
            if (state.backgroundAvailable && state.host != null) {
                TextButton(
                    onClick = { askingBackground = true },
                    enabled = editable,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) { Text("Background agent on ${state.host}…") }
            }
          }
        }
    }

    state.confirm?.let { MultiStartConfirmSheet(it, onConfirm = multiStart.onConfirm, onCancel = multiStart.onCancel) }
    state.result?.let { MultiStartResultSheet(it, onOpen = multiStart.onOpen, onDone = multiStart.onDone) }
}

/** The multi-start taps — every one a no-op until wired. */
data class MultiStartHandlers(
    val onToggle: (Long) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    val onDone: () -> Unit = {},
)

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/**
 * Branch and repository names are identifiers: a dictionary that capitalises
 * `feat/x` or "corrects" a repo name is a silent edit to what reaches the hub.
 * The Pair screen and the prompt box turn the same two things off.
 */
internal val IDENTIFIER_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    imeAction = ImeAction.Next,
)

/** A background agent: a name (optional — the prompt names it otherwise) and the task it starts on. */
@Composable
internal fun BackgroundAgentDialog(host: String, onStart: (String, String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Background agent on $host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A headless Claude session the fleet supervises, started on the task below. It shows in the list once the hub has matched it.")
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name (optional)") })
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    minLines = 3,
                    label = { Text("Task") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onStart(name, prompt) }, enabled = prompt.isNotBlank()) { Text("Start") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What the project tools report. */
data class ProjectToolsHandlers(
    val onOpenAdd: (String) -> Unit = {},
    val onCloseAdd: () -> Unit = {},
    val onClone: (String) -> Unit = {},
    val onCreate: (String, String, Boolean) -> Unit = { _, _, _ -> },
    val onConfirmCreate: () -> Unit = {},
    val onCancelCreate: () -> Unit = {},
    val onDeleteWorktree: (Long) -> Unit = {},
    val onDismissError: () -> Unit = {},
)

/**
 * Add a project on a host, the desktop's three ways but the folder one (a
 * path on the hub's own machine): clone a URL, clone one of the
 * repositories `gh` there can see, or make a new one — on GitHub too, which
 * is confirmed first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddProjectSheet(tools: ProjectToolsUiState, handlers: ProjectToolsHandlers) {
    var url by remember { mutableStateOf("") }
    var owner by remember { mutableStateOf("") }
    var repo by remember { mutableStateOf("") }
    var onGithub by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = handlers.onCloseAdd) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            item {
                Text("Add a project on ${tools.addingOn}", style = MaterialTheme.typography.titleLarge)
                InlineLoading(waiting = tools.adding, modifier = Modifier.padding(vertical = 4.dp))
                tools.error?.let {
                    Text(it.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                SectionLabel("Clone")
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("GitHub URL") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
                TextButton(onClick = { handlers.onClone(url) }, enabled = url.isNotBlank() && !tools.adding) { Text("Clone") }
            }
            val repos = tools.repos
            if (tools.canListGithub) {
                item { Hint(if (repos == null) "Asking gh on the host for its repositories…" else "Or one gh on the host can see:") }
                items(repos.orEmpty(), key = { "gh-${it.nameWithOwner}" }) { r ->
                    ListItem(
                        headlineContent = { Text(r.nameWithOwner) },
                        supportingContent = r.description?.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                        trailingContent = { if (r.isPrivate) Text("private", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.clickable(enabled = !tools.adding) { handlers.onClone("https://github.com/${r.nameWithOwner}") },
                    )
                }
            }
            item {
                SectionLabel("New repository")
                OutlinedTextField(value = owner, onValueChange = { owner = it }, singleLine = true, label = { Text("Owner") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = repo, onValueChange = { repo = it }, singleLine = true, label = { Text("Repository") }, modifier = Modifier.fillMaxWidth())
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.toggleable(value = onGithub, role = Role.Checkbox) { onGithub = it },
                ) {
                    Checkbox(checked = onGithub, onCheckedChange = null)
                    Text("Create it on GitHub too")
                }
                TextButton(
                    onClick = { handlers.onCreate(owner, repo, onGithub) },
                    enabled = owner.isNotBlank() && repo.isNotBlank() && !tools.adding,
                ) { Text("Create") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
