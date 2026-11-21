package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.BackgroundOptions
import dev.claudefleet.mobile.model.BG_AGENT_CLAUDE
import dev.claudefleet.mobile.model.BG_AGENT_CODEX
import dev.claudefleet.mobile.model.CODEX_NEEDS_PROJECT
import dev.claudefleet.mobile.model.backgroundOptionsFor
import dev.claudefleet.mobile.model.backgroundAgentWord
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.ExperimentalMaterial3Api
import dev.claudefleet.mobile.ui.kit.BottomSheet
import dev.claudefleet.mobile.ui.kit.SheetAction

/** The multi-start taps — every one a no-op until wired. */
data class MultiStartHandlers(
    val onToggle: (Long) -> Unit = {},
    val onConfirm: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onOpen: (Long) -> Unit = {},
    val onDone: () -> Unit = {},
)

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

/**
 * A background agent (MobileFormsSession): a task, a name (optional — the
 * task names it otherwise), and where the hub takes them (contract 14)
 * Read-only and a stop after time or spend. A sheet with Cancel and Start at
 * the thumb; it starts and shows in Inbox, no spinner. Opened from New
 * session on a host and from inside a session (⋮ Background agent…).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun BackgroundAgentSheet(
    host: String,
    onStart: (String, String, BackgroundOptions) -> Unit,
    onDismiss: () -> Unit,
    /** The hub takes project, read-only and stop-after (contract 14): the limits are offered. */
    options: Boolean = false,
    /** The project picked on the form, which the agent starts in. */
    project: String? = null,
    /** The agents the hub runs in the background ([backgroundAgents]); a choice only when there are two. */
    agents: List<String> = listOf(BG_AGENT_CLAUDE),
) {
    var name by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf(BG_AGENT_CLAUDE) }
    var readOnly by remember { mutableStateOf(false) }
    var stopAfter by remember { mutableStateOf<Long?>(null) }
    var spend by remember { mutableStateOf<Double?>(null) }
    // Claude is the hub's default and is never sent; Codex only where it was
    // offered, and then without the limits it does not take.
    val picked = agent.takeIf { it in agents } ?: BG_AGENT_CLAUDE
    val codex = picked == BG_AGENT_CODEX
    val sent = backgroundOptionsFor(picked, readOnly, stopAfter, spend, hasProject = project != null)
    BottomSheet(
        title = "Background agent",
        meta = BACKGROUND_AGENT_META,
        onDismiss = onDismiss,
        primary = SheetAction("Start", enabled = prompt.isNotBlank() && sent != null) {
            sent?.let { onStart(name, prompt, it) }
        },
        scrollable = true,
    ) {
        Text(
            listOfNotNull(backgroundAgentWord(agent), host.takeIf { it.isNotBlank() }?.let { "on $it" }, project?.takeIf { options }?.let { "in $it" })
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            minLines = 3,
            label = { Text("Task") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Name (optional)") },
            modifier = Modifier.fillMaxWidth(),
        )
        // MobileMissions: the Agent choice, offered only when the hub runs more than Claude Code in the background.
        if (agents.size > 1) {
            Text("Agent", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in agents) ChoiceChip(backgroundAgentWord(a), agent == a, { agent = a })
            }
            if (codex && sent == null) {
                Text(CODEX_NEEDS_PROJECT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (options && !codex) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().toggleable(value = readOnly, role = Role.Switch) { readOnly = it },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Read-only")
                    Text(BACKGROUND_READ_ONLY_SUB, style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = readOnly, onCheckedChange = null)
            }
            Text("Stop after", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((secs, label) in BACKGROUND_STOP_AFTER) ChoiceChip(label, stopAfter == secs, { stopAfter = secs })
            }
            Text("Spend limit", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((usd, label) in BACKGROUND_SPEND) ChoiceChip(label, spend == usd, { spend = usd })
            }
        }
    }
}

/** The background agent sheet's words (MobileFormsSession). */
internal const val BACKGROUND_AGENT_META = "Runs without a pane; the result lands in Inbox."
internal const val BACKGROUND_READ_ONLY_SUB = "It may read and run tests, never edit or push"

/** A background agent's stop-after choices, within the hub's 60 s to 7 days. */
internal val BACKGROUND_STOP_AFTER: List<Pair<Long?, String>> =
    listOf(null to "No limit", 3_600L to "1 hour", 14_400L to "4 hours", 86_400L to "1 day")

/** Its spend-limit choices, within the hub's (0, 1000]. */
internal val BACKGROUND_SPEND: List<Pair<Double?, String>> =
    listOf(null to "No limit", 1.0 to "$1", 5.0 to "$5", 20.0 to "$20")

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
