package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app's work does not run on the UI dispatcher.
 *
 * `rememberCoroutineScope()` inherits the composition's context, which on
 * Android is `AndroidUiDispatcher.Main`: the main thread, resumed on a frame
 * callback. Every view model and the repository were built with one, so the
 * SSE stream, every `tools/call`, every JSON pass and every re-derivation of
 * the session list ran on the thread that also has to draw — and each
 * suspension point cost a frame even when the work was trivial. That is the
 * defect this guards, and it is invisible in a screenshot: the app is correct
 * the whole time it is janky.
 *
 * A source scan for the same reason [MarkdownIsRenderedTest] is one: what it
 * guards is a property of the *call sites* — which scope a view model is
 * handed, which dispatcher a parse runs on — and neither a JVM unit test nor a
 * device test can observe "this ran off the main thread" without a real
 * composition and a frame clock. What can be asserted cheaply and durably is
 * that the two shapes that caused it are gone.
 */
class WorkIsOffTheUiDispatcherTest {
    @Test
    fun view_models_are_built_with_a_scope_that_is_not_the_ui_dispatcher() {
        val app = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt").readText()

        assertTrue(
            WORK_SCOPE_HELPER.containsMatchIn(app),
            "App.kt no longer defines rememberWorkScope() as " +
                "rememberCoroutineScope { Dispatchers.Default } -- the view models and the " +
                "repository would be back on the UI dispatcher",
        )
        // Zero, not "few": the helper itself uses the lambda form, so any bare
        // call left in this file is a route that kept the UI scope. A scope for
        // real UI work (a scroll animation, a snackbar) belongs in the screen
        // that animates, which is where `SessionScreen.kt` keeps its own.
        assertEquals(
            0,
            BARE_REMEMBER_SCOPE.findAll(withoutComments(app)).count(),
            "App.kt calls rememberCoroutineScope() directly -- a view model built with it runs " +
                "its stream, its hub calls and its JSON on the main thread; use rememberWorkScope()",
        )
    }

    @Test
    fun a_hub_reply_is_parsed_off_whatever_dispatcher_the_caller_is_on() {
        val client = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/net/HubClient.kt").readText()

        // Both passes, because a reply is parsed twice: once as the JSON-RPC
        // envelope, and again for the payload string MCP nests inside it. The
        // second is the one that carries `list_sessions` on a fleet of dozens.
        assertTrue(
            ENVELOPE_OFF_MAIN.containsMatchIn(client),
            "the JSON-RPC envelope is parsed on the caller's dispatcher again -- " +
                "jsonRpcReply(body) belongs inside withContext(Dispatchers.Default)",
        )
        assertTrue(
            PAYLOAD_OFF_MAIN.containsMatchIn(client),
            "the tool payload is parsed and deserialized on the caller's dispatcher again -- " +
                "deserialize(payloadOf(result)) belongs inside withContext(Dispatchers.Default)",
        )
    }

    /**
     * The source with its comments removed.
     *
     * The count below is of *calls*, and this file's own KDoc names the shape
     * it forbids — as does `rememberWorkScope`'s, which has to explain what it
     * replaces. A scan that counted prose would fail on the explanation of why
     * it exists, which is the one way to make a guard nobody will keep.
     */
    private fun withoutComments(source: String): String =
        source.replace(BLOCK_COMMENT, "").replace(LINE_COMMENT, "")

    private companion object {
        val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val LINE_COMMENT = Regex("""//[^\n]*""")

        val WORK_SCOPE_HELPER = Regex(
            """fun\s+rememberWorkScope\(\)[^=]*=\s*rememberCoroutineScope\s*\{\s*Dispatchers\.Default\s*\}""",
        )

        /** `rememberCoroutineScope()` with empty parentheses: the context-inheriting form. */
        val BARE_REMEMBER_SCOPE = Regex("""rememberCoroutineScope\(\)""")

        val ENVELOPE_OFF_MAIN = Regex(
            """withContext\(Dispatchers\.Default\)\s*\{\s*jsonRpcReply\(""",
        )

        val PAYLOAD_OFF_MAIN = Regex(
            """withContext\(Dispatchers\.Default\)\s*\{\s*deserialize\(payloadOf\(""",
        )
    }
}
