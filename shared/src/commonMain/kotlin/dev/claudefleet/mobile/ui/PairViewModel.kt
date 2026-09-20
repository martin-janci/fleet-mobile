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
import kotlinx.coroutines.flow.update
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
    /**
     * Whether the camera view is up. Never true without [cameraAvailable], and
     * never true until someone has asked for it — see [PairViewModel.setScanning].
     */
    val scanning: Boolean = false,
    /** Whether this build can scan at all — see `ui/scan/QrScanner.kt`. */
    val cameraAvailable: Boolean = false,
    val pairing: Boolean = false,
    val error: String? = null,
    /** Non-null once the hub has answered. The screen leaves when it is set. */
    val paired: PairedHub? = null,
    /**
     * Why this screen is up rather than the fleet — set only when a 401 drops
     * the credential. Shown once; a first launch or a user-initiated forget
     * carries none. See [AuthActions.unpairReason].
     */
    val reason: String? = null,
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
 * A third rule is about the permission rather than the camera: **the scanner
 * starts closed.** Composing it is what makes Android ask for the camera, and
 * this screen is what a fresh install opens on, so a scanner that came up by
 * itself would make a permission dialog the first thing the app ever showed —
 * before it had said what it was for, and in front of someone who may have been
 * intending to type the eight characters all along. On Android 11+ two
 * reflexive dismissals deny the permission for good, and at that point the
 * manual field stops being the alternative and becomes the only way in. So the
 * camera is opened by a tap, and by nothing else.
 *
 * Plain Kotlin, not an `androidx.lifecycle.ViewModel`, for the reason the other
 * view models give: the same class runs on iOS.
 */
class PairViewModel(
    private val auth: AuthActions,
    private val scope: CoroutineScope,
    cameraAvailable: Boolean = false,
) {
    // A snapshot, not a live `combine` with `auth.unpairReason`: this view model
    // is recreated fresh each time the app lands back on Pair (`App.kt` keys it
    // on the composable's own scope), so reading the value once at construction
    // is already "show it once" — and it means `dismissReason` can clear the
    // local copy without the flow it came from racing straight back in.
    private val _state = MutableStateFlow(
        PairUiState(cameraAvailable = cameraAvailable, reason = auth.unpairReason.value),
    )
    val state: StateFlow<PairUiState> = _state.asStateFlow()

    /**
     * The last text a scan was acted on for, so a QR held in front of the lens
     * is one attempt rather than one per frame. Cleared on failure, so the same
     * QR can be tried again deliberately.
     */
    private var lastScan: String? = null

    fun onAddressChange(text: String) {
        _state.update { it.copy(address = text) }
    }

    fun onCodeChange(text: String) {
        _state.update { it.copy(code = text) }
    }

    /**
     * Show or hide the camera. The only thing that opens the scanner, and so
     * the only thing that can make Android ask for the camera permission.
     *
     * Hiding it never hides the fields underneath. Opening it clears whatever
     * the last attempt left on screen — a new deliberate act deserves a clean
     * screen, and "the camera permission was refused" sitting above a live
     * viewfinder reads as a lie. Closing it clears nothing: a hub's refusal is
     * still worth reading after the camera has gone.
     */
    fun setScanning(on: Boolean) {
        val open = on && _state.value.cameraAvailable
        _state.update { it.copy(scanning = open, error = if (open) null else it.error) }
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
        _state.update { it.copy(scanning = false, error = reason) }
    }

    /** One decoded QR. Called per frame; see the class comment. */
    fun onScanned(text: String) {
        // A frame arriving while an attempt is already in flight is not acted
        // on, so its key must NOT be recorded. The old order set `lastScan`
        // first and `redeem` then returned early on `pairing`, which burned a
        // good QR that had never been sent anywhere.
        //
        // The scenario is the ordinary one: the first code is spent, the
        // operator runs `fleet-hub pair` again, and the person moves the phone
        // to the new QR while the app is still waiting on the old one. The
        // screen then read "no such pairing code" — true of a code nobody was
        // pointing at any more — while the good QR in front of the lens did
        // nothing at all, for as long as anyone cared to hold it there.
        //
        // This branch is the only exemption. Every *decided* outcome still
        // burns the key, refusals included, or a QR that is not a pairing code
        // re-reports itself thirty times a second. `redeem` says why.
        if (_state.value.pairing) return

        if (text == lastScan) return
        lastScan = text
        redeem(text)
    }

    /** The manual-entry button. Deliberately bypasses the per-frame dedupe. */
    fun submit(): Job? = redeem(_state.value.code)

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    /** Put away a standing [PairUiState.reason] — read once, either explicitly or by dismissal. */
    fun dismissReason() {
        _state.update { it.copy(reason = null) }
        auth.clearUnpairReason()
    }

    private fun redeem(input: String): Job? {
        val current = _state.value
        if (current.pairing || current.paired != null) return null
        if (input.isBlank()) return null

        val target = try {
            PairTarget.require(input)
        } catch (e: NotAPairingCode) {
            _state.update { it.copy(error = explain(e)) }
            return null
        }
        val typed = current.address.takeIf { it.isNotBlank() }
        if (target.base == null && typed == null) {
            _state.update { it.copy(error = NotAPairingCode.NO_HUB) }
            return null
        }

        // A deliberate new attempt is itself a reason to stop showing why the
        // old credential was dropped.
        //
        // `update {}` rather than `_state.value = current.copy(...)`, which is
        // what these three writes were. `current` is a snapshot taken at the
        // top of this function, before the parse; writing `current.copy(...)`
        // several branches later rewrites the *whole* state from it, so a
        // keystroke that landed in between — `onCodeChange` and
        // `onAddressChange` write this same flow — would be silently undone.
        // Nothing interleaves today, because every caller of this class is on
        // the UI thread on both platforms, but that is the dispatcher's
        // guarantee rather than this code's, and the rest of the app has
        // already settled on `update {}` for exactly this reason — see
        // `SessionViewModel.local`, whose KDoc states the rule, and the same
        // change already made to `HostsViewModel`.
        _state.update { it.copy(pairing = true, error = null, reason = null) }
        auth.clearUnpairReason()
        return scope.launch {
            try {
                // The raw input goes down, not `target`: parsing it twice is
                // cheaper than two places that disagree about what a code is,
                // and `AppSession` is where the "which base wins" rule lives.
                val credentials = auth.pair(input, typed)
                _state.update { it.copy(
                    pairing = false,
                    code = "",
                    paired = PairedHub(credentials.hub, credentials.name, credentials.mode),
                ) }
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
                _state.update { it.copy(pairing = false, error = explain(t)) }
            }
        }
    }
}
