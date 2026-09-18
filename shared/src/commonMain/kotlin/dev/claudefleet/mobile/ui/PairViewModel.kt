package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AuthActions
import dev.claudefleet.mobile.data.NotAPairingCode
import dev.claudefleet.mobile.data.PairTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The hub this device has just paired with.
 *
 * Carries no token and cannot: the three fields are the three a person is owed
 * — which hub, under what name, with what rights — and the fourth field of
 * `Credentials` is deliberately not among them.
 */
data class PairedHub(val hub: String, val clientName: String, val mode: String)

/** The Pair screen: a camera, two fields, and whatever went wrong last. */
data class PairUiState(
    /** The hub address, typed. Only consulted for a code that names no hub. */
    val address: String = "",
    val code: String = "",
    /** Whether the camera view is up. Never true without [cameraAvailable]. */
    val scanning: Boolean = false,
    /** Whether this build can scan at all — see `ui/scan/QrScanner.kt`. */
    val cameraAvailable: Boolean = false,
    val pairing: Boolean = false,
    val error: String? = null,
    /** Non-null once the hub has answered. The screen leaves when it is set. */
    val paired: PairedHub? = null,
) {
    /** Whether the manual-entry button does anything. */
    val canSubmit: Boolean get() = !pairing && paired == null && code.isNotBlank()
}

/**
 * Pairing: point the camera at the QR `fleet-hub pair` shows, or type the code
 * printed beneath it.
 *
 * The manual field is not a fallback that appears when the camera fails — it is
 * always there. A phone that has never been granted the camera permission, or
 * has no camera, must still be able to pair, and a person reading a code down
 * the phone to a colleague is the ordinary case rather than the degraded one.
 *
 * Two rules here exist because of what a camera actually delivers. It reports
 * the same QR on every frame, thirty times a second, and a pairing code is
 * **single use** — `PairingRegistry::consume` takes it out of the registry on
 * the first `POST /pair` and the hub throttles a source address to ten attempts
 * a minute. So:
 *
 *  1. **One QR is one attempt, whatever the answer was.** A scan identical to
 *     the last one acted on is dropped, and so is one arriving while a pair is
 *     in flight or after one has succeeded. Without this the success on screen
 *     would be overwritten immediately by 29 refusals of a code the app itself
 *     had just spent, and the rate limiter would be tripped by the app's own
 *     eagerness. A *refusal* does not reset the rule either: a spent or expired
 *     code needs a new QR, and a 429 needs less traffic rather than thirty
 *     attempts a second more. Retrying is a deliberate act — hold up a fresh
 *     QR, or use the button.
 *  2. **A code that names no hub is refused here, not at the hub.** Otherwise
 *     the same camera posts to nowhere at the same rate.
 *
 * Plain Kotlin, not an `androidx.lifecycle.ViewModel`, for the reason the other
 * view models give: the same class runs on iOS.
 */
class PairViewModel(
    private val auth: AuthActions,
    private val scope: CoroutineScope,
    cameraAvailable: Boolean = false,
) {
    private val _state = MutableStateFlow(
        PairUiState(cameraAvailable = cameraAvailable, scanning = cameraAvailable),
    )
    val state: StateFlow<PairUiState> = _state.asStateFlow()

    /**
     * The last text a scan was acted on for, so a QR held in front of the lens
     * is one attempt rather than one per frame. Cleared on failure, so the same
     * QR can be tried again deliberately.
     */
    private var lastScan: String? = null

    fun onAddressChange(text: String) {
        _state.value = _state.value.copy(address = text)
    }

    fun onCodeChange(text: String) {
        _state.value = _state.value.copy(code = text)
    }

    /** Show or hide the camera. Hiding it never hides the fields underneath. */
    fun setScanning(on: Boolean) {
        _state.value = _state.value.copy(scanning = on && _state.value.cameraAvailable)
    }

    /**
     * The camera could not be used: the permission was refused, there is no
     * camera, or the capture session would not start.
     *
     * [reason] is the platform layer's own sentence and never an exception's
     * text — a camera error can name a file path or a device id, and this string
     * goes on the screen.
     */
    fun onScannerUnavailable(reason: String) {
        _state.value = _state.value.copy(scanning = false, error = reason)
    }

    /** One decoded QR. Called per frame; see the class comment. */
    fun onScanned(text: String) {
        if (text == lastScan) return
        lastScan = text
        redeem(text)
    }

    /** The manual-entry button. Deliberately bypasses the per-frame dedupe. */
    fun submit(): Job? = redeem(_state.value.code)

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    private fun redeem(input: String): Job? {
        val current = _state.value
        if (current.pairing || current.paired != null) return null
        if (input.isBlank()) return null

        val target = try {
            PairTarget.require(input)
        } catch (e: NotAPairingCode) {
            _state.value = current.copy(error = explain(e))
            return null
        }
        val typed = current.address.takeIf { it.isNotBlank() }
        if (target.base == null && typed == null) {
            _state.value = current.copy(error = NotAPairingCode.NO_HUB)
            return null
        }

        _state.value = current.copy(pairing = true, error = null)
        return scope.launch {
            try {
                // The raw input goes down, not `target`: parsing it twice is
                // cheaper than two places that disagree about what a code is,
                // and `AppSession` is where the "which base wins" rule lives.
                val credentials = auth.pair(input, typed)
                _state.value = _state.value.copy(
                    pairing = false,
                    code = "",
                    paired = PairedHub(credentials.hub, credentials.name, credentials.mode),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // The typed code stays: retyping something the hub bounced is a
                // poor way to find out the hub was rate-limiting.
                //
                // `lastScan` deliberately stays set too, so the camera does not
                // retry a QR it is still pointing at. Every reason a pair fails
                // is a reason not to try the same code again by reflex: a spent
                // or expired code needs a new QR, and a 429 needs less traffic,
                // not thirty attempts a second more. Retrying is a deliberate
                // act — a fresh QR, or the button.
                _state.value = _state.value.copy(pairing = false, error = explain(t))
            }
        }
    }
}
