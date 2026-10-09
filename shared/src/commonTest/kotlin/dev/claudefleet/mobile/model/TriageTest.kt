package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val NOW = 1_700_000_000L

private fun row(
    id: Long = 1,
    kind: String? = "work",
    claudeStatus: String? = null,
    stuckKind: String? = null,
    status: String = "running",
    safeKillState: String? = null,
    lostAt: Long? = null,
    lastActivityAt: Long? = NOW,
    attention: Attention? = null,
) = SessionRow(
    id = id,
    tmuxName = "s$id",
    kind = kind,
    status = status,
    claudeStatus = claudeStatus,
    stuckKind = stuckKind,
    safeKillState = safeKillState,
    lostAt = lostAt,
    lastActivityAt = lastActivityAt,
    attention = attention,
)

/**
 * The desktop's triage, ported. Its `attention.ts` is the reference; these
 * cases follow its `classify` order check for check, because the two clients
 * disagreeing about which session is worst is exactly the drift the shared
 * bucket list exists to prevent.
 */
class TriageTest {

    @Test
    fun the_bucket_order_is_the_desktops_bucket_order() {
        assertEquals(
            listOf(
                "WAITING", "STUCK", "HOST_DOWN", "ACCOUNT_LIMIT", "NO_CREDENTIALS", "STOP_FAILED", "FAILED", "CONTEXT_FULL", "STALE_WORKING", "CI_FAILING",
                "DONE_UNREAD", "LIFECYCLE", "IDLE_LONG", "WORKING", "IDLE",
            ),
            TriageBucket.entries.map { it.name },
        )
    }

    @Test
    fun blocked_outranks_stuck_outranks_failed() {
        assertEquals(TriageBucket.WAITING, row(claudeStatus = "blocked", stuckKind = "oom").triageBucket())
        assertEquals(TriageBucket.STUCK, row(claudeStatus = "failed", stuckKind = "oom").triageBucket())
        // A turn that failed is a failed stop; only a background agent's is a plain failure.
        assertEquals(TriageBucket.STOP_FAILED, row(claudeStatus = "failed").triageBucket())
        assertEquals(TriageBucket.FAILED, row(claudeStatus = "failed", kind = "bg").triageBucket())
    }

    @Test
    fun the_hubs_reason_decides_the_bucket() {
        // Fields the phone never receives (stale_working_at, idle_since) decide
        // these on the hub; its stamped reason is the answer.
        val stalled = row(claudeStatus = "working").copy(attention = Attention("stale_working"))
        assertEquals(TriageBucket.STALE_WORKING, stalled.triageBucket())
        val ci = row(claudeStatus = "idle").copy(attention = Attention("ci_failing"))
        assertEquals(TriageBucket.CI_FAILING, ci.triageBucket())
        assertTrue(ci.triageScore(now = NOW) > row(claudeStatus = "working").triageScore(now = NOW))
        // An external session is never a person's job, whatever is stamped.
        val external = row(kind = "external", claudeStatus = "idle").copy(attention = Attention("ci_failing"))
        assertEquals(TriageBucket.IDLE, external.triageBucket())
    }

    /** Contract 11: the hub's three Blocked reasons rank right after Stuck and ask for a person. */
    @Test
    fun the_blocked_reasons_rank_after_stuck_and_need_you() {
        val cases = mapOf(
            "host_down" to TriageBucket.HOST_DOWN,
            "account_limit" to TriageBucket.ACCOUNT_LIMIT,
            "no_credentials" to TriageBucket.NO_CREDENTIALS,
        )
        for ((reason, bucket) in cases) {
            val r = row(claudeStatus = "idle").copy(attention = Attention(reason))
            assertEquals(bucket, r.triageBucket())
            assertTrue(bucket.needsYou)
            assertTrue(r.triageScore(now = NOW) < row(stuckKind = "oom").triageScore(now = NOW))
            assertTrue(r.triageScore(now = NOW) > row(claudeStatus = "failed").triageScore(now = NOW))
        }
        assertEquals("Paused · limit", reasonLabel("account_limit"))
        assertEquals("Host down", reasonLabel("host_down"))
        assertEquals("Signed out", reasonLabel("no_credentials"))
    }

    @Test
    fun without_a_stamp_the_local_rules_find_a_full_context_and_failing_ci() {
        assertEquals(TriageBucket.CONTEXT_FULL, row(claudeStatus = "working").copy(contextPct = 90.0).triageBucket())
        assertEquals(TriageBucket.CI_FAILING, row(claudeStatus = "idle").copy(ciStatus = "failing").triageBucket())
        assertEquals(TriageBucket.WORKING, row(claudeStatus = "working").copy(ciStatus = "failing").triageBucket())
    }

