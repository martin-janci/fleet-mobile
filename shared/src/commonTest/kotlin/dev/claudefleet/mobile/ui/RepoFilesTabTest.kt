package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.SessionRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Files tab's file Find and its words (MobileSessionFiles, gap plan G7.16). */
class RepoFilesTabTest {

    private val lines = listOf(
        "@Composable",
        "private fun HostRow(host: Host) {",
        "    Text(host.name)",
        "    host.lastPing?.let { PingChip(it) } // host",
        "}",
    )

    @Test
    fun find_counts_every_occurrence_top_to_bottom_ignoring_case() {
        val found = findInLines(lines, "host")
        assertEquals(
            listOf(FileMatch(1, 12), FileMatch(1, 20), FileMatch(1, 26), FileMatch(2, 9), FileMatch(3, 4), FileMatch(3, 43)),
            found,
        )
    }

    @Test
    fun a_blank_query_finds_nothing_and_occurrences_do_not_overlap() {
        assertTrue(findInLines(lines, "").isEmpty())
        assertTrue(findInLines(lines, "   ").isEmpty())
        assertEquals(listOf(FileMatch(0, 0), FileMatch(0, 2)), findInLines(listOf("aaaa"), "aa"))
    }

    @Test
    fun the_match_find_is_on_is_marked_apart_from_the_rest() {
        val marks = marksByLine(findInLines(lines, "host"), 4, at = 3)
        assertEquals(setOf(1, 2, 3), marks.keys)
        assertEquals(listOf(LineMark(9, 13, current = true)), marks[2])
        assertEquals(listOf(false, false, false), marks[1]?.map { it.current })
    }

    @Test
    fun the_commit_button_is_named_after_the_sessions_agent() {
        assertEquals("Ask Codex to commit", askToCommitLabel(SessionRow(id = 1, agent = "codex")))
        assertEquals("Ask Claude Code to commit", askToCommitLabel(SessionRow(id = 1, agent = "claude")))
        assertEquals("Ask Claude Code to commit", askToCommitLabel(SessionRow(id = 1)), "a hub too old to say runs Claude Code")
        assertEquals("Ask Claude Code to commit", askToCommitLabel(null))
    }

    @Test
    fun ask_to_push_names_the_commit() {
        assertEquals("Push this branch so commit 9f3c2a1 reaches the remote.", askToPush("9f3c2a1"))
    }
}
