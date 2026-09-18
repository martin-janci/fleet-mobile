package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.store.Credentials
import dev.claudefleet.mobile.store.Secrets
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
