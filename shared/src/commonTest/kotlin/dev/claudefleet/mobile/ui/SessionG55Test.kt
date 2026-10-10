package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.model.AccountUsageWindows
import dev.claudefleet.mobile.model.BranchDiff
import dev.claudefleet.mobile.model.ChangedFile
import dev.claudefleet.mobile.model.CheckSummary
import dev.claudefleet.mobile.model.CommitDetail
import dev.claudefleet.mobile.model.Headroom
import dev.claudefleet.mobile.model.HostLogin
import dev.claudefleet.mobile.model.QueuedPrompt
import dev.claudefleet.mobile.model.SendLaterTiming
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.UsageWindow
import dev.claudefleet.mobile.model.branchLine
import dev.claudefleet.mobile.model.checksLabel
import dev.claudefleet.mobile.model.lineCounts
import dev.claudefleet.mobile.model.pushedLabel
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gap plan G5.5 on the phone: the session's account meter and PR checks in
 * Details, Files' per-file +/−, "behind" and pushed (claude-fleet G1.10), and
 * Send later's time choices (G1.8). Every new field is optional, so an older
 * hub's answer still decodes and draws what it did.
 */
class SessionG55Test {

    // 2027-01-15 08:00:00 UTC.
    private val now = 1_800_000_000L
    private val utc: (Long) -> Int = { 0 }

    // ---- Files: +/−, behind, pushed (G1.10) ----