    @Test
    fun a_broken_lifecycle_is_its_own_bucket_however_it_broke() {
        assertEquals(TriageBucket.LIFECYCLE, row(safeKillState = "failed").triageBucket())
        assertEquals(TriageBucket.LIFECYCLE, row(safeKillState = "requested").triageBucket())
        assertEquals(TriageBucket.LIFECYCLE, row(status = "ghost").triageBucket())
        assertEquals(TriageBucket.LIFECYCLE, row(lostAt = NOW - 10).triageBucket())
        // A safe-kill that is merely done is not broken.
        assertEquals(TriageBucket.IDLE, row(safeKillState = "completed").triageBucket())
    }

    /**
     * A session running outside fleet is read-only from the phone, so it can
     * never be something a person is asked to deal with — whatever its fields
     * say. The desktop's rule, and the same one `attentionReason` already
     * applies.
     */
    @Test
    fun an_external_session_is_never_in_a_needs_you_bucket() {
        for (s in listOf("blocked", "failed", null)) {
            assertEquals(TriageBucket.IDLE, row(kind = "external", claudeStatus = s, stuckKind = "oom").triageBucket())
        }
        assertEquals(TriageBucket.WORKING, row(kind = "external", claudeStatus = "working").triageBucket())
    }

    @Test
    fun idle_long_is_off_until_a_threshold_is_given_and_only_covers_work_and_review() {
        val stale = row(lastActivityAt = NOW - 7_200)
        assertEquals(TriageBucket.IDLE, stale.triageBucket(idleSeconds = 0, now = NOW))
        assertEquals(TriageBucket.IDLE_LONG, stale.triageBucket(idleSeconds = 3_600, now = NOW))
        // Exactly on the threshold counts — the desktop's `>=`.
        assertEquals(TriageBucket.IDLE_LONG, stale.triageBucket(idleSeconds = 7_200, now = NOW))
        assertEquals(TriageBucket.IDLE, stale.triageBucket(idleSeconds = 7_201, now = NOW))
        // A shell session is not work nobody is doing.
        assertEquals(TriageBucket.IDLE, row(kind = "shell", lastActivityAt = NOW - 7_200).triageBucket(3_600, NOW))
    }

    /** The cap is what stops a long wait promoting a row out of its bucket. */
    @Test
    fun no_wait_however_long_lets_a_row_jump_its_bucket() {
        val ancientIdle = row(id = 1, claudeStatus = "idle", lastActivityAt = NOW - 5_000_000)
        val freshBlocked = row(id = 2, claudeStatus = "blocked", lastActivityAt = NOW)
        assertTrue(freshBlocked.triageScore(now = NOW) > ancientIdle.triageScore(now = NOW))
    }

    @Test
    fun inside_a_bucket_the_longest_wait_comes_first() {
        val older = row(id = 1, claudeStatus = "blocked", lastActivityAt = NOW - 900)
        val newer = row(id = 2, claudeStatus = "blocked", lastActivityAt = NOW - 60)
        assertEquals(listOf(1L, 2L), listOf(newer, older).byTriage(now = NOW).map { it.id })
    }

    /**
     * The hub stamps when it decided a row wanted a person; the phone has no
     * `stuck_since` column to read, so that stamp is what ages a stuck row.
     */
    @Test
    fun the_hubs_attention_stamp_ages_a_row_ahead_of_its_last_activity() {
        val stamped = row(
            id = 1,
            stuckKind = "oom",
            lastActivityAt = NOW - 10,
            attention = Attention(reason = "stuck", since = NOW - 5_000),
        )
        val unstamped = row(id = 2, stuckKind = "oom", lastActivityAt = NOW - 10)
        assertEquals(listOf(1L, 2L), listOf(unstamped, stamped).byTriage(now = NOW).map { it.id })
    }

    /**
     * An unstamped row is one nothing is known about. Scoring it as the
     * *oldest* of its bucket would float "unknown" above every real wait at
     * the top of the queue, so it scores as the youngest instead.
     */
    @Test
    fun a_row_with_no_stamp_at_all_sorts_last_within_its_bucket() {
        val known = row(id = 1, claudeStatus = "blocked", lastActivityAt = NOW - 30)
        val unknown = row(id = 2, claudeStatus = "blocked", lastActivityAt = null)
        assertEquals(listOf(1L, 2L), listOf(unknown, known).byTriage(now = NOW).map { it.id })
    }

    /**
     * The queue is rebuilt on every event frame, so an unstable sort would
     * reshuffle equal rows under a thumb. The id is the tie-break that stops
     * it — and it is checked from both input orders, because a comparator that
     * merely happens to preserve input order passes the one-way version.
     */
    @Test
    fun rows_that_tie_are_ordered_by_id_from_either_starting_order() {
        val a = row(id = 7, claudeStatus = "working", lastActivityAt = NOW)
        val b = row(id = 3, claudeStatus = "working", lastActivityAt = NOW)
        assertEquals(listOf(3L, 7L), listOf(a, b).byTriage(now = NOW).map { it.id })
        assertEquals(listOf(3L, 7L), listOf(b, a).byTriage(now = NOW).map { it.id })
    }

