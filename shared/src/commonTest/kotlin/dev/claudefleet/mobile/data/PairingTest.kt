package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.store.Credentials
import dev.claudefleet.mobile.store.Secrets
import dev.claudefleet.mobile.store.SecretsUnavailable
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import dev.claudefleet.mobile.ui.explain
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

private const val BASE = "https://fleet.example.com"
private const val CODE = "ABCD1234"

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

/**
 * Stands in for the platform store. Deliberately *not* a mock: it holds the
 * one credential the real ones hold, so "clear() leaves nothing readable" is
 * asserted against the same shape the Keychain and EncryptedSharedPreferences
 * present.
 */
private class FakeSecrets(private var stored: Credentials? = null) : Secrets {
    var writes = 0
        private set
    var clears = 0
        private set

    override suspend fun read(): Credentials? = stored

    override suspend fun write(credentials: Credentials) {
        stored = credentials
        writes++
    }

    override suspend fun clear() {
        stored = null
        clears++
    }
}

private class Calls {
    val requests = mutableListOf<HttpRequestData>()
    fun path(i: Int) = requests[i].url.encodedPath
    fun host(i: Int) = requests[i].url.host
    fun bodyText(i: Int) = (requests[i].body as TextContent).text
    fun auth(i: Int) = requests[i].headers[HttpHeaders.Authorization]
}

private fun session(
    secrets: Secrets = FakeSecrets(),
    calls: Calls = Calls(),
    handler: (HttpRequestData) -> Pair<String, HttpStatusCode>,
): Triple<AppSession, Calls, Secrets> {
    val engine = MockEngine { request ->
        calls.requests += request
        val (body, status) = handler(request)
        respond(body, status, jsonHeaders)
    }
    return Triple(AppSession(secrets, HttpClient(engine)), calls, secrets)
}

/** What `POST /pair` answers on success. */
private fun pairOk(hub: String = BASE, name: String = "phone", mode: String = "full") =
    """{"token":"tok-secret-value","name":"$name","mode":"$mode","hub":"$hub"}"""

// ---------------------------------------------------------------------------
// Reading a scanned code
// ---------------------------------------------------------------------------

class PairTargetTest {

    /** The shape `pair_url` builds: `<base>/pair#<code>`, code in the fragment. */
    @Test
    fun a_scanned_pair_url_yields_the_code_and_the_base() {
        val target = PairTarget.parse("https://hub.example.com/pair#ABCD1234")
        assertEquals(PairTarget("https://hub.example.com", "ABCD1234"), target)
    }

    @Test
    fun a_pair_url_keeps_a_port_and_a_path_prefix() {
        assertEquals(
            PairTarget("https://example.com:8899/fleet", "ABCD1234"),
            PairTarget.parse("https://example.com:8899/fleet/pair#ABCD1234"),
        )
    }

    @Test
    fun a_trailing_slash_after_pair_is_tolerated() {
        assertEquals(
            PairTarget("http://10.0.0.4:8899", "ABCD1234"),
            PairTarget.parse("http://10.0.0.4:8899/pair/#ABCD1234"),
        )
    }

    /**
     * Task 3 review S5. `HubBase::public` refuses this shape on the hub side —
     * "credentials (user@) are not allowed" (`service/hub.rs`) — because the
     * value ends up in hook blocks, logs and `serve` output. The phone must
     * refuse it for the same reason and one more: `Credentials.hub` is printed
     * **unredacted** by `Credentials.toString()`, so a crafted QR would route
     * the app through an attacker's host *and* put `user:pw@` in every log line
     * that prints the auth state.
     */
    @Test
    fun a_pair_url_carrying_userinfo_is_refused() {
        assertNull(PairTarget.parse("https://user:pw@hub.example.com/pair#ABCD1234"))
        assertNull(PairTarget.parse("https://user@hub.example.com/pair#ABCD1234"))
        assertNull(PairTarget.parse("https://user:pw@hub.example.com:8899/fleet/pair#ABCD1234"))
    }

