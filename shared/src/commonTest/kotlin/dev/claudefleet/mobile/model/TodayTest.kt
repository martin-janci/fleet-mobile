package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Today digest's pure half on the phone (claude-fleet M9.1): the hub's
 * wire shape, scoping by org, and the standup text. The cases carry the
 * desktop's `today.test.ts` names and fixture, so the same digest copies the
 * same text from either.
 */
class TodayTest {
    private fun s(id: Long, name: String, attention: String? = null, stale: String? = null, orgId: Long? = null, prUrl: String? = null, ciStatus: String? = null) =
        TodaySession(id = id, name = name, hostAlias = "mefistos", lastActivityAt = 100, orgId = orgId, attention = attention, stale = stale, prUrl = prUrl, ciStatus = ciStatus)

    private val digest = Today(
        since = 0,
        now = 200,
        groups = listOf(
            TodayGroup(
                bucket = "waiting",
                key = "PAY-7",
                title = "Refund flow",
                statusName = "In Progress",
                sessions = listOf(s(1, "pay", attention = "waiting"), s(2, "pay-tests", orgId = 2)),
            ),
            TodayGroup(bucket = "in_progress", key = "PAY-9", title = "Ledger", sessions = listOf(s(3, "ledger", prUrl = "https://gh/pr/9", ciStatus = "passing"))),
            TodayGroup(bucket = "stale", key = "OLD-1", title = "", sessions = listOf(s(4, "old", stale = "idle"))),
            TodayGroup(bucket = "in_progress", sessions = listOf(s(5, "scratch"))),
        ),
        shipped = listOf(
            TodayShipped(how = "done", key = "PAY-3", title = "Receipts", url = "https://x/PAY-3", prUrl = "https://gh/pr/3", at = 150, orgId = 1),
            TodayShipped(how = "pr", key = "ENG-2", title = "Other", prUrl = "https://gh/pr/4", at = 140, orgId = 2),
        ),
    )

    /** The live rows' orgs, as the desktop fixture has them. */
    private val rowOrg = mapOf(1L to 1L, 2L to 2L, 3L to 1L, 4L to 1L, 5L to 1L)
    private val orgOf: (TodaySession) -> Long? = { rowOrg[it.id] ?: it.orgId }

    @Test
    fun bucketOf_waiting_beats_stale_beats_in_progress_as_on_the_hub() {
        assertEquals(TodayBucket.Waiting, bucketOf(listOf(s(1, "a", attention = "stuck"), s(2, "b", stale = "idle"))))
        assertEquals(TodayBucket.Stale, bucketOf(listOf(s(1, "a", stale = "idle"), s(2, "b", stale = "done"))))
        assertEquals(TodayBucket.InProgress, bucketOf(listOf(s(1, "a", stale = "idle"), s(2, "b"))))
        assertEquals(TodayBucket.InProgress, bucketOf(emptyList()))
    }

    @Test
    fun scopeToday_all_every_group_in_its_bucket_every_shipped_entry() {
        val v = scopeToday(digest, null, orgOf)
        assertEquals(listOf("PAY-7"), v.waiting.map { it.key })
        assertEquals(listOf("PAY-9", null), v.inProgress.map { it.key })
        assertEquals(listOf("OLD-1"), v.stale.map { it.key })
        assertEquals(2, v.shipped.size)
    }

    @Test
    fun scopeToday_a_scope_drops_the_other_orgs_sessions_and_re_buckets_what_is_left() {
        val v = scopeToday(digest, 2, orgOf)
        // PAY-7 waited because of session 1 (org 1); in org 2 only pay-tests is left.
        assertEquals(emptyList(), v.waiting)
        assertEquals(listOf("PAY-7"), v.inProgress.map { it.key })
        assertEquals(listOf(2L), v.inProgress[0].sessions.map { it.id })
        assertEquals(listOf("ENG-2"), v.shipped.map { it.key })
    }

