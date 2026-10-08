package dev.claudefleet.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.claudefleet.mobile.notify.BackgroundNotifier
import dev.claudefleet.mobile.notify.NoBackgroundNotifier
import dev.claudefleet.mobile.notify.rememberNotificationPermission
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.kit.OrbitMark
import dev.claudefleet.mobile.ui.kit.OrbitMarkLarge
import dev.claudefleet.mobile.ui.scan.QrScannerView
import dev.claudefleet.mobile.ui.theme.Fleet
import dev.claudefleet.mobile.ui.theme.OrbitTokens
import kotlinx.coroutines.delay

/**
 * Pairing, QR first (redesign 14.11, board MobileSettings › Pairing): the
 * Orbit mark, one sentence on where the code comes from, and the camera as the
 * one primary action at the foot of the screen, in thumb reach. Pasting a
 * link and typing the address and code are quiet options under it, one tap
 * away and never removed: a phone with no camera, or a refused permission,
 * still pairs, and reading a code down the phone is an ordinary way to do it.
 *
 * The camera still starts **off**, behind the button. Composing the scanner is
 * what makes Android ask for the permission, and this is the screen a fresh
 * install opens on: a viewfinder that came up by itself would put a camera
 * dialog in front of someone who has not been told what this app is yet.
 *
 * A pair in flight says which hub it is contacting, shows a loader only after
 * `loader-delay` (400 ms), and can be cancelled.
 *
 * Stateless: it draws a [PairUiState] and reports taps. [PairViewModel] is what
 * is tested; `PairPreviewTest` renders it in both themes.
 */
@Composable
fun PairScreen(
    state: PairUiState,
    onAddressChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onScanningChange: (Boolean) -> Unit,
    onScanned: (String) -> Unit,
    onScannerUnavailable: (String) -> Unit,
    onDismissError: () -> Unit,
    onDismissReason: () -> Unit,
    modifier: Modifier = Modifier,
    onManualChange: (Boolean) -> Unit = {},
    onPaste: (String?) -> Unit = {},
    onCancel: () -> Unit = {},
    /** Draws the camera in place of the real one; previews pass one, where there is no camera. */
    scanner: (@Composable (Modifier) -> Unit)? = null,
) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    val touch = OrbitTokens.spacing("touch-min").dp
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier.fillMaxSize().background(o.bg)) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            if (!state.scanning) {
                OrbitMark(OrbitMarkLarge)
                Spacer(Modifier.height(20.dp))
            }
            Text(
                "Pair with your fleet",
                style = Fleet.type.textXl,
                color = o.fg,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "On the desktop, open Settings → Devices → Pair a device, then scan the code it shows. " +
                    "On the hub's own machine, fleet-hub pair shows the same code.",
                style = Fleet.type.textMd,
                color = o.fgMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))

            // Why this screen is up rather than the fleet: a 401 dropped the
            // credential, or the person forgot this hub in Settings. A note,
            // not a failure (analysis 05), so it is drawn in the info tone.
            val reasonAsFriendly = state.reason?.let {
                Friendly(
                    title = if (state.reasonIsForget) "You forgot this hub" else "This phone was signed out",
                    body = it.replaceFirstChar { c -> c.uppercaseChar() },
                    isError = false,
                )
            }
            val errorAsFriendly = state.error?.let {
                Friendly(title = "Couldn't pair", body = it.replaceFirstChar { c -> c.uppercaseChar() }, isError = true)
            }
            ErrorBanner(reasonAsFriendly, onDismiss = onDismissReason, modifier = Modifier.clip(phoneCard()))
            if (reasonAsFriendly != null && errorAsFriendly != null) Spacer(Modifier.height(8.dp))
            ErrorBanner(errorAsFriendly, onDismiss = onDismissError, modifier = Modifier.clip(phoneCard()))

            if (state.scanning) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier.fillMaxWidth()
                        // Square: the QR is square, and a viewfinder the shape of
                        // the thing being aimed at is easier to aim.
                        .aspectRatio(1f)
                        .clip(phoneCard())
                        .border(2.dp, o.accent, phoneCard()),
                    contentAlignment = Alignment.Center,
                ) {
                    if (scanner != null) {
                        scanner(Modifier.fillMaxSize())
                    } else {
                        QrScannerView(onScanned = onScanned, onUnavailable = onScannerUnavailable, modifier = Modifier.fillMaxSize())
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Camera on · point it at the code", style = Fleet.type.textSm, color = o.fgMuted)
            }

            if (state.pairing) {
                Spacer(Modifier.height(12.dp))
                PairingProgress(state.contacting, onCancel)
            }

            // The typed fields sit right under the banner that explains them,
            // above the quiet options, so a link's address and code (and the
            // error they drew) are on screen without scrolling.
            if (state.manual) {
                ManualFields(state, onAddressChange, onCodeChange, onSubmit)
            }

            Spacer(Modifier.height(8.dp))
            // The quiet options: text buttons, full width, at touch height.
            QuietOption("Paste a pairing link", enabled = !state.pairing) {
                onPaste(clipboard.getText()?.text)
            }
            if (state.cameraAvailable) {
                QuietOption(
                    if (state.manual) "Hide the typed code" else "Enter the code by hand",
                    enabled = !state.pairing,
                ) { onManualChange(!state.manual) }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = "A code lasts ten minutes and works once. The token it buys stays on this " +
                    "phone; cancelling it is done from the hub.",
                style = Fleet.type.textXs,
                color = o.fgMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
        }

        // The one primary, in thumb reach: Pair once a code is typed, else the
        // camera (the button the camera permission hangs off).
        val typedReady = state.manual && state.code.isNotBlank()
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 12.dp)) {
            when {
                typedReady || !state.cameraAvailable -> PrimaryButton(
                    label = if (state.pairing) "Pairing…" else "Pair",
                    enabled = state.canSubmit,
                    height = touch,
                    onClick = onSubmit,
                )
                else -> PrimaryButton(
                    label = if (state.scanning) "Stop the camera" else "Scan the code",
                    enabled = !state.pairing,
                    height = touch,
                    onClick = { onScanningChange(!state.scanning) },
                )
            }
        }
    }
}