    @Test
    fun a_changed_file_carries_its_line_counts_and_an_older_hubs_does_not() {
        val files = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ChangedFile.serializer()),
            """[{"path":"src/a.kt","status":"M","staged":false,"orig_path":null,"added":12,"removed":3},
                {"path":"logo.png","status":"A","staged":true,"orig_path":null},
                {"path":"new.kt","status":"?","staged":false,"orig_path":null,"added":4}]""",
        )
        assertEquals("+12 −3", lineCounts(files[0]))
        assertNull(lineCounts(files[1]), "a binary file, or an older hub: no counts drawn")
        assertEquals("+4 −0", lineCounts(files[2]))
    }

    @Test
    fun the_branch_line_says_how_far_behind_its_base_it_is() {
        val b = json.decodeFromString(
            BranchDiff.serializer(),
            """{"branch":"feat","upstream":"origin/feat","unpushed":[],"unpushedFiles":[],"truncated":false,
                "base":"origin/main","aheadOfBase":5,"baseFiles":[],"behindBase":2}""",
        )
        assertEquals(2, b.behindBase)
        assertEquals("5 ahead of main · 2 behind main", branchLine(b))
        assertEquals("5 ahead of main", branchLine(b.copy(behindBase = null)), "an older hub says nothing of behind")
        assertEquals("5 ahead of main", branchLine(b.copy(behindBase = 0)))
        assertNull(branchLine(BranchDiff(behindBase = 3)), "no base, no behind")
    }

    @Test
    fun a_commit_says_whether_it_is_pushed() {
        val c = json.decodeFromString(
            CommitDetail.serializer(),
            """{"hash":"abc","subject":"s","body":"","author":"A","date":"2026-10-01",
                "files":[{"path":"x","status":"M","staged":false,"orig_path":null,"added":1,"removed":1}],"pushed":false}""",
        )
        assertEquals("Not pushed", pushedLabel(c))
        assertEquals("Pushed", pushedLabel(c.copy(pushed = true)))
        assertNull(pushedLabel(c.copy(pushed = null)))
        assertEquals("+1 −1", lineCounts(c.files.single()))
    }

    // ---- Details: PR checks ----

    @Test
    fun a_rows_pr_evidence_gives_the_check_count() {
        val row = json.decodeFromString(
            SessionRow.serializer(),
            """{"id":3,"ci_status":"passing","pr_evidence":{"head_oid":"abc","draft":false,
                "checks":{"total":15,"pending":0,"skipped":0,"failing_total":0}}}""",
        )
        assertEquals("15/15 checks", row.prEvidence?.checks?.let(::checksLabel))
        assertEquals("passing · 15/15 checks", ciFact(row))
        assertEquals("pending", ciFact(SessionRow(id = 1, ciStatus = "pending")), "no evidence: the hub's word alone")
        assertNull(ciFact(SessionRow(id = 1)))
    }

    @Test
    fun skipped_checks_do_not_count_and_failing_and_running_ones_are_named() {
        assertEquals(
            "12/15 checks · 2 failing · 1 running",
            checksLabel(CheckSummary(total = 17, pending = 1, skipped = 2, failingTotal = 2)),
        )
        assertNull(checksLabel(CheckSummary()), "no checks is not a pass")
        assertNull(checksLabel(CheckSummary(total = 2, skipped = 2)))
    }

    // ---- Details: the account meter ----

    private fun snapshot(five: UsageWindow?, week: UsageWindow?) =
        AccountUsageSnapshot("acc-a", AccountUsageWindows(fiveHour = five, sevenDay = week), "ok", now)

    @Test
    fun the_account_row_names_the_account_and_what_is_left() {
        val m = assertNotNull(accountMeter("acc-a", "m@work", snapshot(UsageWindow(25.0, now + 100), UsageWindow(40.0, now + 9_000)), now))
        assertEquals("m@work", m.name)
        assertEquals("75% left · week 60% left", m.line)
        assertEquals(0.6f, m.leftFraction)
        assertFalse(m.low)

        val low = assertNotNull(accountMeter("acc-a", null, snapshot(UsageWindow(10.0, now + 100), UsageWindow(85.0, now + 9_000)), now))
        assertEquals("acc-a", low.name, "no name from the hub: the uuid's head")
        assertTrue(low.low, "under 20% left, as the desktop warns")
    }

    @Test
    fun an_account_at_its_limit_says_when_it_resets() {
        val m = assertNotNull(accountMeter("acc-a", "m@work", snapshot(UsageWindow(100.0, now + 7_200), UsageWindow(50.0, now + 90_000)), now))
        assertEquals("At its 5-hour limit · resets in 2 h", m.line)
        assertEquals(0f, m.leftFraction)
        assertTrue(m.low)
    }

    @Test
    fun a_window_past_its_reset_is_whole_again_and_no_reading_draws_no_meter() {
        val m = assertNotNull(accountMeter("acc-a", "m", snapshot(UsageWindow(90.0, now - 1), null), now))
        assertEquals("100% left", m.line)
        val bare = assertNotNull(accountMeter("acc-a", "m", null, now))
        assertNull(bare.line)
        assertNull(bare.leftFraction)
        assertNull(accountMeter(null, "m", null, now), "a row with no account has no Account row")
    }

    // ---- Switch account: the logins it lists ----

    @Test
    fun switch_account_lists_every_login_but_the_sessions_own() {
        val h = Headroom(logins = listOf(HostLogin(null, "acc-b", 10.0), HostLogin("work", "acc-a", 50.0), HostLogin("spare", "acc-a", 5.0)))
        assertEquals(listOf(null, "spare"), otherLogins(h, "work").map { it.profile })
        assertEquals(listOf("work", "spare"), otherLogins(h, null).map { it.profile })
    }

    // ---- Send later's time choices (G1.8) ----

    @Test
    fun an_older_hub_offers_only_when_idle() {
        assertEquals(listOf(SendLaterWhen.Idle), sendLaterChoices(at = false, afterLimit = false, hasAccount = true))
        assertEquals(
            listOf(SendLaterWhen.Idle, SendLaterWhen.InAnHour, SendLaterWhen.Tomorrow, SendLaterWhen.LimitReset, SendLaterWhen.At),
            sendLaterChoices(at = true, afterLimit = true, hasAccount = true),
        )
        assertFalse(SendLaterWhen.LimitReset in sendLaterChoices(at = true, afterLimit = true, hasAccount = false), "no account, no limit to wait for")
    }

    @Test
    fun the_capabilities_read_each_argument_off_queue_prompts_schema() {
        val tools = setOf(HubCapabilities.QUEUE_PROMPT, HubCapabilities.QUEUED_PROMPTS)
        val old = HubCapabilities(tools = tools, params = mapOf(HubCapabilities.QUEUE_PROMPT to setOf("session_id", "prompt")))
        assertTrue(old.sendLater)
        assertFalse(old.sendLaterAt || old.sendLaterAfterLimit || old.sendLaterSkipArchived)
        val new = HubCapabilities(
            tools = tools,
            params = mapOf(HubCapabilities.QUEUE_PROMPT to setOf("session_id", "prompt", "not_before", "until_limit_reset", "skip_if_archived")),
        )
        assertTrue(new.sendLaterAt && new.sendLaterAfterLimit && new.sendLaterSkipArchived)
    }

    @Test
    fun a_typed_time_reads_as_hours_and_minutes() {
        assertEquals(9 to 0, parseClock("9"))
        assertEquals(9 to 30, parseClock(" 09:30 "))
        assertEquals(21 to 5, parseClock("21.05"))
        assertNull(parseClock("24:00"))
        assertNull(parseClock("9:7"))
        assertNull(parseClock("soon"))
    }

    @Test
    fun the_times_are_the_viewers_local_ones() {
        // 08:00 UTC: today's 09:00 is still ahead, today's 07:30 is not.
        assertEquals(1_800_003_600L, nextLocalClock(now, utc, 9, 0))
        assertEquals(1_800_084_600L, nextLocalClock(now, utc, 7, 30))
        assertEquals(1_800_090_000L, tomorrowMorning(now, utc))
        // An hour ahead of UTC it is 09:00 now, so 09:00 is tomorrow's.
        assertEquals(1_800_086_400L, nextLocalClock(now, { 3_600 }, 9, 0))
    }

    @Test
    fun each_choice_sends_its_timing() {
        assertEquals(SendLaterTiming(), sendLaterTiming(SendLaterWhen.Idle, now, utc, "", skipIfArchived = false))
        assertEquals(SendLaterTiming(notBefore = now + 3_600), sendLaterTiming(SendLaterWhen.InAnHour, now, utc, "", false))
        assertEquals(SendLaterTiming(notBefore = 1_800_090_000L, skipIfArchived = true), sendLaterTiming(SendLaterWhen.Tomorrow, now, utc, "", true))
        assertEquals(SendLaterTiming(untilLimitReset = true), sendLaterTiming(SendLaterWhen.LimitReset, now, utc, "", false))
        assertEquals(SendLaterTiming(notBefore = 1_800_003_600L), sendLaterTiming(SendLaterWhen.At, now, utc, "9:00", false))
        assertNull(sendLaterTiming(SendLaterWhen.At, now, utc, "later", false), "an unreadable time sends nothing")
        assertTrue(SendLaterTiming().plain)
        assertFalse(SendLaterTiming(skipIfArchived = true).plain)
    }

    @Test
    fun a_waiting_prompt_says_when_it_goes() {
        val q = QueuedPrompt(id = 1, sessionId = 3, body = "b")
        assertEquals("when idle", queuedWhen(q, now, utc))
        assertEquals("at 09:00", queuedWhen(q.copy(notBefore = 1_800_003_600L), now, utc))
        assertEquals("tomorrow 09:00 · skipped if archived", queuedWhen(q.copy(notBefore = 1_800_090_000L, skipIfArchived = true), now, utc))
        assertEquals("in 3 d", queuedWhen(q.copy(notBefore = now + 3 * 86_400), now, utc))
        assertEquals("after the limit resets", queuedWhen(q.copy(untilLimitReset = true), now, utc))
        assertEquals("when idle", queuedWhen(q.copy(notBefore = now - 10), now, utc), "its time has come")
    }

    @Test
    fun a_queued_prompts_timing_decodes_and_a_skipped_one_no_longer_waits() {
        val q = json.decodeFromString(
            QueuedPrompt.serializer(),
            """{"id":2,"session_id":3,"body":"b","created_at":1,"attempts":0,"not_before":1800003600,
                "until_limit_reset":true,"skip_if_archived":true}""",
        )
        assertEquals(1_800_003_600L, q.notBefore)
        assertTrue(q.untilLimitReset && q.skipIfArchived && q.waiting)
        assertFalse(q.copy(skippedAt = 5).waiting)
        val old = json.decodeFromString(QueuedPrompt.serializer(), """{"id":2,"session_id":3,"body":"b"}""")
        assertNull(old.notBefore)
        assertFalse(old.untilLimitReset)
    }
}
