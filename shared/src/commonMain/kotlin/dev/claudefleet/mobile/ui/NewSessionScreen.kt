package dev.claudefleet.mobile.ui

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
import androidx.compose.material3.CircularProgressIndicator
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
) {
    val editable = !state.creating
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
            items(state.projects, key = { "project-${it.id}" }) { project ->
                val selected = project.id == state.projectId
                ListItem(
                    headlineContent = { Text(project.label) },
                    leadingContent = { RadioButton(selected = selected, onClick = null, enabled = editable) },
                    modifier = Modifier.clickable(enabled = editable) { onSelectProject(project.id) },
                )
            }

            // Starting work names the worktree after the ticket on the hub, and
            // the label too: nothing to ask here.
            if (state.ticketKey == null) item(key = "worktree") {
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                    Switch(checked = state.newWorktree, onCheckedChange = onNewWorktree, enabled = editable)
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
            Button(
                onClick = onCreate,
                enabled = state.canCreate,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                if (state.creating) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.ticketKey != null) "Starting…" else "Creating…")
                } else {
                    Text(if (state.ticketKey != null) "Start here" else "Create session")
                }
            }
        }
    }
}

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
private val IDENTIFIER_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    imeAction = ImeAction.Next,
)
