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
            listOf("WAITING", "STUCK", "FAILED", "DONE_UNREAD", "LIFECYCLE", "IDLE_LONG", "WORKING", "IDLE"),
            TriageBucket.entries.map { it.name },
        )
    }

    @Test
    fun blocked_outranks_stuck_outranks_failed() {
        assertEquals(TriageBucket.WAITING, row(claudeStatus = "blocked", stuckKind = "oom").triageBucket())
        assertEquals(TriageBucket.STUCK, row(claudeStatus = "failed", stuckKind = "oom").triageBucket())
        assertEquals(TriageBucket.FAILED, row(claudeStatus = "failed").triageBucket())
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
}