    @Test
    fun a_whole_fleet_ranks_worst_first() {
        val rows = listOf(
            row(id = 1, claudeStatus = "working"),
            row(id = 2, claudeStatus = "idle"),
            row(id = 3, claudeStatus = "failed"),
            row(id = 4, stuckKind = "auth_menu"),
            row(id = 5, claudeStatus = "blocked"),
            row(id = 6, status = "ghost"),
        )
        assertEquals(listOf(5L, 4L, 3L, 6L, 1L, 2L), rows.byTriage(now = NOW).map { it.id })
    }

    // ---- Done · unread (contract 11's last_viewed_at, redesign 2.7) ----

    @Test
    fun a_turn_that_ended_after_the_last_look_is_done_unread() {
        val unread = row(claudeStatus = "idle").copy(startedAt = NOW - 900, lastStopAt = NOW - 60, lastViewedAt = NOW - 300)
        assertEquals(TriageBucket.DONE_UNREAD, unread.triageBucket())
        assertEquals("Done · unread", TriageBucket.DONE_UNREAD.label)
        // Seen after the turn ended: plain idle.
        assertEquals(TriageBucket.IDLE, unread.copy(lastViewedAt = NOW - 10).triageBucket())
        // Viewed in the very second the turn ended counts as seen.
        assertEquals(TriageBucket.IDLE, unread.copy(lastViewedAt = NOW - 60).triageBucket())
    }

    @Test
    fun a_session_nobody_opened_counts_from_its_start_and_a_found_one_is_never_unread() {
        val started = row(claudeStatus = "completed").copy(startedAt = NOW - 900, lastStopAt = NOW - 60)
        assertEquals(TriageBucket.DONE_UNREAD, started.triageBucket(), "fleet started it and nobody has looked")
        val found = row(claudeStatus = "idle").copy(lastStopAt = NOW - 60)
        assertEquals(TriageBucket.IDLE, found.triageBucket(), "reconcile found it: neither stamp, never unread")
    }

    @Test
    fun done_unread_is_only_for_a_live_idle_row_and_ranks_where_the_desktop_ranks_it() {
        val base = row().copy(startedAt = NOW - 900, lastStopAt = NOW - 60)
        assertEquals(TriageBucket.WORKING, base.copy(claudeStatus = "working").triageBucket(), "a working row is working")
        assertEquals(TriageBucket.LIFECYCLE, base.copy(claudeStatus = "idle", status = "ghost").triageBucket())
        assertEquals(TriageBucket.CI_FAILING, base.copy(claudeStatus = "idle", ciStatus = "failing").triageBucket())
        assertTrue(TriageBucket.DONE_UNREAD.needsYou, "the Needs you filter shows it, as the desktop's does")
        assertEquals(TriageBucket.DONE_UNREAD, base.copy(claudeStatus = "idle", attention = Attention("done_unread")).triageBucket())
    }

    @Test
    fun bucket_labels_are_the_six_status_words() {
        val words = setOf("Needs you", "Working", "Failed", "Done", "Paused", "Idle")
        // Status labels: one of the words, optionally followed by " · " and the reason.
        for (b in listOf(
            TriageBucket.WAITING, TriageBucket.STUCK, TriageBucket.FAILED, TriageBucket.DONE_UNREAD,
            TriageBucket.LIFECYCLE, TriageBucket.IDLE_LONG, TriageBucket.WORKING, TriageBucket.IDLE, TriageBucket.ACCOUNT_LIMIT,
        )) {
            assertTrue(b.label.substringBefore(" · ") in words, "${b.name} reads ${b.label}")
        }
        assertEquals("Needs you", reasonLabel("waiting"))
        assertEquals("Failed · stuck", reasonLabel("stuck"))
        assertEquals("Paused", reasonLabel("lifecycle"))
    }

    @Test
    fun origin_and_last_viewed_are_read_off_the_wire() {
        val r = dev.claudefleet.mobile.net.json.decodeFromString(
            SessionRow.serializer(),
            """{"id":4,"tmux_name":"s","origin":"routine","origin_ref":"12","last_viewed_at":1700000000,"claude_profile":"work"}""",
        )
        assertEquals("routine", r.origin)
        assertEquals("12", r.originRef)
        assertEquals(1_700_000_000L, r.lastViewedAt)
        assertEquals("work", r.claudeProfile)
        val old = dev.claudefleet.mobile.net.json.decodeFromString(SessionRow.serializer(), """{"id":4,"tmux_name":"s"}""")
        assertEquals(null, old.origin, "an older hub sends none")
    }
}
