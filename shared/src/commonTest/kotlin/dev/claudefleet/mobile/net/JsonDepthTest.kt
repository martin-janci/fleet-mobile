package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.store.decodeCredentials
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HUB = "https://fleet.example.com"
private const val TOKEN = "clt_5f3a9c1e7b2d4a86"

/** `[[[[…]]]]`, [depth] levels of it. */
private fun nested(depth: Int): String = "[".repeat(depth) + "]".repeat(depth)

/**
 * A document too deeply nested to parse.
 *
 * The size ceilings elsewhere stop the app reading *too much*; this stops it
 * reading a shape it cannot survive. `kotlinx.serialization` parses by recursive
 * descent, so nesting depth is stack depth — and what a blown stack produces is
 * a `StackOverflowError`, which is an `Error` and not an `Exception`. Every
 * parse site in this app guards with `catch (e: Exception)`. Measured on the
 * JVM before this check existed: a thousand levels parse fine, ten thousand
 * throw, and the throw walks straight past `parseObject`, `payloadOf`,
 * `frameToEvent` and `decodeCredentials` alike.
 *
 * Those catches were not wrong, they were aimed at one half of `Throwable`. The
 * fix is not to widen them: catching a `StackOverflowError` is unreliable
 * wherever it is possible at all, since the stack that would run the handler is
 * the one that just ran out — and on Kotlin/Native it is not a catchable
 * `Error` in the first place. iOS has less stack to spend, too, because Ktor and
 * coroutines do this work on secondary threads.
 *
 * So nothing here asserts what the parser does with a deep document. The
 * assertion is that **the parser is never given one**, which is a claim that
 * means the same thing on both platforms.
 */
class JsonDepthTest {

    // ---- the counter itself ----

    @Test
    fun ordinary_shapes_are_well_inside_the_ceiling() {
        // What the hub actually sends, at its deepest: a conversation.
        val conversation = """{"turns":[{"items":[{"kind":"text","text":"hi"}]}],"truncated":false}"""
        assertTrue(nestsWithin(conversation, MAX_JSON_DEPTH))
        // And with a great deal of room to spare.
        assertTrue(nestsWithin(conversation, 8), "a real payload is single digits deep")
    }

    @Test
    fun the_ceiling_is_where_it_says_it_is() {
        assertTrue(nestsWithin(nested(MAX_JSON_DEPTH), MAX_JSON_DEPTH), "exactly at the limit is allowed")
        assertTrue(!nestsWithin(nested(MAX_JSON_DEPTH + 1), MAX_JSON_DEPTH), "one past it is not")
    }

    @Test
    fun depth_is_the_deepest_point_not_the_bracket_count() {
        // Sixty-four pairs side by side never nest more than one deep.
        val wide = "[" + (1..64).joinToString(",") { "[]" } + "]"
        assertTrue(nestsWithin(wide, 3), "a wide document is not a deep one")
    }

    /**
     * Brackets inside a string are text, not structure.
     *
     * This is the one thing a naive bracket count gets wrong, and it gets it
     * wrong in the direction that breaks working hubs: a `current_activity`
     * reading `editing [[[nested]]] arrays` or a prompt someone pasted would
     * refuse an otherwise perfectly ordinary reply. Escapes count too — a
     * `\"` does not end the string it is in.
     */
    @Test
    fun brackets_inside_strings_are_not_structure() {
        val bracketsInText = """{"current_activity":"${"[".repeat(500)}"}"""
        assertTrue(nestsWithin(bracketsInText, MAX_JSON_DEPTH), "those brackets are somebody's prose")

        val escapedQuote = """{"text":"he said \"${"[".repeat(500)}\" and left"}"""
        assertTrue(nestsWithin(escapedQuote, MAX_JSON_DEPTH), "an escaped quote does not end the string")

        val escapedBackslash = """{"text":"ends with a backslash \\","a":${nested(100)}}"""
        assertTrue(
            !nestsWithin(escapedBackslash, MAX_JSON_DEPTH),
            "a trailing escaped backslash must not swallow the rest of the document",
        )
    }

    /** The counter never recurses, so the thing it guards cannot happen inside it. */
    @Test
    fun the_counter_itself_survives_what_the_parser_would_not() {
        // The depth that produced a StackOverflowError from the parser on the
        // JVM, an order of magnitude over. If this test can run at all, the
        // check is not recursive.
        assertTrue(!nestsWithin(nested(200_000), MAX_JSON_DEPTH))
    }

    // ---- every door the wire comes in by ----

    @Test
    fun a_deeply_nested_tool_reply_is_refused_before_it_is_parsed() = runTest {
        val payload = nested(MAX_JSON_DEPTH + 50)
        val framed = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":" +
            "[{\"type\":\"text\",\"text\":${quote(payload)}}]}}\n\n"

        val refusal = assertFailsWith<HubError.TooLarge> { clientReturning(framed).listSessions() }

        assertEquals(HUB_PAYLOAD, refusal.what, "the inner payload is the document that overran")
        assertEquals(MAX_JSON_DEPTH, refusal.limit)
    }

    @Test
    fun a_deeply_nested_envelope_is_refused_before_it_is_parsed() = runTest {
        val envelope = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"deep\":${nested(MAX_JSON_DEPTH + 50)}}}"

        val refusal = assertFailsWith<HubError.TooLarge> { clientReturning(envelope).listSessions() }

        assertEquals(WIRE_JSON, refusal.what)
    }

