package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.ui.theme.FleetIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import dev.claudefleet.mobile.ui.components.DangerTextButton
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Switch
import dev.claudefleet.mobile.notify.rememberNotificationPermission
import dev.claudefleet.mobile.notify.NoBackgroundNotifier
import dev.claudefleet.mobile.notify.BackgroundNotifier

/**
 * Settings: which hub, under what name, with what rights, on what version —
 * this app's and the hub's, as two fields — and one button that drops the
 * credential.
 *
 * **No revoke button, and not because one was left out.** Cancelling a token for
 * good is the operator's, from the hub; this app holds a client token, which the
 * hub refuses fleet administration. [SettingsViewModel] is handed an interface
 * with two methods and neither of them reaches the hub's client registry, so the
 * button could not be wired up even if someone drew it. The wording below says
 * the same thing to the person holding the phone, because "forgotten" and
 * "cancelled" differ by exactly the thing that matters when a phone is lost.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onForget: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The fleet's settings (claude-fleet declarative pages P6), drawn by
     * [FleetSettingsSection] when the hub serves them; nothing otherwise.
     */
    fleetSettings: @Composable () -> Unit = {},
    /** A fleet settings page is open: it takes the screen. */
    fleetPageOpen: Boolean = false,
    /** The Usage screen; null where the hub reports neither usage nor accounts. */
    onOpenUsage: (() -> Unit)? = null,
    /** The Company screen; null where the hub lists no organisation to this device. */
    onOpenCompany: (() -> Unit)? = null,
    /** Notifications while the app is away; [NoBackgroundNotifier] draws nothing. */
    notifier: BackgroundNotifier = NoBackgroundNotifier,
) {
    // The header and the error stay put; only the fields scroll. The header
    // used to live inside the scrolling column and left with the content.
    Column(modifier = modifier.fillMaxSize()) {
        ScreenHeader(title = "Settings")
        // `SettingsUiState.error` stays a plain `String?` — wrapped here only,
        // at the point `ErrorBanner` needs a [Friendly], rather than pulling
        // `SettingsViewModel` into this task's scope.
        val errorAsFriendly = state.error?.asGenericFriendly()
        ErrorBanner(errorAsFriendly, onDismiss = onDismissError)
        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(8.dp))
            if (fleetPageOpen) {
                fleetSettings()
                return@Column
            }
            Field("Hub", state.hub)
            Field("Client name", state.clientName)
            Field(
                label = "Access",
                value = if (state.readOnly) {
                    "read only — this device cannot send prompts"
                } else {
                    state.mode
                },
            )
            // Two programs, two versions, named apart. One "Version" field
            // here could only ever have been one of them, and whichever it
            // was it read as the other half the time.
            Field("App version", state.appVersion)
            Field("Hub version", state.hubVersion)

            if (notifier.supported) NotifyRow(notifier)

            onOpenUsage?.let { open ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Usage", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Estimated cost by host, day and session; the fleet's Claude accounts",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Says it opens somewhere: among settings fields it read as one more.
                    Icon(FleetIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            onOpenCompany?.let { open ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Company", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Organisations, their spend, members and devices — read only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(FleetIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            fleetSettings()

            Text(
                text = "Forget this hub",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = "Removes the credential from this phone. It does not cancel it — the " +
                    "token stays good on the hub until the operator cancels it there, which " +
                    "is what to do if this phone is lost.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // Asked first: getting back means a new pairing code from the
            // operator, and this was one tap from nothing.
            var asking by remember { mutableStateOf(false) }
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                DangerTextButton(onClick = { asking = true }, enabled = state.canForget) {
                    Text(if (state.forgetting) "Forgetting…" else "Forget…")
                }
            }
            if (asking) {
                AlertDialog(
                    onDismissRequest = { asking = false },
                    title = { Text("Forget ${state.hub}?") },
                    text = { Text("This phone stops seeing the fleet. To come back you need a new pairing code from the hub's operator.") },
                    confirmButton = { DangerTextButton(onClick = { asking = false; onForget() }) { Text("Forget") } },
                    dismissButton = { TextButton(onClick = { asking = false }) { Text("Cancel") } },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value.ifBlank { "—" },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Notify me when a session needs me — on Android, a foreground service with
 * its own ongoing notification holds the hub's stream open, so it is the
 * person's to turn on, and the system's leave is asked for right then.
 */
@Composable
private fun NotifyRow(notifier: BackgroundNotifier) {
    val on by notifier.enabled.collectAsState()
    var refused by remember { mutableStateOf(false) }
    val ask = rememberNotificationPermission()
    val note by notifier.note.collectAsState()
    LifecycleResumeEffect(notifier) {
        notifier.refreshNote()
        onPauseOrDispose { }
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
    val flip: (Boolean) -> Unit = { want ->
        if (!want) {
            notifier.setEnabled(false)
        } else {
            ask { granted ->
                refused = !granted
                if (granted) notifier.setEnabled(true)
            }
        }
    }
    // The whole row is the switch: a tap on its words flips it, and a screen
    // reader reads the words as the switch's own.
    Row(
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = on, role = Role.Switch, onValueChange = flip)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Notify me when a session needs me", style = MaterialTheme.typography.titleSmall)
            Text(
                if (refused) "Notifications are turned off for this app in the system's settings."
                else note ?: "Waiting, stuck or failed — even with the app closed. Keeps a connection to the hub open, with its own notification.",
                style = MaterialTheme.typography.bodySmall,
                color = if (refused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = on,
            onCheckedChange = null,
        )
    }
}
