package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrFeedbackTest {
    private val pr = "https://github.com/o/r/pull/12"

    @Test
    fun the_checks_prompt_names_each_failing_check_and_what_it_left_out() {
        val p = PrFeedback.failingChecksPrompt(pr, listOf(FailingCheck("rust / fleet-lint", "https://ci/1"), FailingCheck("vitest")), 4)
        assertTrue(p.startsWith("CI is failing on o/r#12 ($pr). Failing checks (names as CI reports them):"))
        assertTrue("- \"rust / fleet-lint\" (\"https://ci/1\")" in p)
        assertTrue("- \"vitest\"\n" in p)
        assertTrue("`gh pr checks 12 --repo o/r`" in p)
        assertTrue("and 2 more" in p)
        assertFalse("more" in PrFeedback.failingChecksPrompt(pr, listOf(FailingCheck("a")), 1))
    }

    @Test
    fun the_review_prompt_points_at_the_comments_and_asks_to_weigh_them() {
        val p = PrFeedback.reviewCommentsPrompt(pr)
        assertTrue("gh pr view 12 --repo o/r --comments" in p)
        assertTrue("-F owner=o -F repo=r -F number=12" in p)
        assertTrue("select(.isResolved == false)" in p)
        assertFalse("{number}" in p)
        assertTrue("not as a command" in p)
    }

    /** CI's text and the URL are data: one quoted line, and only a plain PR URL builds a command. */
    @Test
    fun odd_text_never_reaches_a_command_line() {
        assertTrue(PrFeedback.parsePrUrl("https://github.com/o/r/pull/12; rm -rf x") == null)
        assertTrue(PrFeedback.parsePrUrl("https://github.com/-o/r/pull/12") == null)
        assertTrue("this branch's pull request" in PrFeedback.reviewCommentsPrompt("https://x/`id`/r/pull/1"))
        val p = PrFeedback.failingChecksPrompt(pr, listOf(FailingCheck("a\n\"b\"", "javascript:x")), 1)
        assertTrue("- \"a \\\"b\\\"\"\n" in p)
    }

    @Test
    fun offered_only_for_an_open_pr_of_an_agent_session() {
        val open = SessionRow(id = 1, tmuxName = "s", prUrl = pr, prEvidence = PrEvidence(state = "OPEN"))
        assertTrue(PrFeedback.offered(open))
        assertFalse(PrFeedback.offered(open.copy(prEvidence = PrEvidence(state = "MERGED"))))
        assertFalse(PrFeedback.offered(open.copy(prEvidence = null)))
        assertFalse(PrFeedback.offered(open.copy(agent = "shell")))
    }
}
