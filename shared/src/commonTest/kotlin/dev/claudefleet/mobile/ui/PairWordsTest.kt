package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.net.HubError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The first screen's failures in words, with the hub's own answer kept after them. */
class PairWordsTest {

    @Test
    fun a_spent_or_wrong_code_says_get_a_new_one() {
        val said = explainPair(HubError.Http(404, "invalid code"))
        assertTrue(said.startsWith("That code didn't work."), said)
        assertTrue("fleet-hub pair" in said, said)
        assertTrue("404" in said, "the hub's answer stays for the log: $said")
    }

    @Test
    fun too_many_tries_says_wait() {
        assertTrue(explainPair(HubError.Http(429, "slow down")).startsWith("Too many tries"))
    }

    @Test
    fun anything_else_is_explained_as_before() {
        val other = HubError.Http(502, "bad gateway")
        assertEquals(explain(other), explainPair(other))
    }

    @Test
    fun a_mode_says_what_it_allows() {
        assertEquals("full access", pairedModeWords("full"))
        assertTrue(pairedModeWords("readonly").startsWith("read-only"))
    }
}
