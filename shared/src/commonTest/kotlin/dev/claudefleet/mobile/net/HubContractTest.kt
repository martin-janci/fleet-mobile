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
import kotlin.test.assertFalse
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
            okResult("""[{"tmux_name":"api","host_alias":"pine"}]"""),
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

/**
 * [contractVerdict] against the desktop's own range
 * (`claude-fleet` `src-tauri/src/backend/contract.rs`, commit 5fa119f7,
 * `MIN_HUB_CONTRACT = 0`, `MAX_HUB_CONTRACT = 1`). One revision below the
 * minimum is a hub too old for this app; one above the maximum is this app
 * too old for the hub; everything in between, including a hub that names no
 * contract at all, is trusted.
 */
class HubContractVerdictTest {

    /**
     * The literals, not just the relationships. Every other assertion in this
     * class is written in terms of `MIN_HUB_CONTRACT`/`MAX_HUB_CONTRACT`
     * themselves, so editing either constant moves the test with it and the
     * whole class stays green against a range nobody chose. `HubContractDriftTest`
     * checks these against the desktop's own `contract.rs` where that checkout
     * is present; this is the half that runs everywhere, CI included.
     */
    @Test
    fun the_range_is_zero_to_one() {
        assertEquals(0, MIN_HUB_CONTRACT)
        assertEquals(1, MAX_HUB_CONTRACT)
    }

    @Test
    fun the_verdict_matches_the_desktops_range() {
        assertEquals(ContractVerdict.Ok, contractVerdict(null))
        assertEquals(ContractVerdict.Ok, contractVerdict(MIN_HUB_CONTRACT))
        assertEquals(ContractVerdict.Ok, contractVerdict(MAX_HUB_CONTRACT))
        assertEquals(ContractVerdict.HubTooOld(MIN_HUB_CONTRACT - 1), contractVerdict(MIN_HUB_CONTRACT - 1))
        assertEquals(ContractVerdict.AppTooOld(MAX_HUB_CONTRACT + 1), contractVerdict(MAX_HUB_CONTRACT + 1))
    }

    /**
     * A contract this app cannot read is refused as "the hub is ahead of me",
     * and says so in words rather than printing 2147483647 at a person.
     */
    @Test
    fun an_unreadable_contract_is_refused_without_naming_a_number() {
        val sentence = contractVerdict(UNREADABLE_CONTRACT).sentence()

        assertEquals("This hub reported a contract this app cannot read. Update the app.", sentence)
        assertEquals(ContractVerdict.AppTooOld(UNREADABLE_CONTRACT), contractVerdict(UNREADABLE_CONTRACT))
        assertTrue(UNREADABLE_CONTRACT > MAX_HUB_CONTRACT, "an unreadable contract must fall outside the range")
    }

    /** A readable one still names the revision, so an operator can compare the two sides. */
    @Test
    fun a_readable_out_of_range_contract_still_names_itself() {
        assertEquals(
            "This app is too old for this hub (contract 2). Update the app.",
            contractVerdict(2).sentence(),
        )
    }
}

/**
 * [semverAtLeast] gates `send_prompt { keys }` on the hub's own version
 * string rather than the wire-contract revision, because the `pending_input`
 * / keys addition is additive and never moved [MAX_HUB_CONTRACT]. See
 * [HUB_VERSION_KEYS].
 */
class SemverAtLeastTest {
    @Test
    fun an_equal_version_is_at_least() {
        assertTrue(semverAtLeast("0.2.35", HUB_VERSION_KEYS))
    }

    @Test
    fun a_higher_patch_is_at_least() {
        assertTrue(semverAtLeast("0.2.36", HUB_VERSION_KEYS))
    }

    @Test
    fun a_higher_minor_is_at_least() {
        assertTrue(semverAtLeast("0.3.0", HUB_VERSION_KEYS))
    }

    @Test
    fun a_higher_major_is_at_least() {
        assertTrue(semverAtLeast("1.0.0", HUB_VERSION_KEYS))
    }

    @Test
    fun a_lower_version_is_not_at_least() {
        assertFalse(semverAtLeast("0.2.34", HUB_VERSION_KEYS))
    }

    @Test
    fun a_null_version_is_not_at_least() {
        assertFalse(semverAtLeast(null, HUB_VERSION_KEYS))
    }

    @Test
    fun an_unparsable_version_is_not_at_least() {
        assertFalse(semverAtLeast("garbage", HUB_VERSION_KEYS))
    }

    @Test
    fun a_leading_v_and_a_pre_or_build_suffix_are_tolerated() {
        assertTrue(semverAtLeast("v0.2.35", HUB_VERSION_KEYS))
        assertTrue(semverAtLeast("0.2.35-pre", HUB_VERSION_KEYS))
        assertTrue(semverAtLeast("0.2.35+build.7", HUB_VERSION_KEYS))
    }
}
