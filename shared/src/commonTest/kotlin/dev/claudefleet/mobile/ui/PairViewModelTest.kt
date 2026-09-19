@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AuthActions
import dev.claudefleet.mobile.data.AuthState
import dev.claudefleet.mobile.data.NotAPairingCode
import dev.claudefleet.mobile.data.REVOKED_CREDENTIAL_REASON
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.store.Credentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TOKEN = "tok-0123456789abcdef-SECRET"
private const val HUB = "https://hub.example.com"
private const val PAIR_URL = "$HUB/pair#ABCD1234"

/**
 * The credential side of the app, driven by the test rather than by a hub.
 *
 * [AuthActions] has exactly the two methods a screen may reach — pair, and
 * forget — so a fake of it is three lines and, more to the point, a screen
 * handed one cannot revoke anything even by accident.
 */
private class FakeAuth : AuthActions {
    override val state = MutableStateFlow<AuthState>(AuthState.Unpaired)
    override val unpairReason = MutableStateFlow<String?>(null)

    /** Every `(scanned, base)` this was asked to redeem, in order. */
    val pairs = mutableListOf<Pair<String, String?>>()

    var forgets = 0
        private set

    var reasonClears = 0
        private set

    /** Held open, a pair stays in flight so a second camera frame can arrive. */
    var gate: CompletableDeferred<Unit>? = null

    /** Thrown instead of answering. */
    var failWith: Throwable? = null

    /** What a successful pair buys. */
    var issue: Credentials = Credentials(HUB, TOKEN, "phone", Credentials.FULL)

    override suspend fun pair(scanned: String, base: String?): Credentials {
        pairs += scanned to base
        gate?.await()
        failWith?.let { throw it }
        state.value = AuthState.Paired(issue)
        return issue
    }

    override suspend fun forget() {
        forgets += 1
        state.value = AuthState.Unpaired
    }

    override fun clearUnpairReason() {
        reasonClears += 1
        unpairReason.value = null
    }
}

class PairViewModelTest {

    // -----------------------------------------------------------------------
    // A scan that is not a pairing code
    // -----------------------------------------------------------------------

    @Test
    fun a_scan_that_is_not_a_pairing_code_is_rejected_with_a_message() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        vm.onScanned("https://example.com/some/other/qr")
        runCurrent()

