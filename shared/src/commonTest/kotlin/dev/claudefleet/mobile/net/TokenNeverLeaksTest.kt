package dev.claudefleet.mobile.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import dev.claudefleet.mobile.model.PairResult
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val HUB = "https://fleet.example.com"
private const val TOKEN = "tok-SECRET-value"

private fun hub(body: String, status: HttpStatusCode = HttpStatusCode.OK, token: String? = TOKEN): HubClient {
    val engine = MockEngine {
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    return HubClient(HttpClient(engine), HUB, token)
}

private fun okResult(payloadJson: String): String {
    val quoted = payloadJson.replace("\\", "\\\\").replace("\"", "\\\"")
    return "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1," +
        "\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"$quoted\"}]}}\n\n"
}

/** Everything a failure is allowed to say, checked against the one secret. */
private fun assertSilentAbout(secret: String, failure: Throwable) {
    assertFalse(secret in failure.message.orEmpty(), "leaked into message: ${failure.message}")
    assertFalse(secret in failure.toString(), "leaked into toString: $failure")
    assertFalse(
        generateSequence(failure.cause) { it.cause }.any { secret in it.message.orEmpty() || secret in it.toString() },
        "leaked through the cause chain: ${failure.cause}",
    )
}

/**
 * Task 3 review B1 and S1. Both are the same shape: text that came off the wire,
 * or out of a parser that quotes the wire, reaching a string a person can see.
 *
 * A `HubError` is what a screen shows and what a crash reporter records. The
 * token is issued once, in exactly one response, and is never recoverable
 * afterwards — so a failure that repeats it hands the fleet to whoever reads the
 * screenshot.
 */
class TokenNeverLeaksTest {

    /**
     * B1, the reported case: kotlinx.serialization appends the *input document*
     * to its own exception message, and the hub writes `token` as the first
     * field of the pair reply. A body cut short — a dropped connection, a proxy
     * buffer limit, a captive portal — is enough.
     */
    @Test
    fun a_truncated_pair_reply_never_repeats_the_token() = runTest {
        val truncated = """{"token":"$TOKEN","name":"phone","""

        val failure = assertFailsWith<HubError.Transport> { hub(truncated, token = null).pair("ABCD1234") }

        assertSilentAbout(TOKEN, failure)
        assertTrue(failure.message.orEmpty().isNotBlank(), "silence is not the same as saying nothing")
    }

    /** The same hole, reached through a trailing comma rather than a cut. */
    @Test
    fun a_malformed_pair_reply_never_repeats_the_token_either() = runTest {
        val malformed = """{"token":"$TOKEN","name":"phone",}"""

        assertSilentAbout(TOKEN, assertFailsWith<HubError.Transport> { hub(malformed, token = null).pair("X") })
    }

    /** Not confined to pairing: every tool payload goes down the same path. */
    @Test
    fun a_tool_payload_that_fails_to_decode_is_not_quoted_back() = runTest {
        val body = okResult("""[{"id":"not-a-number","last_prompt":"$TOKEN"}]""")

        assertSilentAbout(TOKEN, assertFailsWith<HubError.Transport> { hub(body).listSessions() })
    }

    /** A failure still has to be diagnosable — the type survives, the text does not. */
    @Test
    fun a_transport_failure_still_names_what_kind_of_failure_it_was() = runTest {
        val failure = assertFailsWith<HubError.Transport> { hub("""{"token":"$TOKEN",""", token = null).pair("X") }

        assertTrue(failure.kind.isNotBlank())
        assertContains(failure.message.orEmpty(), failure.kind)
    }

    /**
     * S1. fleet's own 401 is bare, but the design puts Caddy in front, and
     * oauth2-proxy, nginx `auth_request` and most WAFs render an error page that
     * quotes the offending header.
     */
    @Test
    fun a_401_body_that_echoes_the_authorization_header_never_reaches_the_error() = runTest {
        val proxyPage = "401: Bearer $TOKEN rejected by the identity provider"

        val failure = assertFailsWith<HubError.Unauthorized> {
            hub(proxyPage, HttpStatusCode.Unauthorized).listSessions()
        }

        assertSilentAbout(TOKEN, failure)
    }

    /** Same for the 502 page, which `Http` does have to show something of. */
    @Test
    fun an_http_error_body_has_the_token_scrubbed_out_of_it() = runTest {
        val proxyPage = "<html>upstream rejected Authorization: Bearer $TOKEN</html>"

        val failure = assertFailsWith<HubError.Http> {
            hub(proxyPage, HttpStatusCode.BadGateway).listSessions()
        }

        assertSilentAbout(TOKEN, failure)
        // The rest of the page is exactly what an operator needs, so it stays.
        assertContains(failure.body, "upstream rejected")
        assertContains(failure.body, "redacted")
    }

    /**
     * A body too large to read never becomes a message at all.
     *
     * [HubError.TooLarge] is the one variant that carries no wire text by
     * construction — its two fields are an app-authored phrase and an `Int` —
     * and this is the stronger half of why: the ceiling is enforced while the
     * body is still being *read*, before the status is even looked at, so an
     * oversized error page is not scrubbed and capped, it is never assembled.
     * `redacted()` removes the one secret it knows about and says so in its own
     * comment ("read the name as token-scrubbed, not safe"); a page that is
     * simply not read cannot leak anything, including secrets nobody thought of.
     *
     * The token is planted right at the front, where a straddling cut cannot be
     * the reason it fails to appear.
     */
    @Test
    fun a_body_past_the_ceiling_is_not_read_into_an_error_at_all() = runTest {
        val enormous = TOKEN + "x".repeat(MAX_RESPONSE_BYTES + 4096)

        val failure = assertFailsWith<HubError.TooLarge> {
            hub(enormous, HttpStatusCode.BadGateway).listSessions()
        }

        assertSilentAbout(TOKEN, failure)
        assertEquals(HUB_REPLY, failure.what)
        assertFalse("x" in failure.message.orEmpty(), "no part of the body may survive into the message")
    }

    /** A proxy that answers with a megabyte of HTML must not become the message. */
    @Test
    fun a_very_long_error_body_is_capped() = runTest {
        val huge = "x".repeat(50_000)

        val failure = assertFailsWith<HubError.Http> { hub(huge, HttpStatusCode.BadGateway).listSessions() }

        assertTrue(failure.body.length < 2_000, "body was ${failure.body.length} chars")
    }

    /**
     * The Task 4 review's BLOCKER. `redacted()` capped the body *before* it
     * scrubbed, so a token that straddled the 1 000-character cut was sliced in
     * half, the `replace` stopped matching what was left, and the surviving
     * prefix went out verbatim. A 64-character hex token cut at its last
     * character leaves a brute-force space of sixteen.
     *
     * And it is not only a log: `FleetRepository` puts an `Http` failure's text
     * into the reconnect banner, which a person reads on the screen.
     */
    @Test
    fun a_token_that_straddles_the_cap_is_scrubbed_before_the_body_is_cut() = runTest {
        val secret = "0123456789abcdef".repeat(4) // 64 chars, like a hub token
        // Ends at 1 010: the first 54 characters sit inside the cap, the rest
        // outside, which is exactly the case the old order got wrong.
        val page = "x".repeat(946) + "Bearer $secret" + "</html>"

        val failure = assertFailsWith<HubError.Http> {
            HubClient(
                HttpClient(MockEngine { respond(page, HttpStatusCode.BadGateway) }),
                HUB,
                secret,
            ).listSessions()
        }

        assertSilentAbout(secret, failure)
        // The half that used to survive the cut, on its own:
        assertFalse(secret.take(54) in failure.body, "a prefix of the token survived the cap")
        assertContains(failure.body, "redacted")
    }

    /**
     * The invariant this file's header states — "any body that came off the wire
     * goes through [redacted]" — did not hold for [HubError.Tool], whose message
     * comes straight out of the hub's `structuredContent`. A hub, or something
     * in front of it, that quotes the request back in a tool's message put the
     * token on the screen through the one path nothing was checking.
     */
    @Test
    fun a_tool_refusal_never_repeats_the_token_either() = runTest {
        val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"isError\":true," +
            "\"structuredContent\":{\"code\":\"E_FORBIDDEN\"," +
            "\"message\":\"rejected Authorization: Bearer $TOKEN\"},\"content\":[]}}\n\n"

        val failure = assertFailsWith<HubError.Tool> { hub(body).listSessions() }

        assertSilentAbout(TOKEN, failure)
        assertContains(failure.message, "rejected")
        assertEquals("E_FORBIDDEN", failure.code)
    }

    /** The same for a JSON-RPC `error`, which is the other way a tool says no. */
    @Test
    fun a_json_rpc_error_never_repeats_the_token() = runTest {
        val body = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32602," +
            "\"message\":\"bad arguments for Bearer $TOKEN\"}}\n\n"

        val failure = assertFailsWith<HubError.Tool> { hub(body).listSessions() }

        assertSilentAbout(TOKEN, failure)
    }

    /** The hub's own 404 for a spent pairing code still says why. */
    @Test
    fun a_body_with_no_secret_in_it_is_passed_through_unchanged() = runTest {
        val failure = assertFailsWith<HubError.Http> {
            hub("""{"error":"invalid code"}""", HttpStatusCode.NotFound, token = null).pair("ABCD1234")
        }

        assertContains(failure.body, "invalid code")
        assertFalse("redacted" in failure.body)
    }
}

/**
 * Task 5 review, N3 — `redacted()` is defeated by case-folding, and a hub token
 * is lowercase hex.
 *
 * `mcp/mod.rs` mints 32 bytes rendered as lowercase hex. A proxy or an error
 * page that upper-cases what it echoes — and plenty do, in a header dump or a
 * `<CODE>` block — hands back the same 64 characters in a form `replace` no
 * longer matches, and the whole token goes out verbatim. The reviewer measured
 * a leaked run of 64 of 64.
 *
 * This is the one re-encoding worth defending against, and the reason is that it
 * is not a re-encoding at all: an upper-cased hex token *is* the token. Base64,
 * URL-encoding and markup-splitting genuinely cannot be caught by a `replace`,
 * and `redacted`'s KDoc is right to say so rather than pretend.
 */
class RedactionIsCaseInsensitiveTest {

    private val hexToken = "a3f9c1d2e4b5061728394a5b6c7d8e9f0123456789abcdef0123456789abcdef"

    @Test
    fun an_upper_cased_echo_of_a_hex_token_is_still_scrubbed() {
        val body = "gateway error: Authorization: Bearer ${hexToken.uppercase()}"

        val safe = redacted(body, hexToken)

        assertFalse(hexToken.uppercase() in safe, "the token came back upper-cased: $safe")
        assertFalse(hexToken in safe, safe)
        assertTrue("<redacted>" in safe, safe)
    }

    @Test
    fun a_mixed_case_echo_is_scrubbed_too() {
        val mixed = hexToken.mapIndexed { i, c -> if (i % 2 == 0) c.uppercaseChar() else c }
            .joinToString("")

        val safe = redacted("proxy said: $mixed", hexToken)

        assertFalse(mixed in safe, safe)
    }

    /** And the ordinary exact-match case still works. */
    @Test
    fun the_exact_token_is_still_scrubbed() {
        val safe = redacted("Bearer $hexToken", hexToken)
        assertFalse(hexToken in safe, safe)
    }

    /**
     * Case-insensitivity must not start eating text that merely resembles the
     * token. The scrub replaces the token, not everything near it.
     */
    @Test
    fun text_that_is_not_the_token_survives() {
        val safe = redacted("upstream timed out after 30s", hexToken)
        assertEquals("upstream timed out after 30s", safe)
    }

    /**
     * `PairResult` does not print its token.
     *
     * It was the last `data class` in the app whose first property is the
     * plaintext token, and a generated `toString()` prints all of them — the
     * exact shape `Credentials` was deliberately written around. One string
     * interpolation (`"pair failed: $result"`) would have been the third leak.
     */
    @Test
    fun a_pair_result_never_prints_its_token() {
        val result = PairResult(
            token = hexToken,
            name = "phone",
            mode = "full",
            hub = "https://fleet.example.com",
        )

        val rendered = result.toString()

        assertFalse(hexToken in rendered, "the token reached PairResult.toString(): $rendered")
        assertTrue("<redacted>" in rendered, rendered)
        // The fields that are not secret stay legible: this is a redaction, not
        // an opaque type nobody can debug with.
        assertTrue("phone" in rendered && "https://fleet.example.com" in rendered, rendered)
    }

    /**
     * More than one secret can be in scope, and all of them are scrubbed.
     *
     * The pairing code is the second: on the `/pair` path there is no token yet,
     * and the code is the credential.
     */
    @Test
    fun every_secret_in_scope_is_scrubbed_not_just_the_first() {
        val safe = redacted("proxy echoed $hexToken and ABCD1234", hexToken, "ABCD1234")

        assertFalse(hexToken in safe, safe)
        assertFalse("ABCD1234" in safe, safe)
    }

    /** A null or blank secret is skipped rather than scrubbing everything. */
    @Test
    fun a_null_secret_among_others_does_not_break_the_scrub() {
        val safe = redacted("proxy echoed ABCD1234", null, "ABCD1234", "")

        assertFalse("ABCD1234" in safe, safe)
        assertTrue("<redacted>" in safe, safe)
    }
}