    /**
     * The one departure from the desktop: a session the phone has no live row
     * for yet (its frame has not arrived) keeps the digest's own `org_id`
     * rather than being dropped from every org. With neither, it is in none.
     */
    @Test
    fun scopeToday_a_session_with_no_live_row_falls_back_to_the_digests_org() {
        val noRow: (TodaySession) -> Long? = { if (it.id == 2L || it.id == 3L) it.orgId else rowOrg[it.id] }
        val v = scopeToday(digest, 1, noRow)
        // ledger (3) has no row and no org of its own: dropped; scratch stays.
        assertEquals(listOf(null), v.inProgress.map { it.key })
        // pay-tests (2) has no row but carries org 2.
        assertEquals(listOf(2L), scopeToday(digest, 2, noRow).inProgress.flatMap { g -> g.sessions.map { it.id } })
    }

    @Test
    fun standupText_is_plain_text_in_a_stable_order_shipped_in_progress_waiting_on_me_stale() {
        assertEquals(
            listOf(
                "Shipped",
                "- PAY-3 Receipts — done https://gh/pr/3",
                "- ENG-2 Other — PR https://gh/pr/4",
                "",
                "In progress",
                "- PAY-9 Ledger — PR https://gh/pr/9 (CI passing) · ledger",
                "- scratch",
                "",
                "Waiting on me",
                "- PAY-7 Refund flow — In Progress · pay (waiting for an answer), pay-tests",
                "",
                "Stale",
                "- OLD-1 — old (idle)",
                "",
            ).joinToString("\n"),
            standupText(scopeToday(digest, null, orgOf)),
        )
    }

    @Test
    fun standupText_says_so_when_there_is_nothing() {
        val empty = scopeToday(Today(since = 0, now = 1), null, orgOf)
        assertTrue(empty.isEmpty)
        assertEquals("Nothing to report.\n", standupText(empty))
    }

    @Test
    fun standupText_keeps_markup_in_a_tracker_title_as_the_text_it_is() {
        val t = Today(since = 0, now = 1, shipped = listOf(TodayShipped(how = "done", key = "X-1", title = "<b>bold</b>", at = 1)))
        assertTrue("- X-1 <b>bold</b> — done" in standupText(scopeToday(t, null, orgOf)))
    }

    @Test
    fun sessionPhrase_says_why_a_session_is_listed_only_in_its_own_bucket() {
        assertEquals("pay (stuck)", sessionPhrase(s(1, "pay", attention = "stuck"), TodayBucket.Waiting))
        assertEquals("pay (lifecycle-x)", sessionPhrase(s(1, "pay", attention = "lifecycle-x"), TodayBucket.Waiting))
        assertEquals("pay (ticket done, session still running)", sessionPhrase(s(1, "pay", stale = "done"), TodayBucket.Stale))
        assertEquals("pay", sessionPhrase(s(1, "pay", attention = "stuck"), TodayBucket.InProgress))
    }

    @Test
    fun localMidnight_is_the_local_start_of_the_day_in_seconds() {
        // 2026-09-25 12:30 UTC.
        val noonUtc = 1_790_339_400L
        val midnightUtc = 1_790_294_400L
        assertEquals(midnightUtc, localMidnight(noonUtc, 0))
        // Two hours ahead: local 14:30, local midnight is 22:00 UTC the day before.
        assertEquals(midnightUtc - 2 * 3600, localMidnight(noonUtc, 2 * 3600))
        // Ten hours behind: local 02:30 on the 25th.
        assertEquals(midnightUtc + 10 * 3600, localMidnight(noonUtc, -10 * 3600))
        // Twelve hours behind: local 00:30 on the 25th — still the 25th.
        assertEquals(midnightUtc + 12 * 3600, localMidnight(noonUtc, -12 * 3600))
        // At local midnight exactly, that same second.
        assertEquals(midnightUtc, localMidnight(midnightUtc, 0))
        // One second before it: the day before.
        assertEquals(midnightUtc - 86_400, localMidnight(midnightUtc - 1, 0))
    }

