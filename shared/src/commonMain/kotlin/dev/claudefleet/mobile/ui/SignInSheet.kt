package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.model.LoginTarget
import dev.claudefleet.mobile.model.setupTokenRenewal
import dev.claudefleet.mobile.ui.components.ErrorBanner
import kotlinx.coroutines.delay

/**
 * Where the screens that show a signed-out login reach Sign in again:
 * provided by the paired app only while the hub offers it to this pairing
 * ([SignInUiState.available]); null otherwise, and each place then keeps
 * its Copy login command alone. A setup-token login is never opened here
 * (its `claude setup-token` guidance stays where it is).
 */
val LocalSignIn = compositionLocalOf<((LoginTarget) -> Unit)?> { null }

/** Sign in again for [target], or null where the hub does not offer it or the login renews with a setup token. */
@Composable
fun signInFor(target: LoginTarget): (() -> Unit)? {
    val open = LocalSignIn.current ?: return null
    return if (target.token) null else ({ open(target) })
}

const val SIGN_IN_SHEET_TAG = "sign_in.sheet"
const val SIGN_IN_OPEN_LINK_TAG = "sign_in.open_link"
const val SIGN_IN_CODE_TAG = "sign_in.code"
const val SIGN_IN_SEND_CODE_TAG = "sign_in.send_code"
const val SIGN_IN_COMMAND_TAG = "sign_in.command"
const val SIGN_IN_DONE_TAG = "sign_in.done"

internal const val SIGN_IN_MAC_NOTE_TAG = "signIn.macNote"

/** The Mac note's words (the desktop's SignInAgain), pointing to the desktop for + Add account. */
fun macLoginNote(host: String): String =
    "$host is a Mac. Signing its own login in from fleet writes ~/.claude/.credentials.json next to the Keychain copy, " +
        "and the two copies sign each other out. A login profile has only one copy: add one for this account with " +
        "+ Add account on the desktop instead."

/** How often the link's expiry line is redrawn while the sheet is open. */
private const val EXPIRY_TICK_MS = 15_000L

/**
 * The phone's sign-in step (the desktop's `AddAccountLogin.svelte` with
 * `again`): the sign-in link to open in the browser, the pane's last lines
 * with its choices as buttons, a field for the code the page shows, and
 * Done once the host reports the login. The command to run on the host
 * instead is always there to copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInSheet(state: SignInUiState, vm: SignInViewModel) {
    val target = state.target ?: return
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    LaunchedEffect(target, state.linkSeenAt) {
        while (state.linkSeenAt != null) {
            delay(EXPIRY_TICK_MS)
            vm.tick()
        }
    }
    ModalBottomSheet(onDismissRequest = { vm.finish() }, modifier = Modifier.testTag(SIGN_IN_SHEET_TAG)) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(state.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            val done = state.doneLine
            when {
                target.token && target.profile != null -> {
                    Text("${target.profile} signs in with a setup token: ${setupTokenRenewal(target.host, target.profile)}.", style = MaterialTheme.typography.bodyMedium)
                }
                // Review w03-6: the desktop's Mac note. The phone has no
                // + Add account, so it points there.
                state.macNote -> {
                    Text(macLoginNote(target.host), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag(SIGN_IN_MAC_NOTE_TAG))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { vm.finish() }) { Text("Cancel") }
                        OutlinedButton(onClick = { vm.signInAnyway() }) { Text("Sign in here anyway") }
                    }
                }
                done != null -> {
                    Text("$done New sessions on ${target.host} can bill it.", style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    Text(
                        "Claude's own sign-in runs on ${target.host}. Open the link, sign in, and paste the code the page shows.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    ErrorBanner(state.error, onDismiss = vm::dismissError.takeIf { !state.forbidden })
                    if (state.starting) Text("Opening the login pane on ${target.host}…", style = MaterialTheme.typography.bodySmall)
                    state.status?.signInUrl?.let { url ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { runCatching { uri.openUri(url) } }, modifier = Modifier.testTag(SIGN_IN_OPEN_LINK_TAG)) { Text("Open sign-in page") }
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(url)) }) { Text("Copy link") }
                        }
                    }
                    state.deviceCode?.let { code ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Code $code", style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy code") }
                        }
                    }
                    state.expiry?.let { e ->
                        Row {
                            Text(
                                e.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (e.expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (e.expired) TextButton(onClick = { vm.startOver() }) { Text("Start over") }
                        }
                    }
                    if (state.started) {
                        Text(
                            state.status?.pane ?: "Waiting for the login pane…",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 12,
                        )
                        for (c in state.choices) {
                            OutlinedButton(onClick = { vm.press(c.key) }, modifier = Modifier.fillMaxWidth()) { Text("${c.key}. ${c.label}") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { vm.press("Up") }) { Text("Up") }
                            TextButton(onClick = { vm.press("Down") }) { Text("Down") }
                            TextButton(onClick = { vm.press("Enter") }) { Text("Enter") }
                        }
                        OutlinedTextField(
                            value = state.code,
                            onValueChange = vm::onCodeChange,
                            label = { Text("Code from the sign-in page") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                            modifier = Modifier.fillMaxWidth().testTag(SIGN_IN_CODE_TAG),
                        )
                        Button(
                            onClick = { vm.sendCode() },
                            enabled = !state.sending && state.code.isNotBlank(),
                            modifier = Modifier.heightIn(min = 48.dp).testTag(SIGN_IN_SEND_CODE_TAG),
                        ) { Text(if (state.sending) "Sending…" else "Send code") }
                    } else if (!state.starting && !state.forbidden && state.error != null) {
                        TextButton(onClick = { vm.startOver() }) { Text("Try again") }
                    }
                }
            }
            // The way round any of it: the command for a terminal on the host.
            if (done == null) {
                state.command?.let { command ->
                    Text(
                        if (target.token) "The token command:" else "Or run it on ${target.host} from a computer:",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(command, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(SIGN_IN_COMMAND_TAG))
                    TextButton(onClick = { clipboard.setText(AnnotatedString(command)) }) { Text("Copy command") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                if (done == null) {
                    OutlinedButton(onClick = { vm.finish() }) { Text(if (state.started || state.starting) "Cancel" else "Close") }
                } else {
                    Button(onClick = { vm.finish() }, modifier = Modifier.testTag(SIGN_IN_DONE_TAG)) { Text("Done") }
                }
            }
        }
    }
}
