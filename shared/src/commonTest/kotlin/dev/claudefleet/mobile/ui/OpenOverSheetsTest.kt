package dev.claudefleet.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** Review r09 F5: a notification's session opens with every sheet closed, so the question is on top. */
class OpenOverSheetsTest {
    @Test
    fun every_sheet_closes_before_the_session_opens() {
        val log = mutableListOf<String>()
        openOverSheets(42, listOf({ log += "today" }, { log += "tidy" }), open = { log += "open $it" })
        assertEquals(listOf("today", "tidy", "open 42"), log)
    }
}
