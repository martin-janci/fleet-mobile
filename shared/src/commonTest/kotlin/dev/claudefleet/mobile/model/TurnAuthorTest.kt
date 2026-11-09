package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Who sent each prompt (claude-fleet migration 224): a colleague's prompt names them. */
class TurnAuthorTest {
    @Test
    fun a_colleagues_prompt_names_them_and_the_readers_own_does_not() {
        val theirs = json.decodeFromString<ConvTurn>(
            """{"prompt":"fix it","author":{"name":"Ada · phone","person_id":2}}""",
        )
        assertEquals("Ada · phone", theirs.otherAuthor())
        assertEquals(2L, theirs.author?.personId)
        val mine = json.decodeFromString<ConvTurn>(
            """{"prompt":"fix it","author":{"name":"Martin · desktop","mine":true}}""",
        )
        assertNull(mine.otherAuthor())
        // An older hub, or a prompt typed in the terminal.
        assertNull(json.decodeFromString<ConvTurn>("""{"prompt":"fix it"}""").otherAuthor())
    }
}