    /** An `@` after the authority is a path, not userinfo, and is nobody's business. */
    @Test
    fun an_at_sign_further_along_the_url_is_not_userinfo() {
        assertEquals(
            PairTarget("https://hub.example.com/a@b", "ABCD1234"),
            PairTarget.parse("https://hub.example.com/a@b/pair#ABCD1234"),
        )
    }

    /** Manual entry: the code alone, with the base typed into its own field. */
    @Test
    fun a_bare_code_parses_with_no_base() {
        assertEquals(PairTarget(null, "ABCD1234"), PairTarget.parse("ABCD1234"))
    }

    @Test
    fun a_typed_code_is_folded_to_the_hubs_alphabet() {
        // Lower case, and the separators Crockford allows for readability.
        assertEquals(PairTarget(null, "ABCD1234"), PairTarget.parse("abcd-1234"))
        assertEquals(PairTarget(null, "ABCD1234"), PairTarget.parse(" abcd 1234 "))
    }

    /**
     * Crockford's whole point: the hub never mints `I`, `L`, `O` or `U`, so a
     * reader who saw a `0` as an `O` can be given back the digit without any
     * risk of turning one valid code into another.
     */
    @Test
    fun crockford_confusables_are_folded_to_their_digits() {
        assertEquals(PairTarget(null, "01234567"), PairTarget.parse("O1234567"))
        assertEquals(PairTarget(null, "11234567"), PairTarget.parse("I1234567"))
        assertEquals(PairTarget(null, "11234567"), PairTarget.parse("l1234567"))
    }

    @Test
    fun a_code_outside_the_alphabet_is_rejected() {
        // `U` is excluded from the alphabet and has no confusable to fold to.
        assertNull(PairTarget.parse("UUUUUUUU"))
        assertNull(PairTarget.parse("ABCD123"))      // seven
        assertNull(PairTarget.parse("ABCD12345"))    // nine
        assertNull(PairTarget.parse(""))
    }

    @Test
    fun a_url_that_is_not_a_fleet_pair_url_is_rejected() {
        assertNull(PairTarget.parse("https://hub.example.com/login#ABCD1234"))
        assertNull(PairTarget.parse("https://hub.example.com/pair#nope"))
        assertNull(PairTarget.parse("ftp://hub.example.com/pair#ABCD1234"))
        assertNull(PairTarget.parse("hub.example.com/pair#ABCD1234"))
        assertNull(PairTarget.parse("https:///pair#ABCD1234"))
    }
}

// ---------------------------------------------------------------------------
// Pairing, and what happens to the credential afterwards
// ---------------------------------------------------------------------------

class AppSessionTest {

    @Test
    fun pairing_stores_the_credentials() = runTest {
        val (app, calls, secrets) = session { pairOk() to HttpStatusCode.OK }

        val credentials = app.pair("$BASE/pair#$CODE")

        assertEquals(BASE, credentials.hub)
        assertEquals("tok-secret-value", credentials.token)
        assertEquals("phone", credentials.name)
        assertEquals("full", credentials.mode)
        assertEquals(credentials, secrets.read())
        assertIs<AuthState.Paired>(app.state.value)
        assertEquals("/pair", calls.path(0))
    }

    /** `/pair` is the one unauthenticated route: there is no token to send yet. */
    @Test
    fun the_pairing_request_carries_the_code_and_no_bearer_token() = runTest {
        val (app, calls, _) = session { pairOk() to HttpStatusCode.OK }

        app.pair("$BASE/pair#$CODE")

        assertEquals("""{"code":"$CODE"}""", calls.bodyText(0))
        assertNull(calls.auth(0))
    }

    /**
     * The hub echoes its own idea of its base URL and that is what to keep: the
     * name scanned off a QR may be one of several that reach it.
     */
    @Test
    fun the_hubs_own_base_url_is_what_gets_stored() = runTest {
        val (app, _, secrets) = session {
            pairOk(hub = "https://fleet.example.com:8443") to HttpStatusCode.OK
        }

        app.pair("https://10.0.0.4:8899/pair#$CODE")

        assertEquals("https://fleet.example.com:8443", secrets.read()?.hub)
    }

