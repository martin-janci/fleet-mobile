@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.BatchResult
import dev.claudefleet.mobile.model.DecisionResult
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.WorkSummary
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.ToolCatalog
import dev.claudefleet.mobile.model.ReviewKind
import dev.claudefleet.mobile.model.ReviewPage
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val INBOX: ReviewPage = json.decodeFromString(ReviewPage.serializer(), WorkTreeJson.REVIEW)

/** Two suggestions on screen, so a batch has something to send. */
private val TWO_SUGGESTIONS: ReviewPage = INBOX.copy(
    items = INBOX.items.take(2) + INBOX.items[0].copy(reviewId = "link:46", linkId = 46, linkVersion = 1, sessionId = 8),
)

private fun TestScope.reviewVm(
    actions: FakeWorkActions = FakeWorkActions().apply { reviewAnswer = INBOX },
    canWrite: Boolean = true,
    fleet: WorkFleet = WorkFleet(),
    changed: MutableList<Unit> = mutableListOf(),
) = ReviewViewModel(fleet, actions, backgroundScope, canWrite, onChanged = { changed += Unit }, clock = { 1_790_000_000 }, utcOffset = { 0 })

/** Session 7's row, carrying [work] as its primary (or none). */
private fun row7(work: WorkSummary? = null) = SessionRow(id = 7, tmuxName = "api", hostAlias = "mefistos", work = work)

private val SOME_WORK = WorkSummary(linkId = 99, key = "OPS-1", state = "confirmed")

class ReviewViewModelTest {

    @Test
    fun it_opens_on_the_inbox_with_each_cards_reason() = runTest {
        val vm = reviewVm()
        vm.open()
        runCurrent()

        val s = vm.state.value
        assertTrue(s.open && s.loaded)
        assertEquals(3, s.total)
        assertEquals(listOf(ReviewKind.Suggestion, ReviewKind.CrossOrg, ReviewKind.Unknown), s.items.map { it.kind })
        assertEquals(listOf("branch abc-12-login since 09:05 · R3"), s.items[0].why)
        assertTrue(s.canConfirm && s.canReject && s.canKeep && s.canChange)
    }

    /**
     * Confirm sends the version it was read at (as a one-item batch, whose
     * answer is the link's new version); Undo sends it back to a suggestion
     * under exactly that version.
     */
    @Test
    fun confirm_carries_the_version_and_undo_reconsiders_it_under_the_answered_version() = runTest {
        val changed = mutableListOf<Unit>()
        val actions = FakeWorkActions().apply { reviewAnswer = INBOX }
        val vm = reviewVm(actions = actions, changed = changed, fleet = WorkFleet(listOf(row7(SOME_WORK))))
        vm.open()
        runCurrent()

        vm.confirm(vm.state.value.items[0])
        runCurrent()
        assertEquals("Confirmed ABC-12", vm.state.value.undo?.label)
        assertEquals(3L, vm.state.value.undo?.version, "what the hub answered, not what was read")
        assertTrue(vm.state.value.canUndo)

        vm.undo()
        runCurrent()

        assertEquals(listOf("decide_batch confirm:42@2", "reconsider 7 42"), actions.calls)
        assertEquals(listOf("reconsider 7 42 v=3"), actions.workArgs)
        assertNull(vm.state.value.undo, "an undo is not itself undone")
        assertEquals(2, changed.size, "My work's count follows every decision")
        assertEquals(3, actions.reviewCalls, "the inbox is re-read after each")
    }

    /** Reject's Undo, too, goes under the version the reject answered. */
    @Test
    fun undo_of_a_reject_carries_the_rejections_version() = runTest {
        val actions = FakeWorkActions().apply { reviewAnswer = INBOX }
        val vm = reviewVm(actions = actions)
        vm.open()
        runCurrent()

        vm.reject(vm.state.value.items[0])
        runCurrent()
        vm.undo()
        runCurrent()

        assertEquals(listOf("decide_batch reject:42@2", "reconsider 7 42"), actions.calls)
        assertEquals(listOf("reconsider 7 42 v=3"), actions.workArgs)
    }