    /** The hub's own wire shape (`today.rs`, nulls stripped), read whole. */
    @Test
    fun the_hubs_digest_parses() {
        val t = json.decodeFromString(
            Today.serializer(),
            """{"since":10,"now":20,"groups":[{"bucket":"waiting","key":"PAY-7","item_id":70,"title":"Refund",
               "status_category":"in_progress","status_name":"In Review","org_id":2,
               "sessions":[{"id":5,"name":"pay","host_alias":"pine","last_activity_at":15,"attention":"stuck",
               "claude_status":"idle","pr_url":"https://gh/pr/1","ci_status":"failing","org_id":2}]},
               {"bucket":"in_progress","title":"","sessions":[{"id":6,"name":"scratch","host_alias":"pine","last_activity_at":16}]}],
               "shipped":[{"how":"pr","title":"Other","pr_url":"https://gh/pr/4","at":18}],"future_field":1}""",
        )
        assertEquals(2, t.groups.size)
        assertEquals(StatusCategory.InProgress, t.groups[0].statusCategory)
        assertEquals("stuck", t.groups[0].sessions[0].attention)
        assertEquals(2L, t.groups[0].sessions[0].orgId)
        assertEquals(null, t.groups[1].key)
        assertEquals("pr", t.shipped[0].how)
    }

    // --- The sheet's own filters (phone only; the desktop has no copy). ---

    private fun hs(id: Long, name: String, host: String, attention: String? = null, stale: String? = null, at: Long = 0) =
        TodaySession(id = id, name = name, hostAlias = host, lastActivityAt = at, attention = attention, stale = stale)

    /** In the hub's own order, which is the order the sheet lists. */
    private val hosted = TodayView(
        waiting = listOf(TodayGroup(key = "PD-1", title = "Pay", sessions = listOf(hs(1, "a", "mac", attention = "ci_failing", at = 90), hs(2, "b", "trn", at = 80)))),
        inProgress = listOf(
            TodayGroup(key = "PD-2", title = "Ship", sessions = listOf(hs(3, "c", "trn", at = 70))),
            TodayGroup(key = null, sessions = listOf(hs(4, "d", "mac", at = 60))),
        ),
        shipped = listOf(TodayShipped(how = "done", key = "PD-0", title = "Done")),
        stale = listOf(TodayGroup(key = "PD-3", sessions = listOf(hs(5, "e", "trn", stale = "idle", at = 50)))),
    )

    @Test
    fun no_filter_is_the_view_itself() {
        assertEquals(hosted, filterToday(hosted, TodayFilters()))
        assertTrue(!TodayFilters().any)
    }

    /** The host drops `a`, the one needing a person, so PD-1 is in progress on `trn` — the hub's rule again. */
    @Test
    fun a_host_filter_re_buckets_what_is_left_and_keeps_shipped() {
        val v = filterToday(hosted, TodayFilters(host = "trn"))
        assertEquals(emptyList(), v.waiting)
        assertEquals(listOf("PD-1", "PD-2"), v.inProgress.map { it.key })
        assertEquals(listOf(listOf(2L)), v.inProgress.take(1).map { g -> g.sessions.map { it.id } })
        assertEquals(listOf("PD-3"), v.stale.map { it.key })
        assertEquals(1, v.shipped.size, "shipped names no host; the filter cannot judge it")
    }

    @Test
    fun section_chips_or_together_and_tickets_only_drops_the_no_work_group() {
        val v = filterToday(hosted, TodayFilters(sections = setOf(TodaySection.Waiting, TodaySection.Shipped)))
        assertEquals(listOf("PD-1"), v.waiting.map { it.key })
        assertEquals(emptyList(), v.inProgress)
        assertEquals(emptyList(), v.stale)
        assertEquals(1, v.shipped.size)

        val t = filterToday(hosted, TodayFilters(ticketsOnly = true))
        assertEquals(listOf("PD-2"), t.inProgress.map { it.key })
        assertTrue(hosted.hasUnlinked)
        assertTrue(!t.hasUnlinked)
    }

