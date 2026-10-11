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

    @Test
    fun dictated_words_go_after_what_is_typed() {
        assertEquals("Run the tests", withDictation("", " Run the tests "))
        assertEquals("Run the tests and push", withDictation("Run the tests ", "and push"))
        assertEquals("Line one and two", withDictation("Line one\n", "and two"))
        assertEquals("keep this", withDictation("keep this", "   "))
    }

    @Test
    fun a_codex_session_suggests_codex_commands_not_claudes() {
        assertEquals(listOf("compact", "copy"), matchSlashCommands("/c", AGENT_CODEX).map { it.name })
        assertTrue(matchSlashCommands("/context", AGENT_CODEX).isEmpty())
        assertEquals(listOf("new"), matchSlashCommands("/n", AGENT_CODEX).map { it.name })
        // A row from a hub that names no agent is Claude Code's.
        assertEquals(matchSlashCommands("/co"), matchSlashCommands("/co", null))
    }

    @Test
    fun a_codex_session_picks_from_codex_models_by_a_relaunch() {
        assertEquals(CODEX_MODEL_OPTIONS, pickerOptionsFor(AGENT_CODEX, "model"))
        assertTrue(pickerOptionsFor(AGENT_CODEX, "model").none { it.value == "opus" })
        assertTrue(pickerOptionsFor(AGENT_CODEX, "effort").none { it.value == "auto" })
        assertEquals(MODEL_OPTIONS, pickerOptionsFor("claude", "model"))
        assertEquals(EFFORT_OPTIONS, pickerOptionsFor(null, "effort"))
        assertTrue(switchesByRelaunch(AGENT_CODEX))
        assertTrue(!switchesByRelaunch("claude") && !switchesByRelaunch(null))
    }
}