        assertNotNull(vm.state.value.error)
        assertNull(vm.state.value.paired)
        assertEquals(0, auth.pairs.size, "a stranger's QR must not be posted to anything")
    }

    /**
     * A camera pointed at the world reads whatever is in front of it, including
     * someone else's credential, and a refusal is exactly the string that ends
     * up in a screenshot or a support ticket. So the message says what a
     * pairing code looks like and never what it just saw.
     */
    @Test
    fun the_refusal_never_repeats_what_was_scanned() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        vm.onScanned("https://wifi.example.net/join#SUPERSECRETPAYLOAD")
        runCurrent()

        val error = assertNotNull(vm.state.value.error)
        assertFalse("SUPERSECRETPAYLOAD" in error, "the refusal repeated the scan: $error")
        assertFalse("wifi.example.net" in error, "the refusal repeated the scan: $error")
    }

    /**
     * A bare code names no hub. Refusing here rather than letting the call fail
     * is what keeps a camera aimed at the *right* QR from posting to nowhere
     * thirty times a second.
     */
    @Test
    fun a_bare_code_with_no_hub_address_asks_for_one_rather_than_calling() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        vm.onScanned("ABCD1234")
        runCurrent()

        assertEquals(NotAPairingCode.NO_HUB, vm.state.value.error)
        assertEquals(0, auth.pairs.size)
    }

    // -----------------------------------------------------------------------
    // A successful pair
    // -----------------------------------------------------------------------

    @Test
    fun a_successful_scan_pairs_and_names_the_hub() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        vm.onScanned(PAIR_URL)
        runCurrent()

        assertEquals(PairedHub(HUB, "phone", Credentials.FULL), vm.state.value.paired)
        assertEquals(listOf<Pair<String, String?>>(PAIR_URL to null), auth.pairs)
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.pairing)
    }

    /**
     * The Pair screen is the one place in the app that has the token in hand,
     * so it is the one place worth asserting it does not keep it.
     */
    @Test
    fun the_paired_state_never_carries_the_token() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        vm.onScanned(PAIR_URL)
        runCurrent()

        val state = vm.state.value
        assertNotNull(state.paired)
        assertFalse(TOKEN in state.toString(), "the token reached the ui state: $state")
    }

    @Test
    fun a_typed_code_pairs_against_the_typed_address() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = false)

        vm.onAddressChange("http://10.0.0.4:8899")
        // Hyphens and case are the operator reading it off a terminal; the
        // folding is `PairTarget`'s and is not repeated here.
        vm.onCodeChange("abcd-1234")
        assertTrue(vm.state.value.canSubmit)
        vm.submit()
        runCurrent()

        assertEquals(listOf<Pair<String, String?>>("abcd-1234" to "http://10.0.0.4:8899"), auth.pairs)
        assertNotNull(vm.state.value.paired)
    }

    // -----------------------------------------------------------------------
    // The app has to work without the camera
    // -----------------------------------------------------------------------

    @Test
    fun manual_entry_works_when_there_is_no_camera_at_all() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = false)

        assertFalse(vm.state.value.scanning)
        assertFalse(vm.state.value.cameraAvailable)

        vm.onAddressChange(HUB)
        vm.onCodeChange("ABCD1234")
        assertTrue(vm.state.value.canSubmit, "the manual field must not depend on the camera")
        vm.submit()
        runCurrent()

        assertNotNull(vm.state.value.paired)
    }

    /**
     * The permission refused, or no camera on the device: the scanner goes away
     * and says why, and the fields it was sitting above still work.
     */
    @Test
    fun a_camera_that_will_not_start_leaves_manual_entry_in_place() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)
        vm.setScanning(true)
        assertTrue(vm.state.value.scanning)

        vm.onScannerUnavailable("the camera permission was refused")

        assertFalse(vm.state.value.scanning)
        assertEquals("the camera permission was refused", vm.state.value.error)
        vm.onAddressChange(HUB)
        vm.onCodeChange("ABCD1234")
        assertTrue(vm.state.value.canSubmit)
    }

    // -----------------------------------------------------------------------
    // The camera is opened by a tap, never by arriving on the screen
    // -----------------------------------------------------------------------

    /**
     * On Android the permission dialog appears when the scanner composable is
     * first composed — so if the scanner were up the moment the Pair screen
     * appeared, a fresh install's *first* screen would be a camera prompt, from
     * an app that has not yet said what it is for. It also means someone who
     * intends to type the code has to dismiss a dialog about a camera they were
     * never going to use, and on Android 11+ two reflexive dismissals deny the
     * permission permanently.
     *
     * So the screen opens closed. [PairViewModel.setScanning] is the only thing
     * that opens it, and only the button calls it.
     */
    @Test
    fun the_camera_is_not_open_when_the_pair_screen_appears() = runTest {
        val vm = PairViewModel(FakeAuth(), backgroundScope, cameraAvailable = true)

        assertTrue(vm.state.value.cameraAvailable, "this device can scan")
        assertFalse(vm.state.value.scanning, "but nothing has asked it to yet")
    }

    @Test
    fun the_scanner_opens_only_when_someone_asks_for_it() = runTest {
        val vm = PairViewModel(FakeAuth(), backgroundScope, cameraAvailable = true)

        vm.setScanning(true)
        assertTrue(vm.state.value.scanning)

        vm.setScanning(false)
        assertFalse(vm.state.value.scanning, "and it closes again on the second tap")
    }

    /** No camera, no scanner — whatever the screen asks for. */
    @Test
    fun asking_for_a_scanner_this_build_does_not_have_changes_nothing() = runTest {
        val vm = PairViewModel(FakeAuth(), backgroundScope, cameraAvailable = false)

        vm.setScanning(true)

        assertFalse(vm.state.value.scanning)
    }

    /**
     * The whole typed path, start to finish, with every state it passed through
     * checked rather than only the last one: a single frame in which `scanning`
     * went true is a frame in which Android would have asked for the camera.
     */
    @Test
    fun the_manual_path_never_opens_the_camera() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)
        val seen = mutableListOf<Boolean>()
        seen += vm.state.value.scanning

        vm.onAddressChange(HUB)
        seen += vm.state.value.scanning
        vm.onCodeChange("ABCD1234")
        seen += vm.state.value.scanning
        vm.submit()
        seen += vm.state.value.scanning
        runCurrent()
        seen += vm.state.value.scanning

        assertNotNull(vm.state.value.paired, "typing the code has to be enough to pair")
        assertEquals(listOf(false, false, false, false, false), seen, "the camera was opened on the typed path")
    }

    /**
     * The permission permanently denied — two refusals on Android 11+, after
     * which `launch` returns denied without showing anything. The platform layer
     * reports it the same way it reports any other camera failure, and what has
     * to survive is the rest of the screen.
     */
    @Test
    fun a_permanently_denied_camera_still_pairs_by_hand() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        vm.setScanning(true)
        // What the Android layer calls back with when `launch` returns denied.
        vm.onScannerUnavailable(
            "the camera permission was refused. Type the 8-character code instead — " +
                "it is printed under the QR.",
        )
        assertFalse(vm.state.value.scanning)

        vm.onAddressChange(HUB)
        vm.onCodeChange("ABCD1234")
        vm.submit()
        runCurrent()

        assertNotNull(vm.state.value.paired)
        assertFalse(vm.state.value.scanning, "a refused camera must not reopen itself")
        assertEquals(1, auth.pairs.size)
    }

    /** Reopening the camera is a new attempt; the last one's message goes. */
    @Test
    fun opening_the_scanner_clears_what_the_last_attempt_said() = runTest {
        val vm = PairViewModel(FakeAuth(), backgroundScope, cameraAvailable = true)

        vm.setScanning(true)
        vm.onScannerUnavailable("the camera could not be started")
        assertNotNull(vm.state.value.error)

        vm.setScanning(true)

        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.scanning)
    }

    /** Closing it is not an attempt at anything, so it says nothing. */
    @Test
    fun closing_the_scanner_leaves_the_message_alone() = runTest {
        val auth = FakeAuth()
        auth.failWith = HubError.Http(404, "no such pairing code")
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        vm.setScanning(true)
        vm.onScanned(PAIR_URL)
        runCurrent()
        val refusal = assertNotNull(vm.state.value.error)

        vm.setScanning(false)

        assertEquals(refusal, vm.state.value.error)
    }

    // -----------------------------------------------------------------------
    // A camera delivers the same frame over and over
    // -----------------------------------------------------------------------

    /**
     * A pairing code is single use: `consume` removes it from the registry on
     * the first `POST /pair` (`mcp/pairing.rs`). A camera looking at one QR
     * hands it over thirty times a second, so without this, frames 2..n would
     * each post a spent code and the *success* on screen would be overwritten
     * by a refusal — and the hub's ten-a-minute rate limiter would be tripped
     * by the app's own eagerness.
     */
    @Test
    fun one_QR_held_in_front_of_the_camera_is_one_attempt() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        // Frames both back-to-back and fully settled, because three separate
        // guards could each be the one doing the work and the claim is that the
        // count is one however they interleave.
        repeat(15) { vm.onScanned(PAIR_URL) }
        repeat(15) {
            vm.onScanned(PAIR_URL)
            runCurrent()
        }

        assertEquals(1, auth.pairs.size)
        assertNotNull(vm.state.value.paired)
    }

    @Test
    fun a_different_code_arriving_while_one_is_in_flight_is_ignored() = runTest {
        val auth = FakeAuth()
        auth.gate = CompletableDeferred()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        vm.onScanned(PAIR_URL)
        runCurrent()
        assertTrue(vm.state.value.pairing)

        vm.onScanned("$HUB/pair#ZZZZ9999")
        runCurrent()
        assertEquals(1, auth.pairs.size)

        auth.gate?.complete(Unit)
        runCurrent()
        assertNotNull(vm.state.value.paired)
    }

    /**
     * Review S-1. The test above completes the gate with a **success**, so the
     * code it swallowed is never wanted again and the hole is invisible. This
     * one refuses the first code, which is the ordinary case: the first QR is
     * spent or expired, the operator prints another, and the person moves the
     * phone to it while the app is still waiting on the old one.
     *
     * The second code must reach the hub. Before the fix it never did — its key
     * was recorded by `onScanned` before `redeem` decided it would not act, and
     * `if (text == lastScan) return` then dropped it forever. Recovery was to
     * type the code or to have a third QR printed.
     */
    @Test
    fun a_code_seen_during_an_attempt_that_then_fails_is_still_sent() = runTest {
        val auth = FakeAuth()
        auth.gate = CompletableDeferred()
        auth.failWith = HubError.Tool("E_NOT_FOUND", "no such pairing code")
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        vm.onScanned(PAIR_URL)
        runCurrent()
        assertTrue(vm.state.value.pairing)

        // The camera sees the new QR while the old attempt is still open. The
        // app is right not to send it yet — one attempt at a time — but it must
        // not throw the code away.
        val second = "$HUB/pair#ZZZZ9999"
        vm.onScanned(second)
        runCurrent()
        assertEquals(1, auth.pairs.size, "one attempt at a time")

        auth.gate?.complete(Unit)
        runCurrent()
        assertEquals(1, auth.pairs.size)
        assertNotNull(vm.state.value.error, "the first code was refused")

        // Still pointed at the new QR, thirty frames later.
        auth.failWith = null
        repeat(30) { vm.onScanned(second) }
        runCurrent()

        assertEquals(
            listOf(PAIR_URL, second),
            auth.pairs.map { it.first },
            "the code seen during the failed attempt must be sent, exactly once",
        )
        assertNotNull(vm.state.value.paired)
    }

    /**
     * And the burn still happens for the reasons it exists.
     *
     * `redeem` also returns null for input that is not a pairing code and for
     * one that names no hub, and those must keep recording the key: a camera
     * pointed at a QR for a Wi-Fi network would otherwise re-report it thirty
     * times a second, replacing the error with an identical error forever. Only
     * the "an attempt is already in flight" branch may leave `lastScan` alone.
     */
    @Test
    fun a_qr_that_is_not_a_pairing_code_is_still_only_reported_once() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)
        var reports = 0

        repeat(30) {
            vm.onScanned("WIFI:S=coffeeshop;T=WPA;P=hunter2;;")
            if (vm.state.value.error != null) reports += 1
            vm.dismissError()
        }
        runCurrent()

        assertEquals(1, reports, "a QR that is not a pairing code is reported once")
        assertEquals(0, auth.pairs.size, "and never sent anywhere")
    }

    @Test
    fun a_scan_after_pairing_succeeded_does_nothing() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        vm.onScanned(PAIR_URL)
        runCurrent()
        vm.onScanned("$HUB/pair#ZZZZ9999")
        runCurrent()

        assertEquals(1, auth.pairs.size)
        assertEquals(HUB, vm.state.value.paired?.hub)
    }

    // -----------------------------------------------------------------------
    // Refusals
    // -----------------------------------------------------------------------

    @Test
    fun a_code_the_hub_refuses_keeps_the_screen_and_says_why() = runTest {
        val auth = FakeAuth()
        auth.failWith = HubError.Http(404, "no such pairing code")
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        vm.onScanned(PAIR_URL)
        runCurrent()

        val error = assertNotNull(vm.state.value.error)
        assertTrue("404" in error, "the hub's own words should survive: $error")
        assertNull(vm.state.value.paired)
        assertFalse(vm.state.value.pairing)
    }

    /**
     * A refusal is not an invitation to retry, and the camera is still pointing
     * at the same QR. Every reason a pair fails is a reason *not* to send the
     * same code again by reflex: a spent or expired code needs a new QR, and a
     * 429 — the hub allows one attempt every 6 s — needs less traffic, not
     * thirty attempts a second more.
     *
     * This is the test that was missing. Its first version asserted the
     * in-flight guard by accident, and a mutation that deleted the per-frame
     * dedupe outright survived it. See the task report.
     */
    @Test
    fun a_refused_scan_is_not_retried_by_the_camera_on_its_own() = runTest {
        val auth = FakeAuth()
        auth.failWith = HubError.Http(429, "one attempt every 6 s")
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        // One frame at a time, each fully settled, so the in-flight guard is
        // never what is doing the work here.
        repeat(30) {
            vm.onScanned(PAIR_URL)
            runCurrent()
        }

        assertEquals(1, auth.pairs.size, "the camera retried a code the hub had already refused")
        assertNotNull(vm.state.value.error)
    }

    /** …and the screen is not wedged by that: a fresh QR is tried. */
    @Test
    fun a_new_QR_after_a_refusal_is_tried() = runTest {
        val auth = FakeAuth()
        auth.failWith = HubError.Http(404, "no such pairing code")
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = true)

        vm.onScanned(PAIR_URL)
        runCurrent()
        assertNotNull(vm.state.value.error)

        auth.failWith = null
        vm.onScanned("$HUB/pair#ZZZZ9999")
        runCurrent()

        assertEquals(2, auth.pairs.size)
        assertNotNull(vm.state.value.paired)
    }

    /**
     * Nor is the manual path. The button is a person deciding to try again,
     * which is the one thing the per-frame rule is not about.
     */
    @Test
    fun the_button_tries_a_typed_code_again_after_a_refusal() = runTest {
        val auth = FakeAuth()
        auth.failWith = HubError.Http(502, "bad gateway")
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = false)
        vm.onAddressChange(HUB)
        vm.onCodeChange("ABCD1234")

        vm.submit()
        runCurrent()
        assertNotNull(vm.state.value.error)
        assertEquals("ABCD1234", vm.state.value.code, "a bounced code must not have to be retyped")

        auth.failWith = null
        vm.submit()
        runCurrent()

        assertEquals(2, auth.pairs.size)
        assertNotNull(vm.state.value.paired)
    }

    @Test
    fun the_button_is_disabled_while_a_pair_is_in_flight() = runTest {
        val auth = FakeAuth()
        auth.gate = CompletableDeferred()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = false)
        vm.onAddressChange(HUB)
        vm.onCodeChange("ABCD1234")

        vm.submit()
        runCurrent()
        assertTrue(vm.state.value.pairing)
        assertFalse(vm.state.value.canSubmit)

        vm.submit()
        runCurrent()
        assertEquals(1, auth.pairs.size)
    }

    @Test
    fun a_blank_code_is_not_submittable() = runTest {
        val auth = FakeAuth()
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = false)
        vm.onAddressChange(HUB)

        assertFalse(vm.state.value.canSubmit)
        vm.submit()
        runCurrent()
        assertEquals(0, auth.pairs.size)
    }

    // -----------------------------------------------------------------------
    // A 401 that dropped this device's credential explains itself
    // -----------------------------------------------------------------------

    @Test
    fun a_standing_reason_is_shown_when_the_screen_opens() = runTest {
        val auth = FakeAuth()
        auth.unpairReason.value = REVOKED_CREDENTIAL_REASON
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = false)

        assertEquals(
            REVOKED_CREDENTIAL_REASON,
            vm.state.value.reason,
        )
    }

    /** A first launch, or a screen reached by a user-initiated forget, has nothing to explain. */
    @Test
    fun no_reason_is_shown_on_an_ordinary_visit() = runTest {
        val vm = PairViewModel(FakeAuth(), backgroundScope, cameraAvailable = false)

        assertNull(vm.state.value.reason)
    }

    @Test
    fun dismissing_the_reason_clears_it_here_and_in_the_session() = runTest {
        val auth = FakeAuth()
        auth.unpairReason.value = REVOKED_CREDENTIAL_REASON
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = false)

        vm.dismissReason()

        assertNull(vm.state.value.reason)
        assertEquals(1, auth.reasonClears)
    }

    /** Starting a new attempt is itself a reason to stop showing the old one. */
    @Test
    fun starting_a_pair_attempt_clears_a_standing_reason() = runTest {
        val auth = FakeAuth()
        auth.unpairReason.value = REVOKED_CREDENTIAL_REASON
        val vm = PairViewModel(auth, backgroundScope, cameraAvailable = false)
        vm.onAddressChange(HUB)
        vm.onCodeChange("ABCD1234")

        vm.submit()
        runCurrent()

        assertNull(vm.state.value.reason)
        assertEquals(1, auth.reasonClears)
        assertNotNull(vm.state.value.paired)
    }
}