    /**
     * ...except when the hub has no public URL configured, in which case it
     * echoes its own loopback base. That address means "this machine" on the
     * phone and would be unreachable, so the base the phone actually reached
     * wins.
     */
    @Test
    fun a_loopback_echo_loses_to_the_base_that_was_actually_reached() = runTest {
        val (app, _, secrets) = session { pairOk(hub = "http://127.0.0.1:8899") to HttpStatusCode.OK }

        app.pair("https://10.0.0.4:8899/pair#$CODE")

        assertEquals("https://10.0.0.4:8899", secrets.read()?.hub)
    }

    /**
     * Task 3 review S4. The hub's own `HubBase::loopback` only ever emits
     * `http://127.0.0.1:{port}`, so the shipped default was covered — but
     * `HubBase::public` (`service/hub.rs:49-104`) validates scheme, userinfo,
     * whitespace, path and port and then **accepts `0.0.0.0` and `[::]`**. An
     * operator who pastes the bind address into `hub.public_url` is the same
     * operator this rule exists to protect, and the echo wins every time, so
     * re-pairing never recovers.
     */
    @Test
    fun every_address_that_means_this_machine_loses_to_the_one_that_was_reached() = runTest {
        val meansThisMachine = listOf(
            "http://127.0.0.1:8899",
            "http://127.1:8899",
            "http://localhost:8899",
            "http://LOCALHOST:8899",
            "http://[::1]:8899",
            "http://0.0.0.0:8899",
            "http://[::]:8899",
            "http://[0:0:0:0:0:0:0:1]:8899",
            "http://[::ffff:127.0.0.1]:8899",
            // Task 4 review. The same address, written the way every tool that
            // prints an IPv6 address actually prints it: the mapped IPv4 in hex,
            // with no dot anywhere. The dotted spelling was handled and this one
            // fell through to the plain-IPv6 comparison, did not match `::1`,
            // and was declared a real address — and this failure is the one that
            // fails OPEN, storing an unreachable hub the app never recovers from.
            "http://[::ffff:7f00:1]:8899",
            "http://[::ffff:7f00:0001]:8899",
            "http://[0:0:0:0:0:ffff:7f00:1]:8899",
            // Mapped 0.0.0.0, which is a pasted bind address wearing the same hat.
            "http://[::ffff:0:0]:8899",
            // The other two spellings the review named, plus the two neighbours
            // of each. All are what `inet_aton` accepts and what
            // `InetAddress.getByName` still resolves, so an HTTP client reaches
            // the phone through every one of them.
            "http://2130706433:8899", // 0x7F000001, the whole address as one decimal
            "http://2130706432:8899", // 0x7F000000 — 127.0.0.0, the block's first
            "http://0177.0.0.1:8899", // octal first octet
            "http://0x7f.0.0.1:8899", // hex first octet
            "http://0x7f000001:8899", // the whole address in hex
            "http://127.0.1:8899", // three parts
            "http://0:8899", // 0.0.0.0 as one decimal
        )

        for (echo in meansThisMachine) {
            val (app, _, secrets) = session { pairOk(hub = echo) to HttpStatusCode.OK }
            app.pair("https://10.0.0.4:8899/pair#$CODE")
            assertEquals("https://10.0.0.4:8899", secrets.read()?.hub, "$echo should have lost")
        }
    }

