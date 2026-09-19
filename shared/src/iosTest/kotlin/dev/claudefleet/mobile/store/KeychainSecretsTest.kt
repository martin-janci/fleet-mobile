package dev.claudefleet.mobile.store

import dev.claudefleet.mobile.ui.explain
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The iOS secure store, executed.
 *
 * Until this file, `KeychainSecrets` had never run a line. Its own KDoc said so
 * — *"Linked, never run"* — and that was the whole of what stood behind the one
 * thing this app persists on iOS. Android had the same hole and closed it with
 * an emulator job (`AndroidSecretsTest`); this is the other half, and it needs
 * the same thing that job needed: somewhere the platform's real API exists.
 * That is `iosSimulatorArm64Test`, which CI now runs.
 *
 * Every test uses its own service and account so the cases cannot see each
 * other's items, and `@AfterTest` deletes them — a Keychain item outlives the
 * process that wrote it, so a leftover one would make the next run read a value
 * it did not write. That is the same rule `AndroidSecretsTest` follows for the
 * same reason.
 *
 * What is asserted here is the contract `Secrets` states and nothing about
 * Apple's implementation: a credential survives the round trip, `write`
 * replaces rather than accumulates, `clear` actually removes, and a `clear`
 * with nothing to remove is not an error.
 */
class KeychainSecretsTest {

    private val service = "dev.claudefleet.mobile.test"
    private val accounts = mutableListOf<String>()

    private fun store(): KeychainSecrets {
        val account = "case-${accounts.size}-${randomSuffix()}"
        accounts += account
        return KeychainSecrets(service = service, account = account)
    }

    @AfterTest
    fun tearDown() = runTest {
        for (account in accounts) {
            runCatching { KeychainSecrets(service = service, account = account).clear() }
        }
    }

    private val credential = Credentials(
        hub = "https://fleet.example.com",
        token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        name = "phone",
        mode = Credentials.FULL,
    )

    /** The round trip the whole class exists for. */
    @Test
    fun a_written_credential_comes_back() = runTest {
        val secrets = store()
        secrets.write(credential)

        val read = assertNotNull(secrets.read(), "the item was written, so it must read back")
        assertEquals(credential.hub, read.hub)
        assertEquals(credential.token, read.token)
        assertEquals(credential.name, read.name)
        assertEquals(credential.mode, read.mode)
        assertEquals(credential, read)
    }

    /** An account with no item is not an error and is not a credential. */
    @Test
    fun an_empty_store_reads_as_unpaired() = runTest {
        assertNull(store().read(), "nothing was ever written under this account")
    }

    /**
     * `write` replaces.
     *
     * The implementation deletes and then adds rather than branching on whether
     * an item exists, precisely so there is no path where a failed update leaves
     * the *old* token in place. This is that claim: after re-pairing against a
     * different hub, reading gives the new credential and not the first one.
     */
    @Test
    fun writing_twice_keeps_only_the_second() = runTest {
        val secrets = store()
        secrets.write(credential)

        val replacement = Credentials(
            hub = "https://other.example.com",
            token = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
            name = "kiosk",
            mode = Credentials.READONLY,
        )
        secrets.write(replacement)

        assertEquals(replacement, secrets.read(), "the second write must replace the first")
    }

    /**
     * `clear` removes the item rather than reporting success and leaving it.
     *
     * This is the failure `Secrets.clear`'s contract is written around: the app
     * publishes `Unpaired` on the strength of this call returning, so a silent
     * no-op would leave the token on disk for the next cold start to find while
     * the operator believes it was forgotten.
     */
    @Test
    fun clearing_really_removes_the_item() = runTest {
        val secrets = store()
        secrets.write(credential)
        assertNotNull(secrets.read())

        secrets.clear()

        assertNull(secrets.read(), "clear() must remove the item, not merely claim to")
    }

    /** `errSecItemNotFound` is benign: there was nothing to delete. */
    @Test
    fun clearing_an_empty_store_is_not_an_error() = runTest {
        store().clear()
    }

    /**
     * Two accounts under one service do not share an item.
     *
     * The query is `kSecClass` + `kSecAttrService` + `kSecAttrAccount`, so this
     * is really a check that the account is part of the identity rather than
     * decoration — if it were dropped from the query, every store in the process
     * would be the same store.
     */
    @Test
    fun two_accounts_hold_separate_items() = runTest {
        val first = store()
        val second = store()
        first.write(credential)

        assertNull(second.read(), "a different account must not see the first one's item")
        assertEquals(credential, first.read())
    }

    /**
     * The stored form is the shared JSON, so a value written on one platform has
     * the same shape as on the other.
     *
     * Read back through `decodeCredentials`, which is the function both stores
     * use, rather than by inspecting bytes: what matters is that the encoding
     * this platform writes is the encoding the shared decoder expects.
     */
    @Test
    fun the_stored_form_is_the_shared_encoding() = runTest {
        assertEquals(credential, decodeCredentials(credential.encode()))
    }

    /**
     * A credential with awkward text survives.
     *
     * The value crosses a C boundary as UTF-8 bytes (`encodeToByteArray` out,
     * `readBytes(...).decodeToString()` back), and a length taken in the wrong
     * units is the classic way that goes wrong — invisibly, for anyone whose
     * client name is ASCII.
     */
    @Test
    fun a_credential_with_non_ascii_text_survives() = runTest {
        val secrets = store()
        val awkward = Credentials(
            hub = "https://fleet.example.com",
            token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            name = "Martin's iPhone — kuchyňa 🛰",
            mode = Credentials.FULL,
        )
        secrets.write(awkward)

        val read = assertNotNull(secrets.read())
        assertEquals(awkward.name, read.name, "the name crosses a C boundary as UTF-8")
        assertEquals(awkward, read)
    }

    /**
     * The token never appears in what [KeychainFailure] says, and what it says
     * is what a screen shows.
     *
     * Two claims in one, because they pull against each other. The message has
     * to be specific enough to be worth showing — `explain()` repeats it rather
     * than falling back to "something went wrong", which it does only for the
     * app's own exceptions that promise to carry nothing from outside — and it
     * has to carry an `OSStatus` and never the value it failed to store. This
     * is the one class on this platform that holds the token in the clear.
     */
    @Test
    fun a_keychain_failure_says_the_status_and_never_the_value() {
        val failure = KeychainFailure(errSecInteractionNotAllowedStatus)
        val shown = explain(failure)

        assertTrue(credential.token !in shown, "an OSStatus, never the value")
        assertTrue("$errSecInteractionNotAllowedStatus" in shown, "the status is what an operator greps for")
        assertTrue(
            "something went wrong" !in shown,
            "a KeychainFailure is a SecretsUnavailable, so explain() shows it rather than the fallback " +
                "— the identical Android failure already reads as a sentence",
        )
    }

    /**
     * The status `KeychainSecrets`' own KDoc names as the reachable one: a
     * background wake before the device's first unlock, which is exactly the
     * situation `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` invites.
     */
    private val errSecInteractionNotAllowedStatus = -25308

    /** Distinct per run, so a leftover item from a crashed run cannot be read as this one's. */
    private fun randomSuffix(): String = buildString {
        var seed = kotlin.random.Random.nextInt(0, Int.MAX_VALUE)
        repeat(8) {
            append(('a' + (seed % 26)))
            seed /= 26
        }
    }
}
