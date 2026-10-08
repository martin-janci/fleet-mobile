package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.ReopenedWork
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.TidyApplyItem
import dev.claudefleet.mobile.model.TidyCandidate
import dev.claudefleet.mobile.model.TidyReport
import dev.claudefleet.mobile.net.HubCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class TidyFleet(tools: Set<String>) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "pine", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(HubCapabilities(tools = tools))
    override suspend fun refresh() = Unit
}

/** Tidy-up's rules and screen state, held to the desktop's `tidy.ts`. */
class TidyTest {

    private val linkedDone = TidyCandidate(sessionId = 1, linkId = 10, reason = "done_idle", action = "safe_kill")
    private val unlinkedIdle = TidyCandidate(sessionId = 2, reason = "idle_unlinked", action = "safe_kill")
    private val expiringGhost = TidyCandidate(sessionId = 3, linkId = 30, reason = "ghost_expiring", action = "resume_or_expire")
    private val merged = TidyCandidate(sessionId = 4, linkId = 40, reason = "pr_merged_idle", action = "archive")

    @Test
    fun the_choices_follow_the_link() {
        assertEquals(listOf(TidyChoice.SafeKill, TidyChoice.Archive, TidyChoice.Snooze, TidyChoice.Never), tidyChoices(linkedDone))
        assertEquals(listOf(TidyChoice.SafeKill, TidyChoice.Keep), tidyChoices(unlinkedIdle))
        // An expiring ghost is never archived: snooze or never only.
        assertEquals(listOf(TidyChoice.Snooze, TidyChoice.Never), tidyChoices(expiringGhost))
    }

    @Test
    fun the_suggestion_is_the_hubs_action_when_it_is_a_choice() {
        assertEquals(TidyChoice.SafeKill, tidyDefault(linkedDone))
        assertEquals(TidyChoice.Archive, tidyDefault(merged))
        assertEquals(TidyChoice.Snooze, tidyDefault(expiringGhost))
    }

    @Test
    fun what_is_ticked_to_begin_with() {
        assertTrue(tidyPreselected(linkedDone))
        assertFalse(tidyPreselected(unlinkedIdle), "idle and unlinked is never ticked for you")
        assertFalse(tidyPreselected(expiringGhost), "an expiring ghost is never ticked for you")
    }

    @Test
    fun items_carry_the_link_and_the_days() {
        val items = tidyItems(
            listOf(linkedDone, unlinkedIdle, merged),
            ticked = setOf(1, 2),
            chosen = mapOf(1L to TidyChoice.Snooze, 2L to TidyChoice.Keep),
        )
        assertEquals(
            listOf(
                TidyApplyItem(sessionId = 1, action = "snooze", linkId = 10, days = 7),
                TidyApplyItem(sessionId = 2, action = "keep", linkId = null, days = 7),
            ),
            items,
        )
    }

    @Test
    fun groups_come_in_the_desktops_order() {
        assertEquals(
            listOf("done_idle", "pr_merged_idle", "ghost_expiring", "idle_unlinked"),
            tidyGroups(listOf(unlinkedIdle, expiringGhost, merged, linkedDone)).map { it.first },
        )
    }

    private val tools = setOf(HubCapabilities.WORK, HubCapabilities.WORK_LINK)

    @Test
    fun opening_ticks_the_suggestions_and_apply_sends_them_then_reads_again() = runTest {
        val work = FakeWorkActions()
        work.tidyAnswer = TidyReport(candidates = listOf(linkedDone, unlinkedIdle))
        val vm = TidyViewModel(TidyFleet(tools), work, backgroundScope, canWrite = true)
        runCurrent()
        assertTrue(vm.state.value.available)

        vm.open().join()
        runCurrent()
        assertEquals(setOf(1L), vm.state.value.ticked)
        assertEquals(1, vm.state.value.kills)

        vm.apply().join()
        runCurrent()
        assertEquals(listOf(TidyApplyItem(sessionId = 1, action = "safe_kill", linkId = 10)), work.applied.single())
        assertEquals(2, work.calls.count { it == "tidy" })
        assertTrue(vm.state.value.results!!.single().ok)
    }

    @Test
    fun choosing_ticks_the_candidate() = runTest {
        val work = FakeWorkActions()
        work.tidyAnswer = TidyReport(candidates = listOf(unlinkedIdle))
        val vm = TidyViewModel(TidyFleet(tools), work, backgroundScope, canWrite = true)
        vm.open().join()
        runCurrent()

        vm.choose(2, TidyChoice.Keep)
        runCurrent()
        assertEquals(setOf(2L), vm.state.value.ticked)
        assertEquals(0, vm.state.value.kills)
    }

    @Test
    fun a_readonly_pairing_is_not_offered_tidy_up() = runTest {
        val vm = TidyViewModel(TidyFleet(tools), FakeWorkActions(), backgroundScope, canWrite = false)
        runCurrent()
        assertFalse(vm.state.value.available)
    }