    /** A real address still wins, or the rule would be "always ignore the hub". */
    @Test
    fun an_address_that_does_not_mean_this_machine_still_wins() = runTest {
        // `::ffff:8f00:1` is 143.0.0.1 — a mapped address that is NOT loopback,
        // so the new rule must not swallow the whole mapped range.
        for (echo in listOf(
            "https://fleet.example.com",
            "http://10.0.0.7:8899",
            // Neighbours of the numeric forms above, so the parser cannot pass
            // by saying yes to anything made of digits. 2130706431 is
            // 0x7EFFFFFF — 126.255.255.255, the address one below the loopback
            // block, which is the boundary worth pinning — and 167772161 and
            // 0x0a000001 are both 10.0.0.1.
            // `https` on the two PUBLIC ones on purpose. The cleartext policy
            // added later refuses plain http to a public address, so leaving
            // these as http would make them lose for a transport reason and
            // this test would keep passing even if the loopback check started
            // over-matching — which is the single thing it exists to catch.
            // The addresses are unchanged; only the scheme is.
            "https://2130706431:8899",
            "http://167772161:8899",
            "http://0x0a000001:8899",
            "https://[2001:db8::1]:8899",
            "https://[::ffff:8f00:1]:8899",
        )) {
            val (app, _, secrets) = session { pairOk(hub = echo) to HttpStatusCode.OK }
            app.pair("https://10.0.0.4:8899/pair#$CODE")
            assertEquals(echo, secrets.read()?.hub, "$echo should have won")
        }
    }

    /** A spent, unknown or expired code: one answer for all three, and nothing stored. */
    @Test
    fun a_refused_code_stores_nothing() = runTest {
        val (app, _, secrets) = session {
            """{"error":"invalid code"}""" to HttpStatusCode.NotFound
        }
        // The real flow: the Pair screen is up precisely because restore() found
        // nothing, so a refusal has to leave that state untouched.
        app.restore()

        val failure = assertFailsWith<HubError.Http> { app.pair("$BASE/pair#$CODE") }

        assertEquals(404, failure.status)
        assertNull(secrets.read())
        assertIs<AuthState.Unpaired>(app.state.value)
    }

    @Test
    fun the_pairing_rate_limit_stores_nothing_either() = runTest {
        val (app, _, secrets) = session {
            """{"error":"too many attempts"}""" to HttpStatusCode.TooManyRequests
        }

        val failure = assertFailsWith<HubError.Http> { app.pair("$BASE/pair#$CODE") }

        assertEquals(429, failure.status)
        assertNull(secrets.read())
    }

    @Test
    fun something_that_is_not_a_pairing_code_never_reaches_the_hub() = runTest {
        val (app, calls, secrets) = session { pairOk() to HttpStatusCode.OK }

        assertFailsWith<NotAPairingCode> { app.pair("https://example.com/login") }

        assertTrue(calls.requests.isEmpty(), "nothing should have been sent")
        assertNull(secrets.read())
    }

    @Test
    fun a_bare_code_needs_a_base_to_pair_against() = runTest {
        val (app, calls, _) = session { pairOk() to HttpStatusCode.OK }

        assertFailsWith<NotAPairingCode> { app.pair(CODE) }
        assertTrue(calls.requests.isEmpty())

        app.pair(CODE, base = BASE)
        assertEquals("fleet.example.com", calls.host(0))
    }

    /**
     * Task 4 review. The scanned URL went through `pairBase`, which refuses a
     * scheme that is not http(s), an empty authority and userinfo — and the
     * *typed* address next to a bare code went through `trim()` and nothing
     * else. That is the field an operator dictates over the phone and a person
     * pastes into, so it is the easier of the two to get something wrong into,
     * not the harder.
     *
     * Userinfo is the one that bites twice: `Credentials.hub` is the single
     * field `Credentials.toString()` prints unredacted, so `user:pw@` would be
     * both a route through someone else's host and a password in every log line
     * that prints the auth state.
     */
    @Test
    fun a_typed_hub_address_is_checked_exactly_like_a_scanned_one() = runTest {
        val refused = listOf(
            "https://someone:secret@evil.example.com",
            "ftp://fleet.example.com",
            "fleet.example.com",
            "https://",
            "   ",
        )

        for (base in refused) {
            val (app, calls, secrets) = session { pairOk() to HttpStatusCode.OK }
            assertFailsWith<NotAPairingCode>("$base should have been refused") {
                app.pair(CODE, base = base)
            }
            assertTrue(calls.requests.isEmpty(), "$base reached the network")
            assertNull(secrets.read())
        }
    }