    /** A hub without `decide_batch`: the single call, and no Undo — there is no version to send it under. */
    @Test
    fun without_decide_batch_a_decision_offers_no_undo() = runTest {
        val caps = HubCapabilities.of(
            ToolCatalog(setOf("work", "work_link"), mapOf("work_link" to setOf("confirm", "reject", "link", "reconsider", "unlink", "ack", "set_primary"))),
        )
        val actions = FakeWorkActions().apply { reviewAnswer = INBOX }
        val vm = reviewVm(actions = actions, fleet = WorkFleet(listOf(row7(SOME_WORK)), caps))
        vm.open()
        runCurrent()

        vm.confirm(vm.state.value.items[0])
        runCurrent()

        assertEquals(listOf("confirm 7 42"), actions.calls)
        assertEquals(listOf("confirm 7 42 primary=false v=2"), actions.workArgs)
        assertNull(vm.state.value.undo)
    }

    /**
     * The primary is never taken: Confirm says `primary: true` only for a
     * session with none — its row carries no work, or the inbox lists it as
     * *no primary* — and `false` otherwise, including for a session this
     * phone has no row for.
     */
    @Test
    fun confirm_takes_the_primary_only_from_a_session_without_one() = runTest {
        val suggestion = INBOX.items[0]
        val noPrimaryCard = suggestion.copy(reviewId = "session:7", kind = ReviewKind.NoPrimary, linkId = 60)
        val cases = listOf(
            Triple(WorkFleet(listOf(row7(SOME_WORK))), INBOX, false),
            Triple(WorkFleet(listOf(row7(null))), INBOX, true),
            Triple(WorkFleet(), INBOX, false),
            Triple(WorkFleet(listOf(row7(SOME_WORK))), INBOX.copy(items = INBOX.items + noPrimaryCard), true),
        )
        for ((fleet, inbox, expected) in cases) {
            val actions = FakeWorkActions().apply { reviewAnswer = inbox }
            val vm = reviewVm(actions = actions, fleet = fleet)
            vm.open()
            runCurrent()
            vm.confirm(vm.state.value.items[0])
            runCurrent()
            assertEquals(expected, actions.batches.single().single().primary, "rows=${fleet.sessions.value} kinds=${inbox.items.map { it.kind }}")
        }
    }

    /** In a batch, a session without a primary gets one — its first decision — and every other confirm is secondary. */
    @Test
    fun a_batch_gives_a_session_without_a_primary_exactly_one() = runTest {
        val second = INBOX.items[0].copy(reviewId = "link:47", linkId = 47, linkVersion = 4)
        val other = INBOX.items[0].copy(reviewId = "link:48", linkId = 48, linkVersion = 1, sessionId = 8)
        val inbox = INBOX.copy(items = listOf(INBOX.items[0], second, other, INBOX.items[1]))
        val fleet = WorkFleet(listOf(row7(null), SessionRow(id = 8, tmuxName = "web", work = SOME_WORK)))
        val actions = FakeWorkActions().apply { reviewAnswer = inbox }
        val vm = reviewVm(actions = actions, fleet = fleet)
        vm.open()
        runCurrent()

        vm.confirmAllShown()
        runCurrent()

        assertEquals(
            listOf(42L to true, 47L to false, 48L to false),
            actions.batches.single().map { it.linkId to it.primary },
        )
        assertEquals(
            listOf(true, false),
            batchConfirmDecisions(listOf(INBOX.items[0], second)) { true }.map { it.primary },
        )
    }

    /** Offline: the cards stay under their age, and a decision is refused out loud — nothing queues. */
    @Test
    fun offline_the_cards_stay_stale_and_a_decision_is_refused() = runTest {
        val fleet = WorkFleet()
        val actions = FakeWorkActions().apply { reviewAnswer = INBOX }
        val vm = reviewVm(actions = actions, fleet = fleet)
        vm.open()
        runCurrent()
        assertNull(vm.state.value.stale)

        fleet.status.value = ConnectionStatus.Offline("not connected")
        runCurrent()

        assertEquals("Offline · as of 14:13", vm.state.value.stale)
        assertEquals(3, vm.state.value.items.size)
        assertNull(vm.confirm(vm.state.value.items[0]))
        runCurrent()
        assertEquals(OFFLINE_WRITE, vm.state.value.error)
        assertTrue(actions.calls.isEmpty())
    }

    /** Load more follows the page's cursor and appends; a page that lands after a re-read is dropped. */
    @Test
    fun load_more_follows_the_cursor_and_a_stale_page_is_dropped() = runTest {
        val first = INBOX.copy(items = INBOX.items.take(1), nextCursor = "c2")
        val second = INBOX.copy(items = INBOX.items.drop(1))
        val actions = FakeWorkActions().apply { reviewAnswer = first }
        val vm = reviewVm(actions = actions)
        vm.open()
        runCurrent()
        assertTrue(vm.state.value.hasMore)

        actions.reviewAnswer = second
        vm.loadMore()
        runCurrent()
        assertEquals(listOf(42L, 50L, 51L), vm.state.value.items.map { it.linkId })
        assertFalse(vm.state.value.hasMore)
    }

