@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.AccountLimit
import dev.claudefleet.mobile.model.Attention
import dev.claudefleet.mobile.model.Headroom
import dev.claudefleet.mobile.model.HostLogin
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.MissionWait
import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeInbox(
    var headroom: Headroom = Headroom(),
    var refuse: HubError? = null,
) : InboxActions {
    val calls = mutableListOf<String>()

    override suspend fun retry(sessionId: Long, prompt: String) {
        calls += "retry $sessionId $prompt"
        refuse?.let { throw it }
    }

    override suspend fun headroom(hostAlias: String, profile: String?): Headroom {
        calls += "headroom $hostAlias ${profile.orEmpty()}"
        return headroom
    }

    override suspend fun restartUnder(sessionId: Long, profile: String) {
        calls += "restart $sessionId $profile"
    }
}

private fun failed(id: Long = 1, prompt: String? = "run the tests") = SessionRow(
    id = id,
    tmuxName = "s$id",
    hostAlias = "mercury",
    claudeStatus = "failed",
    lastPrompt = prompt,
    attention = Attention("failed", since = 10, state = "failed"),
)

private fun paused(id: Long = 2) = SessionRow(
    id = id,
    tmuxName = "s$id",
    hostAlias = "mac",
    claudeStatus = "idle",
    accountUuid = "acct-a",
    attention = Attention("account_limit", since = 20, state = "blocked"),
)

/** Gap plan G5.4 (board MobileNav): the Inbox's rows carry their fix. */
class InboxViewModelTest {

    @Test
    fun a_failed_row_opens_its_log_and_retries_its_last_prompt_only_where_this_phone_may() {
        val a = inboxRowActions(failed(), canWrite = true, switchAvailable = true, limit = null)
        assertTrue(a.openLog)
        assertEquals("run the tests", a.retryPrompt)
        assertFalse(a.switchAccount)

        assertNull(inboxRowActions(failed(), canWrite = false, switchAvailable = true, limit = null).retryPrompt, "a readonly pairing")
        assertTrue(inboxRowActions(failed(), canWrite = false, switchAvailable = true, limit = null).openLog, "the log is a read")
        assertNull(inboxRowActions(failed(prompt = " "), canWrite = true, switchAvailable = true, limit = null).retryPrompt, "nothing to send again")
        val asking = failed().copy(pendingInput = PendingInput(kind = "permission", question = "Allow?"))
        assertNull(inboxRowActions(asking, canWrite = true, switchAvailable = true, limit = null).retryPrompt, "a question is answered, not retried")
        assertNull(inboxRowActions(failed().copy(isController = true), canWrite = true, switchAvailable = true, limit = null).retryPrompt)
    }

    @Test
    fun a_paused_row_offers_switch_account_and_wait_until_the_reset() {
        val a = inboxRowActions(paused(), canWrite = true, switchAvailable = true, limit = AccountLimit(weekly = true, resetsAt = 5_000))
        assertTrue(a.switchAccount)
        assertEquals(5_000L, a.waitUntil)
        assertFalse(a.openLog)
        assertFalse(inboxRowActions(paused(), canWrite = true, switchAvailable = false, limit = null).switchAccount, "a hub that cannot say which login has room")
        assertNull(inboxRowActions(paused(), canWrite = true, switchAvailable = true, limit = null).waitUntil, "no reading, no reset to wait for")
        assertFalse(inboxRowActions(SessionRow(id = 3, tmuxName = "x", claudeStatus = "blocked", attention = Attention("waiting")), true, true, null).any)
    }

    @Test
    fun retry_sends_the_last_prompt_once_and_says_so() = runTest {
        val hub = FakeInbox()
        val vm = InboxViewModel(hub, this, canWrite = true)
        vm.retry(failed())
        assertTrue(1L in vm.state.value.busy)
        assertNull(vm.retry(failed()), "one press at a time per row")
        runCurrent()
        assertEquals(listOf("retry 1 run the tests"), hub.calls)
        assertEquals("Sent the last prompt again.", vm.state.value.notices[1L])
        assertFalse(1L in vm.state.value.busy)
    }

    @Test
    fun a_refused_retry_is_the_rows_notice() = runTest {
        val hub = FakeInbox(refuse = HubError.Tool("E_INVALID_STATE", "the session is gone"))
        val vm = InboxViewModel(hub, this, canWrite = true)
        vm.retry(failed()); runCurrent()
        assertEquals("the session is gone", vm.state.value.notices[1L])
    }