    /** …and an ordinary typed address still works, trailing slash and all. */
    @Test
    fun a_typed_hub_address_that_is_fine_still_pairs() = runTest {
        val (app, calls, secrets) = session { pairOk(hub = "") to HttpStatusCode.OK }

        app.pair(CODE, base = "https://fleet.example.com:8899/")

        assertEquals("fleet.example.com", calls.host(0))
        assertEquals("https://fleet.example.com:8899", secrets.read()?.hub)
    }

    // ---- the credential's life after pairing ----

    @Test
    fun a_401_from_a_later_call_clears_the_credentials() = runTest {
        val secrets = FakeSecrets(Credentials(BASE, "tok-secret-value", "phone", "full"))
        val (app, _, _) = session(secrets) { "" to HttpStatusCode.Unauthorized }
        app.restore()
        assertIs<AuthState.Paired>(app.state.value)

        assertFailsWith<HubError.Unauthorized> { app.withClient { it.listSessions() } }

        assertNull(secrets.read())
        assertIs<AuthState.Unpaired>(app.state.value)
    }

    /**
     * Review S3 made `clear()` throw rather than fail quietly, which put the 401
     * rule at risk: a store that refuses to forget must not swallow the refusal
     * that made us try. The 401 is what routes the app back to Pair, and it is
     * the news the caller needs.
     */
    @Test
    fun a_store_that_refuses_to_forget_does_not_swallow_the_401() = runTest {
        val secrets = object : Secrets by FakeSecrets(Credentials(BASE, "tok-secret-value", "phone", "full")) {
            override suspend fun clear() = throw SecretsUnavailable("the store is sealed")
        }
        val (app, _, _) = session(secrets) { "" to HttpStatusCode.Unauthorized }
        app.restore()

        assertFailsWith<HubError.Unauthorized> { app.withClient { it.listSessions() } }

        // And it does not claim to have forgotten what it could not forget.
        assertIs<AuthState.Paired>(app.state.value)
    }

    /** Only a 401 means the credential is gone. A 403 or a flat tyre does not. */
    @Test
    fun any_other_failure_leaves_the_credentials_alone() = runTest {
        val stored = Credentials(BASE, "tok-secret-value", "phone", "full")
        val secrets = FakeSecrets(stored)
        val (app, _, _) = session(secrets) { """{"error":"bad host"}""" to HttpStatusCode.Forbidden }
        app.restore()

        assertFailsWith<HubError.Forbidden> { app.withClient { it.listSessions() } }

        assertEquals(stored, secrets.read())
        assertIs<AuthState.Paired>(app.state.value)
    }

    @Test
    fun clear_leaves_nothing_readable() = runTest {
        val secrets = FakeSecrets(Credentials(BASE, "tok-secret-value", "phone", "full"))
        val (app, _, _) = session(secrets) { pairOk() to HttpStatusCode.OK }
        app.restore()

        app.forget()

        assertNull(secrets.read())
        assertNull(app.credentials())
        assertIs<AuthState.Unpaired>(app.state.value)
        assertFailsWith<HubError.Unauthorized> { app.withClient { it.listSessions() } }
    }

    @Test
    fun restore_reports_what_the_store_holds() = runTest {
        val empty = session(FakeSecrets()) { "" to HttpStatusCode.OK }.first
        assertIs<AuthState.Unknown>(empty.state.value)
        assertIs<AuthState.Unpaired>(empty.restore())

        val full = session(FakeSecrets(Credentials(BASE, "t", "phone", "full"))) {
            "" to HttpStatusCode.OK
        }.first
        assertIs<AuthState.Paired>(full.restore())
    }

    @Test
    fun a_call_made_before_pairing_is_unauthorized_without_reaching_the_network() = runTest {
        val (app, calls, _) = session { "" to HttpStatusCode.OK }
        app.restore()

        assertFailsWith<HubError.Unauthorized> { app.withClient { it.listSessions() } }

        assertTrue(calls.requests.isEmpty())
    }

