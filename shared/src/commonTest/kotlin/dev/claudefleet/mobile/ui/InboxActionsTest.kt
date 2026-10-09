package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.AccountLimit
import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.model.AccountUsageWindows
import dev.claudefleet.mobile.model.Attention
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.UsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Inbox's failed and paused rows (MobileNav): Retry, and when a paused row's limit resets. */
class InboxActionsTest {

    private fun row(status: String?, lastPrompt: String? = null, attention: Attention? = null, stuck: String? = null) =
        SessionRow(id = 7, tmuxName = "s7", claudeStatus = status, lastPrompt = lastPrompt, attention = attention, stuckKind = stuck)

    private fun usage(fiveHour: UsageWindow? = null, sevenDay: UsageWindow? = null) =
        AccountUsageSnapshot(accountUuid = "a", usage = AccountUsageWindows(fiveHour = fiveHour, sevenDay = sevenDay))

    @Test
    fun a_failed_row_retries_its_last_prompt() {
        assertEquals("Run the suite", inboxRetryPrompt(row("failed", lastPrompt = "Run the suite")))
        assertNull(inboxRetryPrompt(row("failed")), "nothing to send again: no Retry")
        assertNull(inboxRetryPrompt(row("blocked", lastPrompt = "x")), "only a failed session retries")
        assertNull(inboxRetryPrompt(row("idle", lastPrompt = "x", stuck = "wedged")), "a stuck row has no last turn to retry")
    }

    @Test
    fun a_reading_at_its_limit_decides_when_it_resets() {
        val r = row("idle", attention = Attention("account_limit"))
        val u = usage(fiveHour = UsageWindow(100.0, resetsAt = 2_000), sevenDay = UsageWindow(40.0, resetsAt = 9_000))
        assertEquals(AccountLimit(weekly = false, resetsAt = 2_000), inboxLimit(r, u, 1_000))
    }

    @Test
    fun a_paused_row_falls_back_to_its_fullest_window_while_the_reading_lags() {
        val r = row("idle", attention = Attention("account_limit"))
        val u = usage(fiveHour = UsageWindow(98.0, resetsAt = 2_000), sevenDay = UsageWindow(40.0, resetsAt = 9_000))
        assertEquals(AccountLimit(weekly = false, resetsAt = 2_000), inboxLimit(r, u, 1_000))
        // A window already reset says nothing about when this one does.
        val stale = usage(fiveHour = UsageWindow(98.0, resetsAt = 500), sevenDay = UsageWindow(40.0, resetsAt = 9_000))
        assertEquals(AccountLimit(weekly = true, resetsAt = 9_000), inboxLimit(r, stale, 1_000))
    }

    @Test
    fun only_a_paused_row_borrows_a_window_and_only_with_a_reading() {
        val u = usage(fiveHour = UsageWindow(98.0, resetsAt = 2_000))
        assertNull(inboxLimit(row("failed", attention = Attention("failed")), u, 1_000))
        assertNull(inboxLimit(row("idle", attention = Attention("account_limit")), null, 1_000))
        assertNull(inboxLimit(row("idle", attention = Attention("account_limit")), usage(), 1_000))
    }
}