private fun phoneCard() = RoundedCornerShape(OrbitTokens.radius("radius-phone-card").dp)

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, height: Dp, onClick: () -> Unit) {
    val o = Fleet.colors
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(height),
        colors = ButtonDefaults.buttonColors(containerColor = o.accent, contentColor = o.accentFg),
        shape = RoundedCornerShape(OrbitTokens.radius("radius-md").dp),
    ) { Text(label, fontSize = 15.sp) }
}

@Composable
private fun QuietOption(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = OrbitTokens.spacing("touch-min").dp),
        colors = ButtonDefaults.textButtonColors(contentColor = Fleet.colors.accent),
    ) { Text(label, fontSize = 15.sp) }
}

/**
 * "Contacting fleet.example.com…" with Cancel; the loader appears only once
 * the wait has passed `loader-delay`, so a quick hub never flashes one.
 */
@Composable
private fun PairingProgress(contacting: String?, onCancel: () -> Unit) {
    val o = Fleet.colors
    var late by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(OrbitTokens.durationMs.getValue("loader-delay"))
        late = true
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = contacting?.let { "Contacting $it…" } ?: "Pairing…",
                style = Fleet.type.textMd,
                color = o.fg2,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        if (late) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = o.loaderAccent,
                trackColor = o.track,
            )
        }
    }
}

