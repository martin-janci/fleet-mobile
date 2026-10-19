package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The hub's own limit rule (`attention::Facts::from_fleet`), on the phone (step 4.10). */
class AccountLimitTest {
    private val now = 1_000L

    private fun snap(fiveHour: UsageWindow? = null, sevenDay: UsageWindow? = null, fetchedAt: Long? = null) =
        AccountUsageSnapshot("a", AccountUsageWindows(fiveHour, sevenDay), "ok", fetchedAt)

    @Test
    fun a_full_window_that_has_not_reset_is_a_limit_and_the_weekly_one_wins() {
        assertEquals(AccountLimit(weekly = false, resetsAt = 2_000), snap(fiveHour = UsageWindow(100.0, 2_000)).limitAt(now))
        assertEquals(
            AccountLimit(weekly = true, resetsAt = 9_000),
            snap(UsageWindow(100.0, 2_000), UsageWindow(100.0, 9_000)).limitAt(now),
        )
        assertEquals(AccountLimit(weekly = false, resetsAt = null), snap(fiveHour = UsageWindow(100.0, null), fetchedAt = now - 60).limitAt(now))
    }

    /** Review w23-6: the hub's `Window::live_at` and "the one that frees last". */
    @Test
    fun both_at_their_limit_the_one_that_frees_last_decides() {
        // Weekly resets in 1 h, the 5-hour window in 3 h: paused for 3 h.
        assertEquals(
            AccountLimit(weekly = false, resetsAt = now + 3 * 3_600),
            snap(UsageWindow(100.0, now + 3 * 3_600), UsageWindow(100.0, now + 3_600)).limitAt(now),
        )
        // A tie is weekly's; no reset time holds longest.
        assertEquals(AccountLimit(weekly = true, resetsAt = 5_000), snap(UsageWindow(100.0, 5_000), UsageWindow(100.0, 5_000)).limitAt(now))
        assertEquals(
            AccountLimit(weekly = false, resetsAt = null),
            snap(UsageWindow(100.0, null), UsageWindow(100.0, 5_000), fetchedAt = now - 60).limitAt(now),
        )
    }

    @Test
    fun a_full_window_with_no_reset_time_holds_only_while_the_reading_is_younger_than_the_window() {
        assertNull(snap(fiveHour = UsageWindow(100.0, null)).limitAt(now), "no reading time, nothing to go on")
        val twoDays = 1_000_000L
        assertNull(snap(fiveHour = UsageWindow(100.0, null), fetchedAt = twoDays - 2 * 86_400).limitAt(twoDays))
        assertEquals(
            AccountLimit(weekly = true, resetsAt = null),
            snap(sevenDay = UsageWindow(100.0, null), fetchedAt = twoDays - 2 * 86_400).limitAt(twoDays),
        )
    }

    @Test
    fun a_window_under_full_or_already_reset_is_no_limit() {
        assertNull(snap(fiveHour = UsageWindow(99.0, 2_000)).limitAt(now))
        assertNull(snap(fiveHour = UsageWindow(100.0, 500)).limitAt(now))
        assertNull(AccountUsageSnapshot("a").limitAt(now))
    }

    @Test
    fun the_hub_snapshot_decodes_with_the_fields_the_phone_ignores() {
        val s = json.decodeFromString(
            AccountUsageSnapshot.serializer(),
            """{"account_uuid":"a","usage":{"five_hour":{"utilization":100.0,"resets_at":2000},
               "seven_day":{"utilization":40.0,"resets_at":null},"seven_day_opus":null},
               "subscription":"max","fetched_at":900,"source_host":"mercury","status":"ok",
               "detail":null,"next_try_at":1200}""",
        )
        assertEquals(AccountLimit(weekly = false, resetsAt = 2_000), s.limitAt(now))
    }

    /**
     * Review r05 M4: the label follows the desktop's `accountLabel`, so one
     * person's two accounts (one display name) stay apart on the phone.
     */
    @Test
    fun an_account_label_is_the_nickname_then_the_email_then_the_short_uuid() {
        val row = AccountRow(uuid = "0123456789ab", email = "me@work.io", displayName = "Martin")
        assertEquals("Work", row.copy(nickname = " Work ").label)
        assertEquals("me@work.io", row.label)
        assertEquals("me@work.io", row.copy(nickname = "  ").label)
        assertEquals("01234567", row.copy(email = "").label)
        assertEquals("01234567", row.copy(email = null).label)
    }
}