    // ---- a 401 explains itself on the way back to Pair ----

    /**
     * The `REVOKED` sentence used to live only on `FleetRepository`, which is
     * discarded the moment auth flips to `Unpaired` — so by the time the Pair
     * screen could ask why, nothing remembered. [AppSession] is the one place
     * both routes back to `Unpaired` (`withClient`'s own 401, and
     * `FleetRepository`'s `onRevoked`) go through, so it is the one place that
     * can carry the reason forward.
     */
    @Test
    fun a_401_leaves_an_explanation_behind() = runTest {
        val secrets = FakeSecrets(Credentials(BASE, "tok-secret-value", "phone", "full"))
        val (app, _, _) = session(secrets) { "" to HttpStatusCode.Unauthorized }
        app.restore()
        assertNull(app.unpairReason.value, "nothing to explain yet")

        assertFailsWith<HubError.Unauthorized> { app.withClient { it.listSessions() } }

        assertEquals(REVOKED_CREDENTIAL_REASON, app.unpairReason.value)
    }

    /** A first launch with nothing stored has no story to tell. */
    @Test
    fun a_fresh_install_carries_no_reason() = runTest {
        val (app, _, _) = session(FakeSecrets()) { "" to HttpStatusCode.OK }

        app.restore()

        assertNull(app.unpairReason.value)
    }

    /** Forgetting on purpose, from Settings, is not the hub's doing and must say nothing. */
    @Test
    fun a_user_initiated_forget_carries_no_reason() = runTest {
        val secrets = FakeSecrets(Credentials(BASE, "tok-secret-value", "phone", "full"))
        val (app, _, _) = session(secrets) { pairOk() to HttpStatusCode.OK }
        app.restore()

        app.forget()

        assertNull(app.unpairReason.value)
    }

    /** A leftover reason from an earlier revoke must not survive a deliberate forget. */
    @Test
    fun forget_clears_a_reason_a_401_had_left_behind() = runTest {
        val secrets = FakeSecrets(Credentials(BASE, "tok-secret-value", "phone", "full"))
        val (app, _, _) = session(secrets) { "" to HttpStatusCode.Unauthorized }
        app.restore()
        assertFailsWith<HubError.Unauthorized> { app.withClient { it.listSessions() } }
        assertNotNull(app.unpairReason.value)

        app.forget()

        assertNull(app.unpairReason.value)
    }

    @Test
    fun clearUnpairReason_can_be_called_directly() = runTest {
        val secrets = FakeSecrets(Credentials(BASE, "tok-secret-value", "phone", "full"))
        val (app, _, _) = session(secrets) { "" to HttpStatusCode.Unauthorized }
        app.restore()
        assertFailsWith<HubError.Unauthorized> { app.withClient { it.listSessions() } }
        assertNotNull(app.unpairReason.value)

        app.clearUnpairReason()

        assertNull(app.unpairReason.value)
    }

    /** A 403 or a flat tyre is not a revocation and must not manufacture a reason. */
    @Test
    fun a_non_401_failure_leaves_no_reason_either() = runTest {
        val stored = Credentials(BASE, "tok-secret-value", "phone", "full")
        val secrets = FakeSecrets(stored)
        val (app, _, _) = session(secrets) { """{"error":"bad host"}""" to HttpStatusCode.Forbidden }
        app.restore()

        assertFailsWith<HubError.Forbidden> { app.withClient { it.listSessions() } }

        assertNull(app.unpairReason.value)
    }
}

// ---------------------------------------------------------------------------
// The token is a secret: it must not leak into anything printable
// ---------------------------------------------------------------------------

class CredentialSecrecyTest {

    @Test
    fun credentials_never_print_the_token() {
        val text = Credentials(BASE, "tok-secret-value", "phone", "full").toString()

        assertTrue("tok-secret-value" !in text, "the token leaked into toString(): $text")
        assertContains(text, "phone")
    }

