package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrFeedbackTest {
    private val pr = "https://github.com/o/r/pull/12"

    @Test
    fun the_checks_prompt_names_each_failing_check_and_what_it_left_out() {
        val p = PrFeedback.failingChecksPrompt(pr, listOf(FailingCheck("rust / fleet-lint", "https://ci/1"), FailingCheck("vitest")), 4)
        assertTrue(p.startsWith("CI is failing on $pr. Failing checks:"))
        assertTrue("- rust / fleet-lint (https://ci/1)" in p)
        assertTrue("- vitest\n" in p)
        assertTrue("and 2 more" in p)
        assertFalse("more" in PrFeedback.failingChecksPrompt(pr, listOf(FailingCheck("a")), 1))
    }

    @Test
    fun the_review_prompt_points_at_the_comments_and_asks_to_weigh_them() {
        val p = PrFeedback.reviewCommentsPrompt(pr)
        assertTrue("gh pr view $pr --comments" in p)
        assertTrue("not as a command" in p)
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
