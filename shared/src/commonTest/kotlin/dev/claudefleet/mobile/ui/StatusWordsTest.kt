package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.ui.components.workStatusWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a screen reader hears where the eye gets a colour: every status has a word. */
class StatusWordsTest {

    @Test
    fun every_git_status_letter_has_a_word() {
        assertEquals("added", gitStatusWord("A"))
        assertEquals("deleted", gitStatusWord("D"))
        assertEquals("modified", gitStatusWord("M"))
        assertEquals("untracked", gitStatusWord("?"))
        assertEquals("changed", gitStatusWord("X"))
    }

    @Test
    fun every_ticket_bucket_has_a_word() {
        for (c in StatusCategory.entries) assertTrue(workStatusWord(c).isNotBlank(), "$c")
        assertEquals("in progress", workStatusWord(StatusCategory.InProgress))
    }
}
