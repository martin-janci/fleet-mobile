package dev.claudefleet.mobile.net

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

private const val HUB = "https://fleet.example.com"

private fun hub(body: String): HubClient {
    val engine = MockEngine {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
    }
    return HubClient(HttpClient(engine), HUB, "tok-phone")
}

private fun frame(name: String, data: String) = "event: $name\ndata: $data\n\n"

private fun result(payloadJson: String): String {
    val quoted = payloadJson.replace("\\", "\\\\").replace("\"", "\\\"")
    return """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"$quoted"}]}}"""
}

/**
 * The controller's ruling, which supersedes `extractJsonRpcPayload`: the reader
 * yields `(event, data)` pairs and `call()` takes the first frame that *carries*
 * a result or an error, not merely the first frame.
 *
 * The old single-frame reader was only ever safe because rmcp's stateless mode
 * happens to send exactly one message per POST — a property of someone else's
 * code that nobody is holding still for us.
 */
class JsonRpcFramingTest {

    /** A progress notification ahead of the response used to become the reply. */
    @Test
    fun the_reply_is_the_first_frame_that_carries_one_not_the_first_frame() = runTest {
        val body = frame("message", """{"jsonrpc":"2.0","method":"notifications/progress","params":{"progress":1}}""") +
            frame("message", result("[]"))

        assertEquals(emptyList(), hub(body).listSessions())
    }

    @Test
    fun a_jsonrpc_error_behind_a_notification_is_still_found() = runTest {
        val body = frame("message", """{"jsonrpc":"2.0","method":"notifications/message","params":{}}""") +
            frame("message", """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"no such tool"}}""")

        val failure = assertFailsWith<HubError.Tool> { hub(body).listSessions() }

        assertEquals("no such tool", failure.message)
    }

    /** Several notifications, and still no reply, is a hub that did not answer. */
    @Test
    fun a_body_of_nothing_but_notifications_is_a_transport_failure() = runTest {
        val body = frame("message", """{"jsonrpc":"2.0","method":"notifications/progress"}""") +
            frame("message", """{"jsonrpc":"2.0","method":"notifications/progress"}""")

        assertFailsWith<HubError.Transport> { hub(body).listSessions() }
    }

    /**
     * A complete response body may arrive without its terminating blank line.
     * Here — unlike on the live event stream, where a partial frame means the
     * connection was cut — the body is all there is, so the last frame counts.
     */
    @Test
    fun a_body_that_ends_without_its_blank_line_still_yields_its_frame() = runTest {
        val body = "event: message\ndata: ${result("[]")}"

        assertEquals(emptyList(), hub(body).listSessions())
    }

    @Test
    fun keep_alive_comments_around_the_reply_are_skipped() = runTest {
        val body = ":\n\n: keep-alive\n\n" + frame("message", result("[]")) + ":\n\n"

        assertEquals(emptyList(), hub(body).listSessions())
    }

    /** The framing is shared with the event stream, and keeps the name. */
    @Test
    fun the_request_scoped_reader_keeps_every_frame_and_its_name() {
        val frames = sseFrames(frame("message", """{"a":1}""") + ":\n\n" + frame("other", """{"a":2}"""))

        assertEquals(listOf("message", "other"), frames.map { it.event })
        assertEquals(listOf("""{"a":1}""", """{"a":2}"""), frames.map { it.data })
    }
}