    /**
     * A filter narrows the list; it does not reshuffle what is left.
     *
     * `filterToday` re-buckets (the host filter can take away the session that
     * made a group waiting) by walking `waiting + inProgress + stale` and
     * appending, so a group that changed bucket landed at the END of its new
     * section and the order depended on which section it used to be in. The
     * hub's own position, stamped by `scopeToday`, puts it back.
     *
     * The order itself is NOT the sheet's to choose: the first attempt sorted
     * by newest activity and broke `StandupFixtureTest`, because the unfiltered
     * order is a cross-repo contract with the desktop's standup text.
     */
    @Test
    fun a_filter_does_not_reshuffle_what_is_left() {
        // The hub's order is A, B, C. The host filter takes C's waiting session
        // away, so C becomes in-progress — and must land between A and nothing,
        // at its own place, not appended after B.
        val digest = Today(
            groups = listOf(
                TodayGroup(bucket = "in_progress", key = "A", sessions = listOf(hs(1, "a", "trn"))),
                TodayGroup(bucket = "waiting", key = "B", sessions = listOf(hs(2, "b", "trn", attention = "waiting"))),
                TodayGroup(bucket = "waiting", key = "C", sessions = listOf(hs(3, "c", "mac", attention = "waiting"), hs(4, "d", "trn"))),
            ),
        )
        val v = scopeToday(digest, null) { null }
        assertEquals(listOf("B", "C"), v.waiting.map { it.key }, "setup: the hub's order")
        assertEquals(listOf("A"), v.inProgress.map { it.key })

        val f = filterToday(v, TodayFilters(host = "trn"))
        assertEquals(listOf("B"), f.waiting.map { it.key })
        assertEquals(
            listOf("A", "C"),
            f.inProgress.map { it.key },
            "C re-buckets to its own place in the hub's order, not to the end",
        )
    }

    /**
     * The host filter and the section chips together, which were only ever
     * tested apart: a group the host filter moves into a section the chips keep
     * is kept, and one it moves OUT of a kept section is gone.
     */
    @Test
    fun the_host_filter_and_the_section_chips_compose() {
        // PD-1 is waiting only because of `a` on mac; on trn it is in progress.
        val waitingOnly = filterToday(hosted, TodayFilters(sections = setOf(TodaySection.Waiting), host = "trn"))
        assertEquals(emptyList(), waitingOnly.waiting, "nothing is waiting on trn")
        assertEquals(emptyList(), waitingOnly.inProgress, "and the chips keep Waiting only")

        val inProgressOnly = filterToday(hosted, TodayFilters(sections = setOf(TodaySection.InProgress), host = "trn"))
        assertEquals(listOf("PD-1", "PD-2"), inProgressOnly.inProgress.map { it.key })
        assertEquals(emptyList(), inProgressOnly.shipped, "Shipped is not among the kept sections")

        // Tickets-only on top of both.
        val linked = filterToday(hosted, TodayFilters(sections = setOf(TodaySection.InProgress), host = "mac", ticketsOnly = true))
        assertEquals(emptyList(), linked.inProgress, "mac's only in-progress group is the unlinked one")
    }

    @Test
    fun counts_hosts_and_sessions_of_a_view() {
        assertEquals(listOf(1, 2, 1, 1), TodaySection.entries.map { hosted.count(it) })
        assertEquals(listOf("mac", "trn"), todayHosts(hosted))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), hosted.sessions.map { it.id })
    }

    /** The screenshot that started this: `(ci_failing)` on screen. A wire token never reaches the sheet. */
    @Test
    fun every_attention_reason_the_hub_sends_has_words() {
        val hub = listOf("waiting", "stuck", "stop_failed", "failed", "context_full", "stale_working", "ci_failing", "lifecycle")
        for (r in hub) {
            val label = attentionLabel(r)
            assertTrue('_' !in label && label.first().isUpperCase(), "$r reads as $label")
        }
        assertEquals("CI failing", attentionLabel("ci_failing"))
        assertEquals("Context full", attentionLabel("context_full"))
        assertEquals("Some new reason", attentionLabel("some_new_reason"))
        assertEquals("Ticket done", staleLabel("done"))
        assertEquals("Idle", staleLabel("idle"))
    }

    /** The standup keeps the desktop's words: the sheet's labels are the sheet's alone. */
    @Test
    fun the_standup_still_says_what_the_desktop_says() {
        assertEquals("a (ci_failing)", sessionPhrase(hs(1, "a", "mac", attention = "ci_failing"), TodayBucket.Waiting))
    }
}
