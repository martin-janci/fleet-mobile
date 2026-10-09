package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.net.HubError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The first screen's failures in words, with the hub's own answer kept after them. */
class PairWordsTest {

    @Test
    fun a_spent_or_wrong_code_says_get_a_new_one() {
        val said = explainPair(HubError.Http(404, "invalid code"))
        assertTrue(said.startsWith("That code didn't work."), said)
        assertTrue("fleet-hub pair" in said, said)
        // Review r13 (P13-5): the status is the Details line's, not the sentence's.
        assertFalse("404" in said, "the HTTP status reached the sentence: $said")
        assertTrue("404" in pairDetails(HubError.Http(404, "invalid code")).orEmpty(), "the hub's answer stays for the log")
    }

    @Test
    fun too_many_tries_says_wait() {
        assertTrue(explainPair(HubError.Http(429, "slow down")).startsWith("Too many tries"))
    }

    @Test
    fun anything_else_is_a_sentence_with_the_hubs_answer_behind_details() {
        val other = HubError.Http(502, "bad gateway")
        val said = explainPair(other)
        assertFalse("502" in said || "HTTP" in said || "bad gateway" in said, said)
        assertEquals(explain(other), pairDetails(other))
        val odd = IllegalStateException("boom")
        assertFalse("IllegalStateException" in explainPair(odd), explainPair(odd))
        assertTrue("IllegalStateException" in pairDetails(odd).orEmpty())
    }

    @Test
    fun a_mode_says_what_it_allows() {
        assertEquals("full access", pairedModeWords("full"))
        assertTrue(pairedModeWords("readonly").startsWith("read-only"))
    }
}
