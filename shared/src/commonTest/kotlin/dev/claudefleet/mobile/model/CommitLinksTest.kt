package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The commit screen's ticket links and Open on GitHub (MobileSessionFiles › Commit, gap plan G7.16). */
class CommitLinksTest {

    private val project = ProjectRow(id = 4, owner = "martin-janci", repo = "fleet-mobile")
    private val work = WorkSummary(key = "FLEET-142", url = "https://linear.app/fleet/issue/FLEET-142")

    @Test
    fun ticket_keys_and_issue_numbers_are_found_where_they_stand() {
        val text = "Unreachable hosts sort last. Fixes FLEET-142 (and #88)."
        val refs = ticketRefs(text)
        assertEquals(listOf("FLEET-142", "#88"), refs.map { it.key })
        assertEquals("FLEET-142", text.substring(refs[0].start, refs[0].end))
        assertEquals("#88", text.substring(refs[1].start, refs[1].end))
    }

    @Test
    fun words_that_only_look_like_keys_are_not_tickets() {
        assertEquals(emptyList(), ticketRefs("Read it as UTF-8, hash it with SHA-256").map { it.key })
        assertEquals(emptyList(), ticketRefs("see a/FLEET-1, x-FLEET-2, abc#3, FLEET-4x, Fleet-5").map { it.key })
        assertEquals(emptyList(), ticketRefs("https://github.com/o/r/pull/12#issuecomment-3").map { it.key })
    }

    @Test
    fun the_sessions_own_ticket_opens_at_its_tracker_and_an_issue_number_on_github() {
        assertEquals(work.url, ticketRefUrl("FLEET-142", work, project))
        assertEquals(work.url, ticketRefUrl("fleet-142", work, project), "keys compare without case")
        assertEquals("https://github.com/martin-janci/fleet-mobile/issues/88", ticketRefUrl("#88", work, project))
        assertNull(ticketRefUrl("PAY-7", work, project), "another tracker's key has no address the phone can build")
        assertNull(ticketRefUrl("#88", work, null))
        assertNull(ticketRefUrl("FLEET-142", WorkSummary(key = "FLEET-142"), project), "a ticket without a link stays plain")
    }

    @Test
    fun open_on_github_needs_the_projects_owner_and_repo() {
        assertEquals("https://github.com/martin-janci/fleet-mobile/commit/9f3c2a1e", githubCommitUrl(project, "9f3c2a1e"))
        assertNull(githubCommitUrl(ProjectRow(id = 5, repo = "local-only"), "9f3c2a1e"))
        assertNull(githubCommitUrl(null, "9f3c2a1e"))
        assertNull(githubCommitUrl(project, ""))
    }
}
