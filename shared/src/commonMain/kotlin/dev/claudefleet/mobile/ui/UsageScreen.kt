package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.AutoSwitchView
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.unnamedProject
import dev.claudefleet.mobile.model.relativeTime

data class UsageHandlers(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onSelect: (UsageWindow) -> Unit = {},
    val onOpenSession: (Long) -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** Name an account (uuid, name; blank clears). Offered where [UsageUiState.canRename]. */
    val onRename: (String, String) -> Unit = { _, _ -> },
    /**
     * Forbid an account for exactly these projects and every project of
     * exactly these orgs (null keeps a list), in one call. Offered where
     * [UsageUiState.canSetProjects].
     */
    val onSetBlocks: (String, List<Long>?, List<Long>?) -> Unit = { _, _, _ -> },
    /** Turn automatic switching on or off for my sessions. */
    val onAutoSwitch: (Boolean) -> Unit = {},
    /** …and whether a switched session is told to carry on. */
    val onAutoSwitchContinue: (Boolean) -> Unit = {},
)

/** The longest nickname the hub keeps (`Store::set_account_nickname`). */
internal const val NICKNAME_MAX = 32

/**
 * "Not for all of SalesTwins, acme/api": the orgs and projects [account] is
 * forbidden for, by name, or null when none is.
 */
internal fun forbiddenProjectsLine(
    account: AccountRow,
    projects: List<ProjectRow>,
    orgs: List<OrgDetail> = emptyList(),
): String? {
    if (account.forbiddenProjectIds.isEmpty() && account.forbiddenOrgIds.isEmpty()) return null
    val orgNames = account.forbiddenOrgIds
        .map { id -> "all of " + (orgs.firstOrNull { it.id == id }?.name ?: "org #$id") }
        .sorted()
    val names = account.forbiddenProjectIds
        .map { id -> projects.firstOrNull { it.id == id }?.label ?: unnamedProject(id) }
        .sorted()
    return "not for " + (orgNames + names).joinToString(", ")
}

/**
 * Name [account]: the label the desktop and every device show for it. Blank
 * clears it, so the email shows again. Where the hub takes it
 * ([canSetProjects]), also the projects the account is forbidden for
 * (claude-fleet docs/accounts.md, "Keep an account off a project"); Save
 * sends only what changed.
 */