    @Test
    fun a_deeply_nested_pair_reply_is_refused_before_it_is_parsed() = runTest {
        val body = "{\"token\":\"t\",\"name\":\"phone\",\"mode\":\"full\",\"hub\":\"$HUB\",\"x\":${nested(MAX_JSON_DEPTH + 50)}}"

        val refusal = assertFailsWith<HubError.TooLarge> { clientReturning(body).pair("ABCD1234") }

        assertEquals(PAIR_REPLY, refusal.what)
    }

    /**
     * On the live stream it becomes an ordinary dropped connection, the same as
     * an oversized frame: back off, reconnect, and let `ready` refetch.
     */
    @Test
    fun a_deeply_nested_event_frame_fails_the_stream() = runTest {
        val body = "event: session:updated\ndata: ${nested(MAX_JSON_DEPTH + 50)}\n\n"
        val stream = HubEventStream(
            HttpClient(
                MockEngine {
                    respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                },
            ),
            HUB,
            TOKEN,
        )

        val refusal = assertFailsWith<HubError.TooLarge> { stream.connect().collect { } }
        assertEquals(SSE_FRAME, refusal.what)
    }

    /**
     * The stored credential degrades to "not paired" rather than refusing to
     * start, which is the rule the whole store already follows: an unreadable
     * store is an empty one, because a crash on every cold start has no way out
     * but a reinstall.
     */
    @Test
    fun a_deeply_nested_stored_credential_reads_as_unpaired() {
        assertNull(decodeCredentials(nested(MAX_JSON_DEPTH + 50)))
    }

    /** And an ordinary credential still reads, so the guard is not simply refusing everything. */
    @Test
    fun an_ordinary_stored_credential_still_reads() {
        val stored = """{"hub":"$HUB","token":"t","name":"phone","mode":"full"}"""
        assertNotNull(decodeCredentials(stored))
    }

    /** An ordinary reply still goes through, at the depth the hub really uses. */
    @Test
    fun an_ordinary_reply_is_untouched() = runTest {
        val rows = """[{"id":7,"tmux_name":"work","host_alias":"local","tags":["a","b"]}]"""
        val framed = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":" +
            "[{\"type\":\"text\",\"text\":${quote(rows)}}]}}\n\n"

        val sessions = clientReturning(framed).listSessions()

        assertEquals(1, sessions.size)
        assertEquals("work", sessions[0].tmuxName)
    }

    private fun quote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun clientReturning(body: String): HubClient = HubClient(
        HttpClient(
            MockEngine {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            },
        ),
        HUB,
        TOKEN,
    )
}

/**
 * The scanner's own state, which the depth guard rests on entirely.
 *
 * [nestsWithin] is a hand-rolled scanner, and a scanner is only as good as the
 * flags it starts with. Mutation found that `escaped` could start `true` with
 * the whole suite green — and that is not a cosmetic difference. With `escaped`
 * true, the first character *inside the first string* is consumed as an escape;
 * for an empty string `""` that character is the closing quote, so the string
 * never ends, the entire rest of the document is read as string content, and
 * nothing after it is counted as structure.
 *
 * Measured: `{"":` followed by five hundred nested arrays reads as **depth 1**
 * under that mutation and is waved through. The guard exists to stop exactly
 * that document, and `{"…"}` — an empty string early in the payload — is
 * unremarkable JSON that a hub could send any day.
 *
 * So these pin the scanner rather than the ceiling: the thing the ceiling is
 * computed from has to survive the string cases first.
 */
class JsonScannerTest {

    private fun deep(depth: Int = MAX_JSON_DEPTH + 50) = "[".repeat(depth) + "]".repeat(depth)

    /** An empty string must not swallow the rest of the document. */
    @Test
    fun an_empty_string_does_not_blind_the_scanner() {
        assertFalse(nestsWithin("""{"":${deep()}}""", MAX_JSON_DEPTH), "the nesting is still there")
        assertFalse(nestsWithin("""{"a":"","b":${deep()}}""", MAX_JSON_DEPTH))
        assertFalse(nestsWithin("""["","","",${deep()}]""", MAX_JSON_DEPTH))
    }

    /** Nor may an escape sequence at the very start of a string. */
    @Test
    fun an_escape_at_the_start_of_a_string_does_not_blind_the_scanner() {
        assertFalse(nestsWithin("""{"\"":${deep()}}""", MAX_JSON_DEPTH), "an escaped quote opens nothing")
        assertFalse(nestsWithin("""{"\\":${deep()}}""", MAX_JSON_DEPTH), "an escaped backslash ends there")
    }

    /** And the ordinary shapes those cases are carved out of still pass. */
    @Test
    fun the_same_documents_without_the_nesting_are_fine() {
        assertTrue(nestsWithin("""{"":1}""", MAX_JSON_DEPTH))
        assertTrue(nestsWithin("""{"\"":"he said \"hi\""}""", MAX_JSON_DEPTH))
        assertTrue(nestsWithin("""{"a":"","b":[1,2,3]}""", MAX_JSON_DEPTH))
    }

    /** The guard is reached through a real reply, not only by calling it directly. */
    @Test
    fun a_reply_whose_nesting_hides_behind_an_empty_string_is_still_refused() = runTest {
        val envelope = """{"jsonrpc":"2.0","id":1,"result":{"":${deep()}}}"""
        val client = HubClient(
            HttpClient(
                MockEngine {
                    respond(envelope, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                },
            ),
            "https://fleet.example.com",
            "clt_5f3a9c1e7b2d4a86",
        )

        assertFailsWith<HubError.TooLarge> { client.listSessions() }
    }
}