    @Test
    fun a_conflict_is_the_hubs_sentence_with_reload() = runTest {
        val actions = FakeWorkActions().apply {
            reviewAnswer = INBOX
            batchAnswer = BatchResult(listOf(DecisionResult(linkId = 42, ok = false, code = "E_CONFLICT", message = "link 42 was confirmed on another device")))
        }
        val vm = reviewVm(actions = actions)
        vm.open()
        runCurrent()

        vm.reject(vm.state.value.items[0])
        runCurrent()

        val s = vm.state.value
        assertTrue(s.conflict)
        assertEquals("link 42 was confirmed on another device", s.error?.body)
        assertNull(s.undo, "nothing was decided, so nothing to undo")
        vm.reload()
        runCurrent()
        assertNull(vm.state.value.error)
    }

    /** Confirm all shown: one batch of the suggestions on screen; each refusal is said on its own card. */
    @Test
    fun confirm_all_shown_reports_each_refusal_on_its_card() = runTest {
        val actions = FakeWorkActions().apply {
            reviewAnswer = TWO_SUGGESTIONS
            batchAnswer = json.decodeFromString(BatchResult.serializer(), WorkTreeJson.BATCH)
        }
        val vm = reviewVm(actions = actions)
        vm.open()
        runCurrent()
        assertEquals(2, vm.state.value.batchCount, "the cross-org card is not a suggestion")

        // What the hub still lists afterwards: the one it refused.
        actions.reviewAnswer = TWO_SUGGESTIONS.copy(items = TWO_SUGGESTIONS.items.filter { it.linkId == 46L }, total = 1)
        vm.confirmAllShown()
        runCurrent()

        assertEquals(listOf("decide_batch confirm:42@2,confirm:46@1"), actions.calls)
        val s = vm.state.value
        assertEquals(listOf(46L), s.items.map { it.linkId })
        assertEquals("link 46 changed: now confirmed", s.failures[46L]?.message)
        assertEquals("E_CONFLICT", s.failures[46L]?.code)
        assertNull(s.failures[42L])
    }

    /** Change…: link the alternative, then reject the suggestion under its version. */
    @Test
    fun change_links_the_alternative_and_rejects_the_suggestion() = runTest {
        val actions = FakeWorkActions().apply { reviewAnswer = INBOX }
        val vm = reviewVm(actions = actions)
        vm.open()
        runCurrent()
        val item = vm.state.value.items[0]
        vm.toggleChange(item)
        runCurrent()
        assertEquals(42L, vm.state.value.changing)

        vm.change(item, item.alternatives.single())
        runCurrent()

        assertEquals(listOf("link 7 ABC-13", "decide_batch reject:42@2"), actions.calls)
        assertEquals(listOf("link 7 ABC-13 primary=false"), actions.workArgs, "no row for the session: its primary is not known, so never taken")
        assertEquals("Changed to ABC-13", vm.state.value.undo?.label)
        assertEquals(3L, vm.state.value.undo?.version)
        assertNull(vm.state.value.changing)
    }

    @Test
    fun a_conflict_card_is_kept_or_removed_under_its_version() = runTest {
        val actions = FakeWorkActions().apply { reviewAnswer = INBOX }
        val vm = reviewVm(actions = actions)
        vm.open()
        runCurrent()
        val crossOrg = vm.state.value.items[1]

        vm.keep(crossOrg)
        runCurrent()
        vm.remove(crossOrg)
        runCurrent()

        assertEquals(listOf("ack 9 50 v=1", "unlink 9 50"), actions.calls)
        assertEquals("unlink 9 50 v=1", actions.workArgs.last())
    }

    @Test
    fun a_readonly_token_reads_the_inbox_and_decides_nothing() = runTest {
        val actions = FakeWorkActions().apply { reviewAnswer = INBOX }
        val vm = reviewVm(actions = actions, canWrite = false)
        vm.open()
        runCurrent()

        val s = vm.state.value
        assertEquals(3, s.items.size)
        assertFalse(s.canConfirm || s.canReject || s.canKeep || s.canBatch || s.canChange)
        assertEquals(0, s.batchCount)
        assertNull(vm.confirm(s.items[0]))
        assertNull(vm.confirmAllShown())
        runCurrent()
        assertTrue(actions.calls.isEmpty())
    }
}