@Composable
internal fun AccountNameDialog(
    account: AccountRow,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    canRename: Boolean = true,
    canSetProjects: Boolean = false,
    projects: List<ProjectRow> = emptyList(),
    orgs: List<OrgDetail> = emptyList(),
    /** The project and org lists to save, a null one unchanged; called once, with both. */
    onSaveBlocks: (List<Long>?, List<Long>?) -> Unit = { _, _ -> },
) {
    var name by remember(account.uuid) { mutableStateOf(account.nickname.orEmpty()) }
    var forbidden by remember(account.uuid) { mutableStateOf(account.forbiddenProjectIds.toSet()) }
    var forbiddenOrgs by remember(account.uuid) { mutableStateOf(account.forbiddenOrgIds.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (canRename) "Name this account" else account.label) },
        text = {
            Column {
                if (canRename) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(NICKNAME_MAX) },
                        singleLine = true,
                        label = { Text("Name") },
                        placeholder = { Text(account.email ?: account.uuid.take(8)) },
                    )
                    Text(
                        "Shown on every device. Empty shows the email again.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (canSetProjects) {
                    Text(
                        "Not for these projects",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        "Work in a ticked project never starts, resumes or switches onto this account.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Every project the fleet knows, and any it no longer lists that is still ticked.
                    val shown = (projects.map { it.id to it.label } +
                        (forbidden - projects.map { it.id }.toSet()).map { it to unnamedProject(it) })
                        .sortedBy { it.second }
                    if (shown.isEmpty()) {
                        Text(
                            "No projects yet.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Column(Modifier.fillMaxWidth().heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        // Whole orgs first: every project of a ticked one, later ones too.
                        val shownOrgs = (orgs.map { it.id to it.name } +
                            (forbiddenOrgs - orgs.map { it.id }.toSet()).map { it to "org #$it" })
                            .sortedBy { it.second.lowercase() }
                        for ((id, orgName) in shownOrgs) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .toggleable(value = id in forbiddenOrgs, role = Role.Checkbox) { on ->
                                        forbiddenOrgs = if (on) forbiddenOrgs + id else forbiddenOrgs - id
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = id in forbiddenOrgs, onCheckedChange = null)
                                Text("All of $orgName", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        for ((id, label) in shown) {
                            Row(
                                modifier = Modifier.fillMaxWidth()
                                    .toggleable(value = id in forbidden, role = Role.Checkbox) { on ->
                                        forbidden = if (on) forbidden + id else forbidden - id
                                    }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = id in forbidden, onCheckedChange = null)
                                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (canRename && name.trim() != account.nickname.orEmpty().trim()) onSave(name)
                if (canSetProjects) {
                    val projectsChanged = forbidden != account.forbiddenProjectIds.toSet()
                    val orgsChanged = forbiddenOrgs != account.forbiddenOrgIds.toSet()
                    if (projectsChanged || orgsChanged) {
                        onSaveBlocks(forbidden.sorted().takeIf { projectsChanged }, forbiddenOrgs.sorted().takeIf { orgsChanged })
                    }
                }
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** [AccountNameDialog] for [account] with what [state] lets this pairing change. */
@Composable
internal fun AccountDialogFor(account: AccountRow, state: UsageUiState, handlers: UsageHandlers, onDismiss: () -> Unit) {
    AccountNameDialog(
        account,
        onSave = { handlers.onRename(account.uuid, it) },
        onDismiss = onDismiss,
        canRename = state.canRename,
        canSetProjects = state.canSetProjects,
        projects = state.projects,
        orgs = state.orgs,
        onSaveBlocks = { projects, orgs -> handlers.onSetBlocks(account.uuid, projects, orgs) },
    )
}

/**
 * The person's own answer to automatic account switching, as on the
 * desktop's Accounts page: switch my sessions, and tell them to carry on.
 * The fleet's own switch is the owner's, in Settings; the line says so when
 * it is off.
 */
@Composable
internal fun AutoSwitchRows(view: AutoSwitchView, state: UsageUiState, handlers: UsageHandlers) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        ToggleRow(
            title = "Switch my sessions automatically",
            sub = when {
                !view.fleetEnabled -> "Off for this fleet: the owner turns it on in Settings › Sessions › Limits."
                !state.canChangeAutoSwitch -> (if (view.enabled) "On. " else "Off. ") + "Only a full pairing can change this."
                view.enabled -> "A paused session resumes under the login on its host with the most room."
                else -> "Paused sessions wait for you to switch them."
            },
            checked = view.enabled,
            enabled = state.canChangeAutoSwitch,
            onChange = handlers.onAutoSwitch,
        )
        if (state.autoSwitchContinueAvailable) {
            ToggleRow(
                title = "Then tell it to continue",
                sub = "Only a session whose turn the limit cut off; a finished one is just resumed.",
                checked = view.continueAfter,
                enabled = state.canChangeAutoSwitch && view.enabled,
                onChange = handlers.onAutoSwitchContinue,
            )
        }
    }
}

/**
 * A labelled switch: the whole row toggles and reads as one switch with its
 * title (TalkBack said only "Switch, on"; review M11), as Settings' rows do.
 */
@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** An accounts list the hub serves but that has nothing in it yet (review M15). */
internal const val NO_ACCOUNTS_YET = "No Claude account seen on the fleet's hosts yet."

/** Where a second account is added, while the phone cannot run the sign-in itself (review F13). */
internal const val ADD_ACCOUNT_ON_DESKTOP = "Add an account on the desktop: Accounts › + Add account."