    @Test
    fun a_readonly_phone_retries_nothing() = runTest {
        val hub = FakeInbox()
        val vm = InboxViewModel(hub, this, canWrite = false)
        assertNull(vm.retry(failed()))
        assertNull(vm.proposeSwitch(paused(), switchAvailable = true))
        runCurrent()
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun switch_account_asks_first_and_moves_only_on_the_second_tap() = runTest {
        val other = HostLogin(profile = "work", accountUuid = "acct-b", usedPct = 12.0)
        val hub = FakeInbox(
            headroom = Headroom(
                pauseAtPct = 95.0,
                logins = listOf(HostLogin(accountUuid = "acct-a", usedPct = 100.0), other),
            ),
        )
        val vm = InboxViewModel(hub, this, canWrite = true, accountName = { if (it == "acct-b") "m.janci" else null })
        vm.proposeSwitch(paused(), switchAvailable = true); runCurrent()
        assertEquals(other, vm.state.value.switchTargets[2L])
        assertTrue(hub.calls.none { it.startsWith("restart") }, "nothing moves on the first tap")

        vm.confirmSwitch(paused(), switchAvailable = true); runCurrent()
        assertEquals("restart 2 work", hub.calls.last())
        assertNull(vm.state.value.switchTargets[2L])
        assertEquals("Resumed under work (m.janci).", vm.state.value.notices[2L])
    }

    @Test
    fun switch_account_with_nowhere_to_go_says_so_and_cancel_puts_it_away() = runTest {
        val hub = FakeInbox(headroom = Headroom(pauseAtPct = 95.0, logins = listOf(HostLogin(accountUuid = "acct-a", usedPct = 100.0))))
        val vm = InboxViewModel(hub, this, canWrite = true)
        vm.proposeSwitch(paused(), switchAvailable = true); runCurrent()
        assertEquals("No other login on mac has room left.", vm.state.value.notices[2L])
        assertNull(vm.confirmSwitch(paused(), switchAvailable = true), "no target, no second tap")
    }

    @Test
    fun wait_and_not_waiting_change_nothing_on_the_hub() = runTest {
        val hub = FakeInbox()
        val vm = InboxViewModel(hub, this, canWrite = true)
        vm.waitForReset(2L, 9_000)
        assertEquals(9_000L, vm.state.value.waiting[2L])

        val jev = SessionRow(id = 7, tmuxName = "j", claudeStatus = "idle", lastStopAt = 300, attention = Attention("probably_waiting", state = "proposed"))
        vm.notWaiting(jev)
        assertEquals(setOf("7:300"), vm.state.value.setAside)
        assertTrue(proposedRows(listOf(jev), setAside = vm.state.value.setAside).isEmpty())
        // A later turn is a new reading: it shows again.
        assertEquals(listOf(7L), proposedRows(listOf(jev.copy(lastStopAt = 400)), setAside = vm.state.value.setAside).map { it.id })
        runCurrent()
        assertTrue(hub.calls.isEmpty())
    }

    @Test
    fun a_mission_that_waits_on_you_is_an_inbox_row_longest_waiting_first() {
        val missions = listOf(
            Mission(id = 1, name = "a", state = "active", waitingOn = MissionWait("confirm", since = 300, openCards = 3)),
            Mission(id = 2, name = "b", state = "active", waitingOn = MissionWait("sign_grant", since = 100)),
            Mission(id = 3, name = "c", state = "active"),
            Mission(id = 4, name = "d", state = "paused", waitingOn = MissionWait("question", since = 50)),
        )
        assertEquals(listOf(2L, 1L), missionWaits(missions).map { it.id })
        assertEquals("sign the autonomy grant", missionAskWords(MissionWait("sign_grant")))
        assertEquals("answer its question", missionAskWords(MissionWait("question")))
        assertEquals("confirm 3 commands", missionAskWords(MissionWait("confirm", openCards = 3)))
        assertEquals("confirm 1 command", missionAskWords(MissionWait("confirm", openCards = 1)))
        assertEquals("5 need you · 0 running", inboxSubtitle(3 + missionWaits(missions).size, 0))
    }
}