@Composable
private fun ManualFields(
    state: PairUiState,
    onAddressChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    OutlinedTextField(
        value = state.address,
        onValueChange = onAddressChange,
        label = { Text("Hub address") },
        placeholder = { Text("https://fleet.example.com") },
        supportingText = { Text("Only needed for a typed code — a scanned QR names its own hub.") },
        singleLine = true,
        enabled = !state.pairing,
        // A URL, and the keyboard is told so. Left on its defaults this
        // field capitalises the first letter and runs autocorrect over a
        // hostname — so `fleet.rlt.sk` arrives as `Fleet.rlt.sk` or as
        // whatever the dictionary thought `rlt` should have been, and the
        // pairing fails on an address the person can see is right.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    OutlinedTextField(
        value = state.code,
        onValueChange = onCodeChange,
        label = { Text("Pairing code") },
        placeholder = { Text("ABCD1234") },
        supportingText = { Text("Eight characters, printed under the QR.") },
        singleLine = true,
        enabled = !state.pairing,
        // A refused code is the field the banner is about.
        isError = state.error != null && !state.pairing,
        // Eight Crockford base32 characters, which `PairTarget.normalizeCode`
        // uppercases anyway — so the keyboard may as well show the letters
        // in the shape they are printed in, and stop autocorrecting a code
        // that is by construction not a word.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            capitalization = KeyboardCapitalization.Characters,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        // Done pairs, rather than only closing the keyboard over the button.
        keyboardActions = KeyboardActions(onDone = { if (state.canSubmit) onSubmit() }),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

/** A pairing's mode as what it lets the person do — told now rather than found out from a disabled box. */
internal fun pairedModeWords(mode: String): String = when (mode) {
    "full" -> "full access"
    "readonly" -> "read-only: you can watch sessions, not send prompts, answer questions or start sessions"
    else -> mode
}

/**
 * The hub answered (redesign 14.11, board MobileSettings › Paired): the mark
 * in a halo, which hub, under what name and with what rights, then the one
 * permission the main use case depends on, triage from the lock screen.
 *
 * A confirmation rather than a silent jump to the fleet list, because "which
 * hub did I just hand a credential to" is worth reading once, especially for
 * someone who scanned a QR off a screen they do not own.
 *
 * The notification step is asked here, with its reason, and only where the
 * platform has background notifications ([BackgroundNotifier.supported]) and
 * they are not already on. "Not now" is a real choice: it opens the fleet and
 * asks nothing. A notification never carries Approve; the app is where a
 * person answers.
 */
@Composable
fun PairedScreen(
    paired: PairedHub,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
    notifier: BackgroundNotifier = NoBackgroundNotifier,
) {
    val o = Fleet.colors
    val gutter = OrbitTokens.spacing("phone-gutter").dp
    val touch = OrbitTokens.spacing("touch-min").dp
    val alreadyOn by notifier.enabled.collectAsState()
    val ask = rememberNotificationPermission()
    var refused by remember { mutableStateOf(false) }
    val offer = notifier.supported && !alreadyOn && !refused
    Column(modifier = modifier.fillMaxSize().background(o.bg)) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Box(
                modifier = Modifier.size(120.dp).background(o.accentSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                OrbitMark(OrbitMarkLarge)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Paired with ${hubLabel(paired.hub)}",
                style = Fleet.type.textXl,
                color = o.fg,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "${pairedModeWords(paired.mode).replaceFirstChar { it.uppercaseChar() }} · “${paired.clientName}”",
                style = Fleet.type.textMd,
                color = o.fgMuted,
                textAlign = TextAlign.Center,
            )
            if (offer) {
                Spacer(Modifier.height(28.dp))
                Surface(
                    color = o.bgPane,
                    shape = phoneCard(),
                    border = BorderStroke(1.dp, o.border),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Let Orbit Fleet tell you when a session needs you or fails? Notifications never " +
                            "carry an Approve button; you answer inside the app.",
                        style = Fleet.type.textMd,
                        color = o.fg2,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (refused) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Notifications are turned off for this app in the system's settings.",
                    style = Fleet.type.textSm,
                    color = o.fgMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (offer) {
                PrimaryButton("Allow notifications", enabled = true, height = touch) {
                    ask { granted ->
                        if (granted) {
                            notifier.setEnabled(true)
                            onContinue()
                        } else {
                            refused = true
                        }
                    }
                }
                QuietOption("Not now", enabled = true, onClick = onContinue)
                Text(
                    "You can change this in Settings › This phone.",
                    style = Fleet.type.textXs,
                    color = o.fgMuted,
                )
            } else {
                PrimaryButton("Open the fleet", enabled = true, height = touch, onClick = onContinue)
            }
        }
    }
}
