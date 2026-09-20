package dev.claudefleet.mobile.store

import dev.claudefleet.mobile.ui.explain
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KeychainSecrets` **when the Keychain refuses** — which, on this test host, is
 * always, and that turns out to be the useful half.
 *
 * Until this file the class had never run a line; its own KDoc said so
 * ("Linked, never run"). It now runs, under `iosSimulatorArm64Test`, and the
 * first thing running it established is a limit worth writing down rather than
 * working around:
 *
 * **A Kotlin/Native test binary gets no Keychain.** The Kotlin plugin launches
 * it with `simctl spawn`, so it is a bare Mach-O executable rather than an
 * installed app: no bundle identifier, no `keychain-access-group` entitlement,
 * and `securityd` answers every request with `errSecNotAvailable` (**-25291**).
 * That is not a bug in `KeychainSecrets` and no amount of care in it would
 * change the answer — covering the happy-path round trip needs an XCTest target
 * hosted by `iosApp`, which is the one piece of iOS coverage still missing.
 * `AndroidSecretsTest` has the round trip because an instrumentation test *is*
 * installed as an app; this is the difference between the two platforms' test
 * hosts, not between the two stores.
 *
 * So these tests assert what a refusing Keychain is supposed to produce, and
 * that turns out to be the half that had no coverage anywhere and the half that
 * matters most when it is wrong. A store that refuses is not hypothetical in
 * production either: the class chooses
 * `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` precisely so a background
 * wake can read the token, which makes *a background wake before the device's
 * first unlock* — `errSecInteractionNotAllowed` — a case the design invites.
 * Every claim below holds for that case exactly as it does for this one: they
 * are both "the Keychain said no".
 *
 * The three rules under test are the ones the app is built on:
 *
 *  1. **A store that cannot be read reads as "not paired"** rather than
 *     throwing. `AppSession.restore()` has no catch, so anything else here is a
 *     crash on every cold start with no way out but a reinstall. Android was
 *     given this degrade by review finding S2; nothing had ever checked that
 *     iOS behaves the same way.
 *  2. **A store that will not write or clear says so, loudly.** `Secrets.clear`
 *     throws by contract because `AppSession.forget()` publishes `Unpaired` on
 *     the strength of it returning — review finding S3 — and a silent no-op
 *     there leaves the token on disk while the operator believes it is gone.
 *  3. **The refusal is a sentence a person can read**, carrying an `OSStatus`
 *     and never the value it failed to store.
 */
class KeychainSecretsTest {

    private val credential = Credentials(
        hub = "https://fleet.example.com",
        token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        name = "phone",
        mode = Credentials.FULL,
    )

    private fun store() = KeychainSecrets(
        service = "dev.claudefleet.mobile.test",
        account = "keychain-secrets-test",
    )

    /**
     * The precondition every other test here rests on, asserted rather than
     * assumed — and written to fail **loudly and usefully** if it ever stops
     * being true.
     *
     * If this test starts failing, the environment gained a Keychain: someone
     * has given the iOS side a bundled test host. That is good news and the
     * right response is not to relax this test but to replace this whole file
     * with the round trip `AndroidSecretsTest` already has — write, read back,
     * replace, clear — because at that point it can finally be written.
     */
    @Test
    fun this_test_host_has_no_keychain_and_that_is_why_the_rest_of_this_file_reads_as_it_does() =
        runTest {
            val refusal = assertFailsWith<KeychainFailure>(
                "a `simctl spawn`-ed test binary is not an installed app, so it holds no " +
                    "keychain-access-group entitlement and securityd refuses it",
            ) { store().write(credential) }

            assertEquals(
                ERR_SEC_NOT_AVAILABLE,
                refusal.status,
                "if the Keychain now answers something else — or succeeds — this file is " +
                    "testing the wrong thing and should become the round trip instead",
            )
        }

    /**
     * Rule 1. An unreadable store reads as "not paired", and does not throw.
     *
     * `AppSession.restore()` is called from a `LaunchedEffect` with no catch, so
     * a throw here is a crash on the first frame of every cold start. The app
     * showing Pair is recoverable; the app not starting is not.
     */
    @Test
    fun a_refusing_keychain_reads_as_unpaired_rather_than_throwing() = runTest {
        assertNull(
            store().read(),
            "an unreadable store is the same as an empty one — anything else crashes the app " +
                "on every cold start, which is the degrade `decodeCredentials` already makes",
        )
    }

    /** And it keeps doing so, rather than failing differently the second time. */
    @Test
    fun reading_a_refusing_keychain_is_repeatable() = runTest {
        val secrets = store()
        assertNull(secrets.read())
        assertNull(secrets.read())
    }

    /**
     * Rule 2, the write half. A credential that was not stored must not look
     * stored.
     *
     * `AppSession.pair()` writes and then publishes `Paired` on the next line.
     * If the write failed quietly, the app would show the fleet, work until it
     * was next launched, and come back unpaired with nothing having said why.
     */
    @Test
    fun a_write_that_cannot_land_throws_rather_than_claiming_success() = runTest {
        assertFailsWith<SecretsUnavailable>("a failed write must not look like a stored credential") {
            store().write(credential)
        }
    }

    /**
     * Rule 2, the clear half — the more dangerous of the two, and the one review
     * finding S3 names.
     *
     * `AppSession.forget()` publishes `Unpaired` when this returns. A silent
     * no-op would tell someone who has just lost their phone that the credential
     * is gone while it is still on disk. `SettingsViewModel` is built around
     * this throwing: it reports the failure and *stays paired*.
     */
    @Test
    fun a_clear_that_cannot_land_throws_rather_than_silently_doing_nothing() = runTest {
        assertFailsWith<SecretsUnavailable>(
            "forget() publishes Unpaired on the strength of this returning",
        ) { store().clear() }
    }

    /**
     * Rule 3. The refusal reaches the screen as a sentence, with the status and
     * without the value.
     *
     * `explain()` repeats a message only for the app's own exceptions that
     * promise to carry nothing from outside, and [KeychainFailure] is a
     * [SecretsUnavailable] so that it qualifies — as a bare `Exception` it fell
     * through to "something went wrong (KeychainFailure)" while the identical
     * Android failure read as a sentence.
     */
    @Test
    fun the_refusal_is_shown_as_words_and_never_as_the_stored_value() = runTest {
        val refusal = assertFailsWith<KeychainFailure> { store().clear() }
        val shown = explain(refusal)

        assertTrue(credential.token !in shown, "an OSStatus, never the value it failed to store")
        assertTrue("${refusal.status}" in shown, "the status is what an operator greps for")
        assertTrue(
            "something went wrong" !in shown,
            "a KeychainFailure is a SecretsUnavailable precisely so explain() shows it",
        )
    }

    /**
     * The status this class's own KDoc names as the reachable production case
     * gets the same treatment as the one this host happens to produce.
     *
     * Constructed rather than provoked — no test can put a simulator into
     * "before first unlock" — but the point is that nothing branches on *which*
     * refusal it was, so covering one covers the other.
     */
    @Test
    fun the_before_first_unlock_refusal_reads_the_same_way() {
        val shown = explain(KeychainFailure(ERR_SEC_INTERACTION_NOT_ALLOWED))

        assertTrue("$ERR_SEC_INTERACTION_NOT_ALLOWED" in shown)
        assertTrue("something went wrong" !in shown)
        assertTrue("Keychain" in shown, "the sentence should say which store refused")
    }

    /**
     * The stored form is the shared encoding, so what this platform writes is
     * what the shared decoder reads.
     *
     * Pure, and therefore the one thing here a missing Keychain cannot affect —
     * which is also why it is worth having: it is the half of the round trip
     * that *can* be checked on this host.
     */
    @Test
    fun the_stored_form_round_trips_through_the_shared_encoding() {
        assertEquals(credential, decodeCredentials(credential.encode()))
    }

    /**
     * Including a credential whose text is not ASCII.
     *
     * The value crosses a C boundary as UTF-8 (`encodeToByteArray` out,
     * `readBytes(...).decodeToString()` back) and a length taken in the wrong
     * units is the classic way that breaks — invisibly, for everyone whose
     * client name happens to be ASCII. The encoding half is checkable here; the
     * C boundary itself is part of what still needs a bundled host.
     */
    @Test
    fun a_credential_with_non_ascii_text_survives_the_shared_encoding() {
        val awkward = Credentials(
            hub = "https://fleet.example.com",
            token = credential.token,
            name = "Martin's iPhone — kuchyňa 🛰",
            mode = Credentials.FULL,
        )
        assertEquals(awkward, decodeCredentials(awkward.encode()))
    }

    private companion object {
        /** `errSecNotAvailable` — no keychain for this process at all. */
        const val ERR_SEC_NOT_AVAILABLE = -25291

        /**
         * `errSecInteractionNotAllowed` — the device has not been unlocked since
         * boot. The production case `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`
         * invites, named in `KeychainSecrets`' own KDoc.
         */
        const val ERR_SEC_INTERACTION_NOT_ALLOWED = -25308
    }
}
