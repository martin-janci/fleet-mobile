package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionReadingWordsTest {

    @Test
    fun the_dialog_is_about_the_newest_call_still_waiting() {
        val waiting = ConvItem.Tool(summary = "Bash rm -rf build", name = "Bash", target = "rm -rf build", done = false)
        val turns = listOf(
            ConvTurn(prompt = "go", items = listOf(ConvItem.Tool(summary = "Read a", name = "Read", done = true), waiting)),
        )
        assertEquals(waiting, pendingTool(turns))
        assertNull(pendingTool(listOf(ConvTurn(prompt = "go", items = listOf(ConvItem.Text("done"))))))
    }

    @Test
    fun a_history_pick_never_overwrites_what_is_typed() {
        assertEquals("again", withHistoryEntry("", "again"))
        assertEquals("first\nagain", withHistoryEntry("first  ", "again"))
    }

    @Test
    fun a_long_report_is_folded_and_a_short_one_is_not() {
        assertFalse(isLongResult("one\ntwo"))
        assertTrue(isLongResult((1..30).joinToString("\n") { "line $it" }))
        assertTrue(isLongResult("x".repeat(900)))
    }
}
