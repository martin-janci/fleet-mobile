package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.SshHost
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.FoundHostRow
import dev.claudefleet.mobile.ui.kit.FullscreenLoader
import dev.claudefleet.mobile.ui.kit.FullscreenWait
import dev.claudefleet.mobile.ui.theme.Fleet

data class AddHostHandlers(
    val onClose: () -> Unit = {},
    val onAdd: (SshHost) -> Unit = {},
    val onDismissError: () -> Unit = {},
    /** Read the hub's SSH config again after a failed scan. */
    val onRescan: () -> Unit = {},
    /** Install agent on a host just added, by its fleet alias: opens the install's review, which installs nothing yet. */
    val onInstallAgent: (String) -> Unit = {},
    /** Add a host by the name or address typed by hand. */
    val onAddAddress: (String) -> Unit = {},
)

/**
 * Add a host (redesign 14.12, the Radar): the sweep runs while the hub's SSH
 * config hosts appear under it, each with Add. Adding probes the host first,
 * so a row stays on "Adding…" until the hub has heard from it.
 */
@Composable
fun AddHostScreen(state: AddHostUiState, handlers: AddHostHandlers) {
    FullscreenLoader(
        wait = FullscreenWait.FindHosts,
        title = if (state.scanning) "Looking for hosts" else "Hosts the hub can reach",
        meta = addHostMeta(state),
        onExit = handlers.onClose,
        exitLabel = if (state.added.isEmpty()) FullscreenWait.FindHosts.exitLabel else "Done",
        blips = state.blips,
        scanning = state.scanning,
        found = {
            ErrorBanner(state.error, onDismiss = handlers.onDismissError, onRetry = handlers.onRescan.takeIf { state.scanFailed })
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                for (h in state.candidates) {
                    val target = state.installTarget(h.alias)
                    val action = when {
                        target != null -> "Install agent"
                        h.alias in state.added -> "Added"
                        state.adding == h.alias -> "Adding…"
                        else -> "Add"
                    }
                    FoundHostRow(h.alias, addedHostLine(h, state.addedRows[h.alias].takeIf { h.alias in state.added }), action) {
                        when {
                            target != null -> handlers.onInstallAgent(target.alias)
                            action == "Add" && state.adding == null -> handlers.onAdd(h)
                        }
                    }
                }
            }
            ByHand(enabled = state.adding == null, onAdd = handlers.onAddAddress)
            Text(
                "From the hub's ~/.ssh/config. A host the hub cannot reach is added from the desktop as an agent host.",
                color = Fleet.colors.fgMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            )
        },
    )
}

/**
 * "Enter an address by hand": a quiet button that opens one field. What is
 * typed goes to the hub as the host's SSH alias, so the hub probes it the
 * way it probes a host from its SSH config, and keeps it only if it answers.
 */
@Composable
private fun ByHand(enabled: Boolean, onAdd: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    if (!open) {
        TextButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Enter an address by hand", color = Fleet.colors.accent)
        }
        return
    }
    val blocker = addressBlocker(address).takeIf { address.isNotBlank() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                label = { Text("Host name or address") },
                placeholder = { Text("nas.local") },
                isError = blocker != null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = {
                    onAdd(address.trim())
                    address = ""
                },
                enabled = enabled && address.isNotBlank() && blocker == null,
            ) { Text("Add") }
        }
        Text(
            blocker ?: "The hub connects over SSH as its own user, checks the host answers, then adds it.",
            color = if (blocker != null) Fleet.colors.statusFailed else Fleet.colors.fgMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * A found host's line: where it is, and once added what the probe found
 * that matters next — "tmux is missing", which Install agent puts there.
 */
internal fun addedHostLine(h: SshHost, added: HostRow?): String = when {
    added == null -> sshHostLine(h)
    !added.reachable -> "Added · does not answer yet"
    added.tmuxVersion == null -> "Added · tmux is missing"
    else -> listOfNotNull("Added", "tmux ${added.tmuxVersion}", pingWords(added.latencyMs)).joinToString(" · ")
}

/** "3 in the hub's SSH config", "None new in the hub's SSH config", or the scan under way. */
internal fun addHostMeta(state: AddHostUiState): String = when {
    state.scanning -> "Reading the hub's SSH config"
    state.scanFailed -> "Couldn't read the hub's SSH config"
    state.candidates.isEmpty() -> "None new in the hub's SSH config"
    state.candidates.size == 1 -> "1 in the hub's SSH config"
    else -> "${state.candidates.size} in the hub's SSH config"
}