    /**
     * A refusal is shown to a person and may be logged or screenshotted; a
     * pairing code is a credential for the minutes it lives, so the message must
     * not repeat what it refused. (`U` is outside the hub's alphabet and has no
     * confusable to fold to, which is what makes this input a refusal.)
     */
    @Test
    fun a_refusal_never_repeats_the_code_it_refused() {
        val message = assertFailsWith<NotAPairingCode> {
            PairTarget.require("https://hub.example.com/pair#UUUUUUUU")
        }.message.orEmpty()

        assertTrue("UUUUUUUU" !in message, "the code leaked into the message: $message")
        assertTrue("hub.example.com" !in message, "the hub leaked into the message: $message")
        assertTrue(message.isNotBlank())
    }

    /**
     * [AuthState.Paired] is a `data class`, so its generated `toString()` prints
     * whatever [Credentials] prints. It is the state a screen is most likely to
     * log, so the redaction has to survive being nested.
     */
    @Test
    fun the_paired_state_never_prints_the_token_either() {
        val text = AuthState.Paired(Credentials(BASE, "tok-secret-value", "phone", "full")).toString()

        assertTrue("tok-secret-value" !in text, "the token leaked into AuthState: $text")
        assertContains(text, "redacted")
    }

    @Test
    fun an_unauthorized_failure_never_repeats_the_token() = runTest {
        val secrets = FakeSecrets(Credentials(BASE, "tok-secret-value", "phone", "full"))
        val (app, _, _) = session(secrets) { "" to HttpStatusCode.Unauthorized }
        app.restore()

        val failure = assertFailsWith<HubError.Unauthorized> { app.withClient { it.listSessions() } }

        assertTrue("tok-secret-value" !in failure.toString())
    }
}

/**
 * What happens after the code has already been spent.
 *
 * `POST /pair` calls `PairingRegistry::consume` **before** it mints anything,
 * and the hub's own comment on its failure branch is "the code is spent either
 * way — mint a new one". So every step the app takes after the hub answers
 * happens with the code already gone, and a failure there is not the same
 * failure as one before it.
 *
 * The step in question is the store write, and it is reachable on the worst day
 * for it: `AndroidSecrets.prefs` is null when the Keystore master key has gone
 * but the preferences file survived — the state a phone is in after being
 * restored from a backup, which is precisely when someone is setting the app up
 * and pairing for the first time.
 */
class SpentCodeTest {

    private class RefusingSecrets(private val why: String) : Secrets {
        override suspend fun read(): Credentials? = null
        override suspend fun write(credentials: Credentials) = throw SecretsUnavailable(why)
        override suspend fun clear() = Unit
    }

    /**
     * The message names the thing the person has to do something about.
     *
     * Reporting only the store — "the credential could not be written" — leaves
     * the obvious next move being to try the same code again, which the screen
     * invites by keeping it in the field, and which answers with a *different*
     * error that reads like a typo. Two contradictory messages and two trips to
     * the terminal for one failure.
     */
    @Test
    fun a_store_that_will_not_keep_the_credential_says_the_code_is_spent() = runTest {
        val (session, _, _) = session(RefusingSecrets("the secure store could not be opened")) {
            pairOk() to HttpStatusCode.OK
        }

        val failure = assertFailsWith<CredentialNotKept> { session.pair("$BASE/pair#ABCDEFGH") }
        val shown = explain(failure)

        assertContains(shown, "spent")
        assertContains(shown, "fleet-hub pair")
        assertTrue("the secure store could not be opened" in shown, "keep the store's own reason too")
    }

    /** And it is still a `SecretsUnavailable`, so `explain()` shows it rather than the fallback. */
    @Test
    fun the_refusal_reaches_the_screen_as_words() = runTest {
        val (session, _, _) = session(RefusingSecrets("nope")) { pairOk() to HttpStatusCode.OK }

        val failure = assertFailsWith<CredentialNotKept> { session.pair("$BASE/pair#ABCDEFGH") }

        // Not `failure is SecretsUnavailable` — that is true by declaration and
        // the compiler says so. The claim worth making is the one the type
        // exists to buy: `explain()` repeats a message only for the app's own
        // exceptions that promise to carry nothing from outside, so a type
        // outside that set would reach the screen as the bare fallback.
        assertFalse("something went wrong" in explain(failure), "must not read as an unknown failure")
        assertTrue(explain(failure) == failure.message, "shown verbatim, as SecretsUnavailable is")
    }

