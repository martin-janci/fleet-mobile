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
}
