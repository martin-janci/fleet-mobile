package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.store.Credentials
import dev.claudefleet.mobile.store.Secrets
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private const val REACHED = "https://10.0.0.4:8899"
private const val CODE = "ABCD1234"

private class Store(private var stored: Credentials? = null) : Secrets {
    override suspend fun read(): Credentials? = stored
    override suspend fun write(credentials: Credentials) {
        stored = credentials
    }

    override suspend fun clear() {
        stored = null
    }
}

private fun sessionEchoing(hub: String): Pair<AppSession, Store> {
    val store = Store()
    val engine = MockEngine {
        respond(
            """{"token":"tok-secret-value","name":"phone","mode":"full","hub":"$hub"}""",
            HttpStatusCode.OK,
            headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }
    return AppSession(store, HttpClient(engine)) to store
}

/**
 * Task 5 review, S3 — the **third** door into `Credentials.hub`.
 *
 * Two of the three were closed by `c7c0017`: the base carved out of a scanned
 * pair URL, and the address typed beside a dictated code, both go through
 * `hubBase`. The hub's own echoed `hub` field did not, and it is the one field
 * `Credentials.toString()` prints unredacted, and the address every later
 * request is dialled at.
 *
 * Severity is genuinely lower than the other two — fleet's `HubBase::public`
 * refuses all of this before it can be echoed, and someone who can rewrite the
 * pair response cannot mint a token anyway — but a commit whose point was "both
 * now go through one `hubBase`" leaving a third entry point open is exactly the
 * kind of gap that reads as closed.
 */
class EchoedBaseTest {

    @Test
    fun an_echo_that_would_be_refused_from_the_scanner_is_refused_from_the_hub_too() = runTest {
        val refusable = listOf(
            "https://someone:secret@evil.example.com",
            "https://user@evil.example.com",
            "ftp://evil.example.com",
            "javascript://x",
            "not-a-url-at-all",
            "https://a b",
            "https://hub.example.com?x=1",
            "https://[::1",
            // A bracketless IPv6 literal, which without `hasUsablePort`'s
            // colon count would parse as host `::1` port `8899`, be accepted
            // by `hubBase`, and then not be recognised as loopback.
            "http://::1:8899",
        )
        for (echo in refusable) {
            val (app, store) = sessionEchoing(echo)
            app.pair("$REACHED/pair#$CODE")
            assertEquals(
                REACHED,
                store.read()?.hub,
                "the hub echoed `$echo` and the app kept it",
            )
        }
    }

    /**
     * The specific harm, stated as its own test because it is the reason the
     * userinfo rule exists at all: `hub` is the one field `toString()` prints
     * in the clear, so a password in it is a password in every log line that
     * prints the auth state.
     */
    @Test
    fun a_password_in_the_echo_never_reaches_the_credentials_toString() = runTest {
        val (app, store) = sessionEchoing("https://someone:hunter2@evil.example.com")
        app.pair("$REACHED/pair#$CODE")

        val rendered = store.read().toString()
        assertFalse("hunter2" in rendered, "a password from the hub's echo reached toString: $rendered")
        assertFalse("evil.example.com" in rendered, rendered)
    }

    /** A hub that echoes a perfectly good public URL is still believed. */
    @Test
    fun a_valid_echo_still_wins_over_the_address_that_was_reached() = runTest {
        val (app, store) = sessionEchoing("https://fleet.example.com:8443")
        app.pair("$REACHED/pair#$CODE")

        assertEquals("https://fleet.example.com:8443", store.read()?.hub)
    }
}
