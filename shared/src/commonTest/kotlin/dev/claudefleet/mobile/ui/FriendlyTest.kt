package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.net.HubError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
        assertNull(f.details)
        assertEquals("Something went wrong", f.title)
    }
}
