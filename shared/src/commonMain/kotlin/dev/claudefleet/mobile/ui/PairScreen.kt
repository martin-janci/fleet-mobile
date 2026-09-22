package dev.claudefleet.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.components.ErrorBanner
import dev.claudefleet.mobile.ui.scan.QrScannerView

/**
 * Pairing: point the camera at the QR `fleet-hub pair` shows, or type the code
 * printed under it.
 *
 * The two fields are **always** drawn, above or below the camera but never
 * instead of it. A phone that has refused the camera permission, or has no
 * camera, has to be able to pair, and reading a code down the phone to whoever
 * is at the terminal is an ordinary way to do it rather than a degraded one.
 *
 * The camera starts **off**, behind a button. Composing the scanner is what
 * makes Android ask for the permission, and this is the screen a fresh install
 * opens on: a viewfinder that came up by itself would put a camera dialog in
 * front of someone who has not been told what this app is yet, and in front of
 * someone who was going to type the code regardless. Everything below the
 * divider is reachable without the camera ever being touched — which is also
 * all that is left once the permission has been permanently denied.
 *
 * Stateless: it draws a [PairUiState] and reports taps. [PairViewModel] is what
 * is tested; this is what only a device can show.
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
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Pair with a hub",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp),
        )
        Text(
            text = "Run `fleet-hub pair` on the machine the hub runs on. It shows a QR " +
                "code and, underneath it, eight characters.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // Why this screen is up rather than the fleet — a 401 dropped the
        // credential. Absent on a first launch or a user-initiated forget.
        //
        // This screen's own state still carries a plain `String?` — see
        // `PairUiState` — so it is wrapped into a [Friendly] only here, at the
        // point `ErrorBanner` needs one, rather than pulling `PairViewModel`
        // into this task's scope.
        val reasonAsFriendly = state.reason?.asGenericFriendly()
        val errorAsFriendly = state.error?.asGenericFriendly()
        ErrorBanner(reasonAsFriendly, onDismiss = onDismissReason)
        ErrorBanner(errorAsFriendly, onDismiss = onDismissError)

        if (state.scanning) {
            Box(
                modifier = Modifier.fillMaxWidth()
                    .padding(16.dp)
                    // Square: the QR is square, and a viewfinder the shape of
                    // the thing being aimed at is easier to aim.
                    .aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                QrScannerView(
                    onScanned = onScanned,
                    onUnavailable = onScannerUnavailable,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (state.cameraAvailable) {
            // The button the camera permission hangs off. Tapping it is the
            // only thing in the app that can make Android ask for the camera,
            // which is why it says what it will do before it does it.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                OutlinedButton(
                    onClick = { onScanningChange(!state.scanning) },
                    enabled = !state.pairing,
                ) {
                    Text(if (state.scanning) "Stop the camera" else "Scan the QR code")
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        Text(
            text = "Or type it",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = state.code,
            onValueChange = onCodeChange,
            label = { Text("Pairing code") },
            placeholder = { Text("ABCD1234") },
            singleLine = true,
            enabled = !state.pairing,
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
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Button(onClick = onSubmit, enabled = state.canSubmit) {
                Text(if (state.pairing) "Pairing…" else "Pair")
            }
        }

        Text(
            text = "The code expires in ten minutes and works once. The token it buys " +
                "stays on this phone; cancelling it is done from the hub.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * The hub answered: name it, and let the person go on deliberately.
 *
 * A confirmation rather than a silent jump to the fleet list, because "which
 * hub did I just hand a credential to" is worth reading once, especially for
 * someone who scanned a QR off a screen they do not own.
 */
@Composable
fun PairedScreen(paired: PairedHub, onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Paired", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            text = paired.hub,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "as “${paired.clientName}”, ${paired.mode}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onContinue) { Text("Open the fleet") }
    }
}
