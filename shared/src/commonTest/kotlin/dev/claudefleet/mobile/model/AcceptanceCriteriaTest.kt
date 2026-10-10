package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A task's acceptance criteria as MobileWork's task detail lists them, read
 * the way the hub reads a ticket card's (`service::work::card::
 * acceptance_criteria`, whose cases these are), plus what is ticked.
 */
class AcceptanceCriteriaTest {

    @Test
    fun criteria_are_read_from_the_common_shapes() {
        val cases = listOf(
            Triple(
                "jira adf text: heading then list items",
                "As a user I want refunds.\nAcceptance Criteria\n- Refund is issued\n- Email is sent\nNotes\n- not this",
                listOf("Refund is issued", "Email is sent"),
            ),
            Triple(
                "markdown heading with a colon, numbered and checkbox items",
                "## Acceptance criteria:\n1. First\n2) Second\n[ ] Third\n[x] Fourth\n\n## Design\nnope",
                listOf("First", "Second", "Third", "Fourth"),
            ),
            Triple("bold heading, bullets", "**Acceptance Criteria**\n* one\n• two\n", listOf("one", "two")),
            Triple("inline AC", "AC: the page loads in 2s\nOther text", listOf("the page loads in 2s", "Other text")),
            Triple(
                "gherkin",
                "Acceptance criteria\nGiven a cart\nWhen I pay\nThen I get a receipt\n\n\nUnrelated",
                listOf("Given a cart", "When I pay", "Then I get a receipt"),
            ),
            Triple("definition of done", "Definition of Done\n- tests\n- docs\nOut of scope\n- mobile", listOf("tests", "docs")),
            Triple("a section running into a short colon heading", "Acceptance criteria\n- works\nRisks:\n- none", listOf("works")),
            Triple("CRLF", "Acceptance criteria\r\n- a\r\n- b\r\n", listOf("a", "b")),
            Triple("none named", "Just a description.\n- a bullet", emptyList<String>()),
            Triple("an AC-prefixed word is not a heading", "Access control matters\n- one", emptyList<String>()),
        )
        for ((what, text, want) in cases) {
            assertEquals(want, acceptanceCriteria(text).map { it.text }, what)
        }
    }

    @Test
    fun ticked_boxes_say_how_far_along_it_is() {
        val c = acceptanceCriteria(
            "## Acceptance criteria\n- [x] Each host row shows the last ping\n- [ ] Transport shown as a chip\n- [ ] Unreachable hosts sort last",
        )
        assertEquals(listOf(true, false, false), c.map { it.done })
        assertEquals("1 of 3 done", acceptanceProgress(c))
        assertEquals("Transport shown as a chip", c.first { !it.done }.text)
    }

    @Test
    fun criteria_without_boxes_are_counted_not_scored() {
        assertEquals("2 criteria", acceptanceProgress(acceptanceCriteria("AC\n- one\n- two")))
        assertEquals("1 criterion", acceptanceProgress(acceptanceCriteria("AC: one")))
        assertNull(acceptanceProgress(emptyList()))
    }

    @Test
    fun criteria_are_capped_in_count_and_length() {
        val text = buildString {
            append("Acceptance criteria\n")
            repeat(40) { append("- item $it ${"x".repeat(400)}\n") }
        }
        val got = acceptanceCriteria(text)
        assertEquals(CRITERIA_MAX, got.size)
        assertTrue(got.all { it.text.length <= CRITERION_MAX_CHARS && it.text.endsWith("…") })
    }
}
