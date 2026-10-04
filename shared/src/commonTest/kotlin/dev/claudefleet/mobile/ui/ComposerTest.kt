package dev.claudefleet.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The composer's slash suggestions and pickers, held to the desktop's `conversation.ts`. */
class ComposerTest {

    @Test
    fun a_slash_token_suggests_the_commands_it_starts() {
        assertEquals(listOf("compact", "context", "cost", "config"), matchSlashCommands("/co").map { it.name })
        assertEquals(SLASH_COMMANDS.size, matchSlashCommands("/").size)
    }

    @Test
    fun suggestions_get_out_of_the_way_of_an_argument_or_plain_text() {
        assertTrue(matchSlashCommands("/compact focus").isEmpty())
        assertTrue(matchSlashCommands("fix it").isEmpty())
        assertTrue(matchSlashCommands("/clear\n").isEmpty())
    }

    @Test
    fun a_command_with_an_argument_completes_with_a_space() {
        assertEquals("/model ", completeSlashCommand(SLASH_COMMANDS.single { it.name == "model" }))
        assertEquals("/clear", completeSlashCommand(SLASH_COMMANDS.single { it.name == "clear" }))
    }

    @Test
    fun a_picker_sends_one_argument_or_nothing() {
        assertEquals("/model opus[1m]", pickerCommand("model", " opus[1m] "))
        assertEquals("/effort high", pickerCommand("effort", "high"))
        assertNull(pickerCommand("model", ""))
        assertNull(pickerCommand("model", "two words"))
    }
}