    @Test
    fun a_reopened_item_can_be_dismissed() = runTest {
        val work = FakeWorkActions()
        work.reopenedAnswer = listOf(ReopenedWork(itemId = 5, key = "PAY-5", title = "Back again"))
        val vm = TidyViewModel(TidyFleet(tools), work, backgroundScope, canWrite = true)
        vm.open().join()
        runCurrent()
        assertEquals(1, vm.state.value.reopened.size)

        vm.dismissReopened(5).join()
        runCurrent()
        assertTrue(vm.state.value.reopened.isEmpty())
        assertTrue("dismiss 5" in work.calls)
    }

    @Test
    fun idle_reads_coarsely() {
        assertEquals("1 min", formatIdle(10))
        assertEquals("2 h", formatIdle(7_300))
        assertEquals("2 d", formatIdle(200_000))
    }

    @Test
    fun a_failed_apply_names_the_session_and_says_the_action_in_words() {
        val named = TidyCandidate(sessionId = 5, tmuxName = "fleet-api", label = "API work", reason = "done_idle", action = "safe_kill")
        assertEquals("API work (Safe kill)", tidyFailureLine(5, null, "safe_kill", listOf(named)))
        assertEquals("API work (E_BUSY: dirty)", tidyFailureLine(5, "E_BUSY: dirty", "kill", listOf(named)))
        assertEquals("a session no longer listed (resume or expire)", tidyFailureLine(9, null, "resume_or_expire", listOf(named)))
    }

    // ── The New layout (redesign 14.15, MobileTidyTickets) ──

    @Test
    fun the_new_layout_opens_with_nothing_ticked() = runTest {
        val work = FakeWorkActions()
        work.tidyAnswer = TidyReport(candidates = listOf(linkedDone, merged))
        val vm = TidyViewModel(TidyFleet(tools), work, backgroundScope, canWrite = true, preselect = { false })
        vm.open().join()
        runCurrent()
        assertEquals(emptySet(), vm.state.value.ticked)
        // Nothing ticked, nothing sent.
        vm.apply().join()
        runCurrent()
        assertTrue(work.applied.isEmpty())
    }

    @Test
    fun undo_restores_every_archived_row_the_result_lists() = runTest {
        val work = FakeWorkActions()
        val other = merged.copy(sessionId = 6, linkId = 60, tmuxName = "verify-email")
        work.tidyAnswer = TidyReport(candidates = listOf(merged.copy(tmuxName = "background-review"), other, unlinkedIdle))
        val vm = TidyViewModel(TidyFleet(tools), work, backgroundScope, canWrite = true, preselect = { false })
        vm.open().join()
        runCurrent()
        vm.toggle(4)
        vm.toggle(6)
        vm.choose(2, TidyChoice.SafeKill)
        runCurrent()
        work.tidyAnswer = TidyReport(candidates = emptyList())
        vm.apply().join()
        runCurrent()
        val results = vm.state.value.results!!
        assertEquals("✓ Archived 2, killed 1", tidyDoneLine(results, vm.state.value.undone))
        // Names survive the re-read that dropped the rows.
        assertEquals("background-review", vm.state.value.resultNames[4])

        for (r in results.filter { it.action == "archive" }) {
            vm.undo(r.sessionId)!!.join()
            runCurrent()
        }
        assertEquals(
            listOf(TidyApplyItem(sessionId = 4, action = "unarchive", linkId = 40), TidyApplyItem(sessionId = 6, action = "unarchive", linkId = 60)),
            work.applied.drop(1).flatten(),
        )
        assertEquals(setOf(4L, 6L), vm.state.value.undone)
        assertEquals("✓ Killed 1", tidyDoneLine(vm.state.value.results!!, vm.state.value.undone))
        // A kill has no Undo; an undone archive is not undone twice.
        assertEquals(null, vm.undo(2))
        assertEquals(null, vm.undo(4))
    }

    @Test
    fun retry_sends_a_refused_choice_again_and_takes_the_new_answer() = runTest {
        val work = FakeWorkActions()
        work.tidyAnswer = TidyReport(candidates = listOf(unlinkedIdle.copy(tmuxName = "hosts-spike")))
        work.tidyRefuse = setOf(2)
        val vm = TidyViewModel(TidyFleet(tools), work, backgroundScope, canWrite = true, preselect = { false })
        vm.open().join()
        runCurrent()
        vm.choose(2, TidyChoice.SafeKill)
        vm.apply().join()
        runCurrent()
        val failed = vm.state.value.results!!.single()
        assertFalse(failed.ok)
        assertEquals("Could not kill: mercury did not answer", tidyOutcomeLine(failed, undone = false))

        work.tidyRefuse = emptySet()
        vm.retry(2)!!.join()
        runCurrent()
        assertEquals(listOf(TidyApplyItem(sessionId = 2, action = "safe_kill")), work.applied.last())
        assertTrue(vm.state.value.results!!.single().ok)
        assertEquals(null, vm.retry(2), "nothing left to retry")
    }

    @Test
    fun a_candidate_says_its_rule_its_ticket_and_how_long_it_idled() {
        val c = TidyCandidate(sessionId = 7, reason = "done_idle", key = "FLEET-142", itemStatus = "In Review", idleSecs = 2 * 86_400)
        assertEquals("Done and idle · FLEET-142 is In Review · idle 2 d", tidyReasonLine(c))
    }
}