    /**
     * The token is not in it. `CredentialNotKept` is built from the store's own
     * app-authored sentence and from nothing else — the value it failed to save
     * is the one thing in this app that must never reach a screen or a log.
     */
    @Test
    fun the_refusal_never_repeats_the_credential_it_could_not_save() = runTest {
        val token = "tok-SECRET-value"
        val (session, _, _) = session(RefusingSecrets("disk is full")) {
            """{"token":"$token","name":"phone","mode":"full","hub":"$BASE"}""" to HttpStatusCode.OK
        }

        val failure = assertFailsWith<CredentialNotKept> { session.pair("$BASE/pair#ABCDEFGH") }

        assertFalse(token in failure.message.orEmpty(), "leaked into the message")
        assertFalse(token in failure.toString(), "leaked into toString")
        assertFalse(token in explain(failure), "leaked onto the screen")
    }

    /** The device stays unpaired: nothing was kept, so nothing may claim it was. */
    @Test
    fun the_device_is_left_unpaired_rather_than_half_paired() = runTest {
        val (session, _, _) = session(RefusingSecrets("no store")) {
            pairOk() to HttpStatusCode.OK
        }

        assertFailsWith<CredentialNotKept> { session.pair("$BASE/pair#ABCDEFGH") }

        assertTrue(session.state.value !is AuthState.Paired, "a credential that was not kept is not held")
        assertNull(session.credentials())
    }

    /** A failure *before* the hub answers is untouched: that code was never spent. */
    @Test
    fun a_refused_code_does_not_claim_anything_was_spent() = runTest {
        val (session, _, _) = session(FakeSecrets()) {
            """{"error":"no such pairing code"}""" to HttpStatusCode.NotFound
        }

        val failure = assertFailsWith<HubError> { session.pair("$BASE/pair#ABCDEFGH") }

        assertFalse("spent" in explain(failure), "nothing was issued, so nothing was spent")
    }
}

/**
 * One wrong character is enough to refuse a code.
 *
 * `cleaned.any { it !in ALPHABET }` survived as `.all { … }`, which refuses a
 * code only when **every** character is outside Crockford's alphabet. One bad
 * character — the ordinary way a dictated code goes wrong — would have been
 * accepted and posted to the hub, which is precisely the "find out from an
 * error" the app is built to avoid, and it spends a rate-limiter attempt to
 * learn what the alphabet already said.
 */
class CodeAlphabetTest {

    @Test
    fun a_single_character_outside_the_alphabet_refuses_the_whole_code() {
        for (bad in listOf("ABCDEFG!", "ABCDEF#H", "$ BCDEFGH".trim().padEnd(8, 'H'), "ABCDEFGU")) {
            assertNull(PairTarget.normalizeCode(bad), "\"$bad\" is not a code the hub can have minted")
        }
    }

    /** `U` in particular: Crockford excludes it and it folds to nothing. */
    @Test
    fun u_is_refused_rather_than_folded() {
        assertNull(PairTarget.normalizeCode("ABCDEFGU"))
        assertEquals("ABCDEFG1", PairTarget.normalizeCode("ABCDEFGI"), "I folds to 1")
        assertEquals("ABCDEFG0", PairTarget.normalizeCode("ABCDEFGO"), "O folds to 0")
    }

    /** And a wholly good code still passes, so the rule is not simply inverted. */
    @Test
    fun a_good_code_is_still_a_good_code() {
        assertEquals("ABCDEFGH", PairTarget.normalizeCode("abcdefgh"))
        assertEquals("ABCDEFGH", PairTarget.normalizeCode("ABCD-EFGH"), "separators come out")
    }
}
