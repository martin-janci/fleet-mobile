package dev.claudefleet.mobile.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.components.ScreenHeader

/**
 * Settings: which hub, under what name, with what rights, on what version — and
 * one button that drops the credential.
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
            Field("App version", state.appVersion)

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

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
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(onClick = onForget, enabled = state.canForget) {
                    Text(if (state.forgetting) "Forgetting…" else "Forget")
                }
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
