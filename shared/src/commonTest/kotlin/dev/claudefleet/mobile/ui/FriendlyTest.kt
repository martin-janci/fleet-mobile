package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.net.HubError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FriendlyTest {
    @Test
    fun a_tool_refusal_hides_the_code_in_details() {
        val f = friendly(HubError.Tool("E_FORBIDDEN", "kill_session is fleet-wide session control"))
        assertEquals("The hub refused that", f.title)
        assertEquals("kill_session is fleet-wide session control", f.body)
        assertTrue(f.isError)
        assertEquals("E_FORBIDDEN: kill_session is fleet-wide session control", f.details)
    }

    @Test
    fun no_transcript_is_not_an_error() {
        val f = friendly(HubError.Tool("E_NO_TRANSCRIPT", "no transcript for claude session 0b63c561-66fd on htz"))
        assertFalse(f.isError)
        assertEquals("Nothing has been said yet", f.title)
        assertFalse("0b63c561" in f.body, "the UUID never reaches the summary line")
    }

    @Test
    fun a_missing_session_reads_as_gone() {
        val f = friendly(HubError.Tool("E_NOTFOUND", "no session 21520"))
        assertEquals("This session is gone", f.title)
    }

    @Test
    fun transport_and_unknown_throwables_keep_the_token_rule() {
        val f = friendly(RuntimeException("Bearer abc123"))
        assertFalse("abc123" in f.body)
        assertEquals("Something went wrong", f.title)
        // Details exists now, and the token rule holds there too: it is
        // `explain`'s output, which never repeats an unexpected throwable's
        // own message.
        assertFalse("abc123" in f.details.orEmpty())
    }

    /**
     * Every branch offers Details.
     *
     * The four branches below had none, which inverted the design: a tool
     * refusal — the one failure that explains itself in its own message — had
     * the evidence behind a button, while the four a person can do least about
     * (signed out, cannot reach, an odd reply, an unexpected throwable) had
     * nothing to show an operator at all. The body is now the short thing to
     * do; the raw text is what Details holds.
     */
    @Test
    fun every_failure_has_a_body_to_read_and_details_to_open() {
        val cases = listOf(
            friendly(HubError.Unauthorized("the hub refused the credential")),
            friendly(HubError.Transport(RuntimeException("reset by peer"))),
            friendly(HubError.Http(502, "<html>bad gateway</html>")),
            friendly(RuntimeException("boom")),
        )

        for (f in cases) {
            assertTrue(f.title.isNotBlank(), "no title: $f")
            assertTrue(f.body.isNotBlank(), "no body: $f")
            assertTrue(f.isError, "these are all errors: $f")
            assertTrue(!f.details.isNullOrBlank(), "nothing behind Details: $f")
        }
    }

    @Test
    fun an_unreachable_hub_says_what_to_check_and_keeps_the_reason_behind_details() {
        val f = friendly(HubError.Transport(RuntimeException("Bearer abc123 was rejected")))

        assertEquals("Cannot reach the hub", f.title)
        assertEquals("Check the network and the hub address.", f.body)
        // `Transport` names the failure's TYPE and never its text, so this is
        // what Details can honestly hold — and the token rule survives it.
        assertEquals("could not reach the hub (RuntimeException)", f.details)
        assertFalse("abc123" in f.details.orEmpty())
    }

    /**
     * `explain(HubError.Http)` is a proxy's error page, up to a thousand
     * characters of it. That is evidence, not a summary line, so it belongs
     * behind Details and the banner says so in one sentence.
     */
    @Test
    fun an_odd_reply_keeps_the_hubs_own_words_out_of_the_summary_line() {
        val f = friendly(HubError.Http(502, "<html><body>bad gateway</body></html>"))

        assertEquals("The hub answered oddly", f.title)
        assertEquals("The hub's reply is under Details.", f.body)
        assertTrue("bad gateway" in f.details.orEmpty(), "the reply itself has to survive somewhere")
    }
}
