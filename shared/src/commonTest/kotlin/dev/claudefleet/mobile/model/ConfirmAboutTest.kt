package dev.claudefleet.mobile.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The hub's `ConfirmRequest.about` reaches the card: the call in words, not only the bound summary. */
class ConfirmAboutTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun a_described_confirm_decodes_its_words() {
        val c = json.decodeFromString(
            ConfirmRequest.serializer(),
            """{"nonce":"n","tool":"work_link","summary":"start key=\"PD-2709\"#6371","caller":"client:ux-agent",
               "operator":true,"asked_at":1,
               "about":{"title":"Start a session for PD-2709",
                 "facts":[{"label":"Ticket","value":"PD-2709 Fix the login","url":"https://t/PD-2709"},{"label":"Host","value":"mefistos"}],
                 "body":{"label":"Brief","text":"Fix the login redirect."}}}""",
        )
        val a = c.about!!
        assertEquals("Start a session for PD-2709", a.title)
        assertEquals(listOf("Ticket", "Host"), a.facts.map { it.label })
        assertEquals("https://t/PD-2709", a.facts[0].url)
        assertNull(a.facts[1].url)
        assertEquals("Fix the login redirect.", a.body?.text)
    }

    @Test
    fun an_older_hub_sends_no_words() {
        val c = json.decodeFromString(ConfirmRequest.serializer(), """{"nonce":"n","tool":"kill_session","summary":"s"}""")
        assertNull(c.about)
    }
}
