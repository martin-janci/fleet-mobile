package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.StatusCategory
import dev.claudefleet.mobile.model.gitStatusLetter
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

    /** The hub words a changed file's status (`repo_read.rs` `classify`); the row draws git's letter for it. */
    @Test
    fun the_hubs_status_word_is_drawn_as_gits_letter() {
        assertEquals(
            listOf("M", "A", "D", "R", "C", "U", "?"),
            listOf("modified", "added", "deleted", "renamed", "copied", "conflict", "untracked").map { gitStatusLetter(it) },
        )
        assertEquals("M", gitStatusLetter("M"), "a letter is taken as it is")
        assertEquals("?", gitStatusLetter(""))
        for (word in listOf("modified", "added", "deleted", "renamed", "copied", "untracked")) {
            assertEquals(word, gitStatusWord(gitStatusLetter(word)), "a screen reader hears the hub's own word")
        }
        assertEquals("conflicted", gitStatusWord(gitStatusLetter("conflict")))
    }

    @Test
    fun every_ticket_bucket_has_a_word() {
        for (c in StatusCategory.entries) assertTrue(workStatusWord(c).isNotBlank(), "$c")
        assertEquals("in progress", workStatusWord(StatusCategory.InProgress))
    }
}
