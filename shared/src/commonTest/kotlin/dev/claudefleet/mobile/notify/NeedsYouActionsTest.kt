package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.model.Attention
import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.PendingOption
import dev.claudefleet.mobile.model.SessionRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Lock-screen notifications for Needs you (redesign 14.8, MobileControl): the
 * question, Answer and Later; a failure's Open log and Retry — and nothing
 * that answers.
 */
class NeedsYouActionsTest {

    private val asking = SessionRow(
        id = 7,
        tmuxName = "s7",
        friendlyName = "Fix hub-e2e flake",
        hostAlias = "pine",
        claudeStatus = "blocked",
        attention = Attention("waiting"),
        pendingInput = PendingInput(
            "permission",
            "Approve push to main? git push -u origin fix-hub-e2e",
            listOf(PendingOption(1, "Yes", true), PendingOption(2, "No, and tell Claude what to do differently")),
        ),
    )

    private fun alertFor(row: SessionRow) = needsYouAlerts(emptyMap(), listOf(row)).first.single()

    @Test
    fun no_notification_action_approves_anything() {
        for (kind in NotifyKind.entries) {
            for (retryable in listOf(false, true)) {
                val actions = needsYouActions(kind, retryable)
                // Only a failure that can be tried again offers Retry, and Retry opens the app first.
                val allowed = if (kind == NotifyKind.FAILED && retryable) NotifyActionKind.Retry else NotifyActionKind.Later
                assertEquals(setOf(NotifyActionKind.Open, allowed), actions.map { it.kind }.toSet())
                val words = actions.map { it.label.lowercase() }
                for (banned in listOf("approve", "allow", "yes", "deny", "send")) {
                    assertTrue(words.none { banned in it }, "$kind offers \"$banned\"")
                }
            }
        }
        // Not even the agent's own answers find their way onto a button.
        val labels = needsYouContent(alertFor(asking)).actions.map { it.label }
        assertEquals(listOf("Answer", "Later"), labels)
    }

    @Test
    fun the_notification_carries_the_question_and_never_an_answer() {
        val c = needsYouContent(alertFor(asking))
        assertEquals("Fix hub-e2e flake needs you", c.title)
        assertEquals("Approve push to main? git push -u origin fix-hub-e2e", c.body.lines().first())
        assertFalse("No, and tell Claude" in c.body, "the answers stay in the app")
        assertEquals(7L, c.sessionId)
    }

    @Test
    fun the_lock_screen_shows_the_session_and_why_but_not_the_command() {
        val c = needsYouContent(alertFor(asking))
        assertEquals("Fix hub-e2e flake needs you", c.publicTitle)
        assertEquals("Needs you · pine", c.publicBody)
        assertFalse("git push" in c.publicTitle + c.publicBody)
    }

    @Test
    fun a_failure_reads_failed_and_opens_its_log_rather_than_answers() {
        val failed = asking.copy(claudeStatus = "failed", pendingInput = null, attention = Attention("failed"))
        val c = needsYouContent(alertFor(failed))
        assertEquals("Fix hub-e2e flake failed", c.title)
        assertEquals(NEEDS_YOU_FAILED_CATEGORY, c.category)
        assertEquals(listOf("Open log", "Later"), c.actions.map { it.label }, "nothing to send again: no Retry")
    }

    /** MobileControl: "PD-2592 failed · Open log · Retry", Retry being the Inbox failed row's own. */
    @Test
    fun a_failure_with_a_last_prompt_offers_open_log_and_retry() {
        val failed = asking.copy(claudeStatus = "failed", pendingInput = null, attention = Attention("failed"), lastPrompt = "run the tests")
        val alert = alertFor(failed)
        assertTrue(alert.retryable)
        val c = needsYouContent(alert)
        assertEquals(NEEDS_YOU_RETRY_CATEGORY, c.category)
        assertEquals(listOf("Open log", "Retry"), c.actions.map { it.label })
        assertEquals(listOf(NEEDS_YOU_ACTION_OPEN, NEEDS_YOU_ACTION_RETRY), c.actions.map { it.id })

        // The Inbox would not retry Control's own session, a stuck one, or one still asking: nor does the notification.
        assertFalse(alertFor(failed.copy(isController = true)).retryable)
        assertFalse(alertFor(failed.copy(claudeStatus = "blocked")).retryable)
    }

    @Test
    fun every_category_is_registered_with_its_buttons() {
        val categories = needsYouCategories()
        assertEquals(setOf(NEEDS_YOU_CATEGORY, NEEDS_YOU_FAILED_CATEGORY, NEEDS_YOU_RETRY_CATEGORY, NEEDS_YOU_DONE_CATEGORY), categories.keys)
        assertEquals(listOf(NEEDS_YOU_ACTION_OPEN, NEEDS_YOU_ACTION_LATER), categories.getValue(NEEDS_YOU_CATEGORY).map { it.id })
        assertEquals(listOf(NEEDS_YOU_ACTION_OPEN, NEEDS_YOU_ACTION_LATER), categories.getValue(NEEDS_YOU_FAILED_CATEGORY).map { it.id })
        assertEquals(listOf(NEEDS_YOU_ACTION_OPEN, NEEDS_YOU_ACTION_RETRY), categories.getValue(NEEDS_YOU_RETRY_CATEGORY).map { it.id })
        assertEquals(listOf(NEEDS_YOU_ACTION_OPEN, NEEDS_YOU_ACTION_LATER), categories.getValue(NEEDS_YOU_DONE_CATEGORY).map { it.id })
    }
}
