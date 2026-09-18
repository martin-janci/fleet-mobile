package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.model.ConvItem
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The three things the Task 2 review found were promised but not true. Each
 * test pins the corrected promise, not the old wording.
 */
private const val HUB = "https://fleet.example.com"

private fun hubClient(body: String, status: HttpStatusCode): HubClient {
    val engine = MockEngine {
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    return HubClient(HttpClient(engine), HUB, "tok-phone")
}

private fun okResult(payloadJson: String): String {
    val quoted = payloadJson
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    return """event: message
data: {"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"$quoted"}]}}

"""
}

class HubErrorIsAClosedSetTest {

    /**
     * `HubError` calls itself "every way talking to a hub can fail, as one
     * closed set the UI can branch on". That was false while the model decode
     * sat outside the try/catch: a hub whose payload did not fit the model threw
     * a raw `SerializationException` straight past every screen's `catch`.
     */
    @Test
    fun a_payload_that_does_not_fit_the_model_is_a_hub_error_not_a_raw_throw() = runTest {
        // `list_sessions` must yield a list; an object cannot be decoded as one.
        val hub = hubClient(okResult("""{"not":"a list"}"""), HttpStatusCode.OK)

        assertFailsWith<HubError> { hub.listSessions() }
    }

    @Test
    fun such_a_payload_is_reported_as_transport_which_covers_the_unintelligible() = runTest {
        val hub = hubClient(okResult("""{"not":"a list"}"""), HttpStatusCode.OK)

        assertFailsWith<HubError.Transport> { hub.listSessions() }
    }

    /** A single malformed row must not escape either. */
    @Test
    fun a_row_with_the_wrong_field_type_is_a_hub_error_too() = runTest {
        val hub = hubClient(okResult("""[{"id":"not-a-number"}]"""), HttpStatusCode.OK)

        assertFailsWith<HubError> { hub.listSessions() }
    }

    /**
     * The review's second probe. `id` is the only `SessionRow` field without a
     * default, so a row without it is the one input that raises
     * `MissingFieldException` rather than a decoding error — a different
     * exception type down the same unguarded path.
     */
    @Test
    fun a_session_row_missing_its_id_is_a_hub_error_not_a_missing_field_exception() = runTest {
        val hub = hubClient(
            okResult("""[{"tmux_name":"api","host_alias":"trn"}]"""),
            HttpStatusCode.OK,
        )

        assertFailsWith<HubError.Transport> { hub.listSessions() }
    }

    /**
     * The review's first probe, which now takes the *other* branch. An unknown
     * `ConvItem` kind used to throw `JsonDecodingException` straight past every
     * screen's `catch`. With the polymorphic fallback it no longer throws at
     * all — the conversation survives rather than merely failing politely.
     */
    @Test
    fun an_unknown_conversation_item_kind_survives_a_real_call() = runTest {
        val hub = hubClient(
            okResult("""{"turns":[{"items":[{"kind":"image","url":"http://x/a.png"}]}]}"""),
            HttpStatusCode.OK,
        )

        val conversation = hub.conversation(sessionId = 42)

        // Review NIT N10: counting the items would have passed on an item that
        // survived without being usable. What "survives" has to mean is that the
        // screen can draw it and the kind is still recoverable for a bug report.
        val item = conversation.turns.single().items.single()
        assertIs<ConvItem.Unsupported>(item)
        assertEquals("image", item.kind)
        assertTrue(item.label.isNotBlank(), "an unsupported item still has to render as something")
        assertTrue("image" in item.label, "and should say what it could not show: ${item.label}")
    }
}

class ForbiddenExplainsItselfTest {

    /**
     * fleet's `authorize` layer returns a bare `StatusCode`, which axum renders
     * with an **empty body**. So `Forbidden` cannot lean on the body to explain
     * itself, as its comment used to claim — it has to say what a 403 means and
     * which address was refused.
     */
    @Test
    fun a_403_explains_itself_when_the_hub_sends_no_body() = runTest {
        val hub = hubClient("", HttpStatusCode.Forbidden)

        val failure = assertFailsWith<HubError.Forbidden> { hub.listSessions() }

        assertEquals("", failure.body)
        val message = failure.message.orEmpty()
        assertTrue(message.contains(HUB), "the refused address should be named: $message")
        assertTrue(message.length > 40, "an empty body must not mean an empty explanation")
        assertTrue(!message.trimEnd().endsWith(":"), "dangling colon with nothing after it: $message")
    }

    /** When something in front of the hub *does* send a body, it is still shown. */
    @Test
    fun a_403_with_a_body_still_shows_it() = runTest {
        val hub = hubClient("blocked by the proxy", HttpStatusCode.Forbidden)

        val failure = assertFailsWith<HubError.Forbidden> { hub.listSessions() }

        assertEquals("blocked by the proxy", failure.body)
        assertTrue(failure.message.orEmpty().contains("blocked by the proxy"))
    }
}

// `SseFramingIsRequestScopedTest` lived here. It pinned the two limits of
// `extractJsonRpcPayload` — first frame only, event name discarded — so that
// Task 4 would write its own reader instead of inheriting a wrong assumption.
// Task 4 wrote one, and on the controller's ruling `call()` now uses it too, so
// the old function is gone and with it the behaviour these tests pinned.
// `JsonRpcFramingTest` is the replacement, and it asserts more: every frame, its
// name, and the first frame that actually *carries* a result or an error.
