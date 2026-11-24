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

    /** Review w10-5: `launch_model` holds aliases and full ids alike (the hub's `model_family`). */
    @Test
    fun a_launch_model_maps_to_the_family_whose_window_it_bills() {
        for ((m, want) in listOf(
            "opus" to ModelFamily.OPUS,
            "Opus[1m]" to ModelFamily.OPUS,
            "claude-opus-4-5-20251101" to ModelFamily.OPUS,
            "us.anthropic.claude-opus-4-1-20250805-v1:0" to ModelFamily.OPUS,
            "sonnet" to ModelFamily.SONNET,
            "sonnet[1m]" to ModelFamily.SONNET,
            "claude-sonnet-4-5" to ModelFamily.SONNET,
            "opusplan" to null,
            "haiku" to null,
            "fable" to null,
            "default" to null,
            "best" to null,
            "" to null,
        )) assertEquals(want, modelFamily(m), m)
        assertNull(modelFamily(null))
    }

    /**
     * Review w10-5: a model's own weekly window holds only a session launched
     * on that model; with the account's window too, the one that frees last.
     */
    @Test
    fun a_model_window_holds_only_the_sessions_launched_on_that_model() {
        val opusFull = AccountUsageSnapshot(
            "a",
            AccountUsageWindows(sevenDay = UsageWindow(60.0, 9_000), sevenDayOpus = UsageWindow(100.0, 8_000)),
            "ok",
            900,
        )
        val opus = AccountLimit(weekly = true, resetsAt = 8_000, model = ModelFamily.OPUS)
        assertEquals(opus, opusFull.limitAt(now, "opus"))
        assertEquals(opus, opusFull.limitAt(now, "claude-opus-4-5-20251101"))
        assertEquals("weekly Opus", opus.windowWords)
        assertNull(opusFull.limitAt(now, "sonnet"))
        assertNull(opusFull.limitAt(now, "opusplan"))
        assertNull(opusFull.limitAt(now))
        assertEquals(mapOf(ModelFamily.OPUS to opus), opusFull.modelLimitsAt(now))
        // Reset: no longer a limit.
        assertNull(opusFull.limitAt(8_000, "opus"))

        val five = AccountLimit(weekly = false, resetsAt = 2_000)
        val models = mapOf(ModelFamily.OPUS to opus)
        assertEquals(opus, limitFor(five, models, "opus"))
        assertEquals(five, limitFor(five, models, "sonnet"))
        assertEquals(five, limitFor(five, models, null))
        // A tie: the account's own window.
        assertEquals(AccountLimit(false, 8_000), limitFor(AccountLimit(false, 8_000), models, "opus"))
        assertNull(limitFor(null, models, null))
        assertEquals("5-hour", five.windowWords)
        assertEquals("weekly", AccountLimit(weekly = true, resetsAt = null).windowWords)
        // The row carries it; absent = the host's default.
        assertEquals("opus", json.decodeFromString(SessionRow.serializer(), """{"id":1,"launch_model":"opus"}""").launchModel)
        assertNull(json.decodeFromString(SessionRow.serializer(), """{"id":1}""").launchModel)
    }

    /** w07-9: one rounding for every "% left": what is left, half up. */
    @Test
    fun percent_left_is_rounded_one_way() {
        assertEquals(37, leftPct(63.5))
        assertEquals(37, roundLeft(36.5))
        assertEquals("37% left", leftText(63.5))
        assertEquals("38% left", leftText(62.5))
        assertEquals(0, leftPct(140.0))
        assertEquals(100, leftPct(-3.0))
    }

    /** w07-6: a days-old reading is not said as today's figure. */
    @Test
    fun an_old_reading_says_its_age() {
        val now = 1_000_000L
        assertEquals("60% left", roomText(HostLogin(accountUuid = "a", usedPct = 40.0, readAt = now - 600), now))
        assertEquals("~60% left · read 4 d ago", roomText(HostLogin(accountUuid = "a", usedPct = 40.0, readAt = now - 4 * 86_400), now))
        assertEquals("60% left", roomText(HostLogin(accountUuid = "a", usedPct = 40.0), now), "a hub that does not say when")
        assertEquals("no reading", roomText(HostLogin(accountUuid = "a"), now))
    }
}
