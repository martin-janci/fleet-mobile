@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.BatchResult
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
) = ReviewViewModel(fleet, actions, backgroundScope, canWrite, onChanged = { changed += Unit })

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

    /** Confirm sends the version it was read at; Undo sends it back to a suggestion. */
    @Test
    fun confirm_carries_the_version_and_undo_reconsiders_it() = runTest {
        val changed = mutableListOf<Unit>()
        val actions = FakeWorkActions().apply { reviewAnswer = INBOX }
        val vm = reviewVm(actions = actions, changed = changed)
        vm.open()
        runCurrent()

        vm.confirm(vm.state.value.items[0])
        runCurrent()
        assertEquals("Confirmed ABC-12", vm.state.value.undo?.label)
        assertTrue(vm.state.value.canUndo)

        vm.undo()
        runCurrent()

        assertEquals(listOf("confirm 7 42", "reconsider 7 42"), actions.calls)
        assertEquals(listOf("confirm 7 42 primary=null v=2"), actions.workArgs)
        assertNull(vm.state.value.undo, "an undo is not itself undone")
        assertEquals(2, changed.size, "My work's count follows every decision")
        assertEquals(3, actions.reviewCalls, "the inbox is re-read after each")
    }

    @Test
    fun a_conflict_is_the_hubs_sentence_with_reload() = runTest {
        val actions = FakeWorkActions().apply {
            reviewAnswer = INBOX
            fail = HubError.Tool("E_CONFLICT", "link 42 was confirmed on another device")
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

        assertEquals(listOf("link 7 ABC-13", "reject 7 42"), actions.calls)
        assertEquals("reject 7 42 v=2", actions.workArgs.last())
        assertEquals("Changed to ABC-13", vm.state.value.undo?.label)
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
