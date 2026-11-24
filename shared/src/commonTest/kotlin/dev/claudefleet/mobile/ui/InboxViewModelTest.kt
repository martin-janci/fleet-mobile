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

    val retryIds = mutableListOf<String>()

    override suspend fun retry(sessionId: Long, prompt: String, clientMsgId: String) {
        calls += "retry $sessionId $prompt"
        retryIds += clientMsgId
        refuse?.let { throw it }
    }

    override suspend fun headroom(hostAlias: String, profile: String?, projectId: Long?, model: String?): Headroom {
        calls += "headroom $hostAlias ${profile.orEmpty()}" + (projectId?.let { " project $it" } ?: "") + (model?.let { " model $it" } ?: "")
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

    /** Review w05-2: a shared row counts and offers only what the hub takes from that level. */
    @Test
    fun a_shared_row_is_in_needs_you_only_for_what_its_level_may_do() {
        val G = dev.claudefleet.mobile.model.GrantLevel
        val waiting = SessionRow(id = 3, tmuxName = "w", claudeStatus = "blocked", ownerPersonId = 2, attention = Attention("waiting", state = "action_required"))
        val rows = listOf(failed().copy(ownerPersonId = 2), paused().copy(ownerPersonId = 2), waiting)
        fun shown(level: String) =
            inboxRows(rows, dev.claudefleet.mobile.model.MyAccess(personId = 7, grants = rows.associate { it.id to level })).map { it.id }.sorted()
        assertEquals(listOf(3L), shown(G.ANSWER), "a failed turn or a limit is not Answer's to fix")
        assertEquals(listOf(1L, 3L), shown(G.DRIVE), "Steer may send the prompt again")
        assertEquals(emptyList(), shown(G.WATCH))

        assertEquals("run the tests", inboxRowActions(failed(), true, true, null, G.DRIVE).retryPrompt)
        assertNull(inboxRowActions(failed(), true, true, null, G.ANSWER).retryPrompt)
        assertTrue(inboxRowActions(failed(), true, true, null, G.ANSWER).openLog)
        assertFalse(inboxRowActions(paused(), true, true, null, G.DRIVE).switchAccount, "a restart is the owner's")
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

    /**
     * Review w17-4: Retry goes out under one `client_msg_id` until a press is
     * answered, so a second press after a lost answer cannot type the prompt
     * twice, and an unknown outcome says so rather than that it failed.
     */
    @Test
    fun a_retry_whose_answer_was_lost_goes_out_again_under_the_same_id() = runTest {
        val hub = FakeInbox(refuse = HubError.Tool("E_SSH_TIMEOUT", "timed out"))
        val vm = InboxViewModel(hub, this, canWrite = true)
        vm.retry(failed()); runCurrent()
        assertEquals(friendlyWrite(HubError.Tool("E_SSH_TIMEOUT", "timed out")).body, vm.state.value.notices[1L])
        hub.refuse = null
        vm.retry(failed()); runCurrent()
        assertEquals(2, hub.retryIds.size)
        assertEquals(hub.retryIds[0], hub.retryIds[1], "the same send to the hub")
        assertEquals("Sent the last prompt again.", vm.state.value.notices[1L])
        vm.retry(failed()); runCurrent()
        assertTrue(hub.retryIds[2] != hub.retryIds[1], "answered: the next Retry is a new send")
    }

    /**
     * Review w04-4: a lost Retry's id does not outlive its outcome. A later
     * failed turn, or the row coming back after it left Needs you, is a new
     * send the hub types, not a replay of its cached answer.
     */
    @Test
    fun a_lost_retrys_id_is_not_reused_for_a_later_failure() = runTest {
        val hub = FakeInbox(refuse = HubError.Tool("E_SSH_TIMEOUT", "timed out"))
        val vm = InboxViewModel(hub, this, canWrite = true)
        vm.retry(failed().copy(turnSeq = 4)); runCurrent()
        vm.retry(failed().copy(turnSeq = 5)); runCurrent()
        assertTrue(hub.retryIds[1] != hub.retryIds[0], "a later turn: a new send")

        vm.keepOnly(emptySet())
        vm.retry(failed().copy(turnSeq = 5)); runCurrent()
        assertTrue(hub.retryIds[2] != hub.retryIds[1], "the row left Needs you: its id went with it")
    }

    /** Review w17-5: a row that leaves Needs you takes its notice and Switch proposal with it. */
    @Test
    fun a_notice_and_a_proposal_go_when_their_row_leaves_needs_you() = runTest {
        val hub = FakeInbox(headroom = Headroom(pauseAtPct = 95.0, logins = listOf(HostLogin(accountUuid = "acct-a", usedPct = 100.0), HostLogin(profile = "home", accountUuid = "acct-c", usedPct = 40.0))))
        val vm = InboxViewModel(hub, this, canWrite = true)
        vm.retry(failed()); runCurrent()
        vm.proposeSwitch(paused(), switchAvailable = true); runCurrent()
        assertEquals(setOf(1L), vm.state.value.notices.keys)
        assertEquals(setOf(2L), vm.state.value.switchTargets.keys)

        vm.keepOnly(setOf(1L, 2L))
        assertEquals(setOf(1L), vm.state.value.notices.keys, "still in Needs you: kept")
        vm.keepOnly(emptySet())
        assertTrue(vm.state.value.notices.isEmpty())
        assertTrue(vm.state.value.switchTargets.isEmpty())

        vm.retry(failed()); runCurrent()
        vm.dismissNotice(1L)
        assertTrue(vm.state.value.notices.isEmpty(), "a tap puts the notice away")
    }

    /** Review w23-3: a signed-out login is never proposed, though its last reading has room. */
    @Test
    fun switch_account_never_proposes_a_signed_out_login() = runTest {
        val expired = HostLogin(profile = "work", accountUuid = "acct-b", usedPct = 5.0)
        val fresh = HostLogin(profile = "home", accountUuid = "acct-c", usedPct = 40.0)
        val hub = FakeInbox(headroom = Headroom(pauseAtPct = 95.0, logins = listOf(HostLogin(accountUuid = "acct-a", usedPct = 100.0), expired, fresh)))
        val vm = InboxViewModel(hub, this, canWrite = true, signedOut = { host, profile -> host == "mac" && profile == "work" })
        vm.proposeSwitch(paused(), switchAvailable = true); runCurrent()
        assertEquals(fresh, vm.state.value.switchTargets[2L])
    }

    /**
     * The project's forbidden accounts (claude-fleet migration 217): Switch
     * never proposes a login on one, though it has the most room, and the
     * headroom read names the project so the hub's own suggestion skips them too.
     */
    @Test
    fun switch_account_never_proposes_a_login_the_project_forbids() = runTest {
        val spare = HostLogin(profile = "spare", accountUuid = "acct-b", usedPct = 10.0)
        val team = HostLogin(profile = "team", accountUuid = "acct-c", usedPct = 60.0)
        val hub = FakeInbox(headroom = Headroom(pauseAtPct = 95.0, logins = listOf(HostLogin(accountUuid = "acct-a", usedPct = 100.0), spare, team)))
        val vm = InboxViewModel(hub, this, canWrite = true, forbidden = { p, _ -> if (p == 7L) setOf("acct-b") else emptySet() })
        vm.proposeSwitch(paused().copy(projectId = 7), switchAvailable = true); runCurrent()
        assertEquals(team, vm.state.value.switchTargets[2L])
        assertEquals("headroom mac  project 7", hub.calls.single())
        // Review w10-5: an Opus-launched row reads headroom for its model.
        vm.proposeSwitch(paused(id = 3).copy(projectId = 7, launchModel = "opus"), switchAvailable = true); runCurrent()
        assertEquals("headroom mac  project 7 model opus", hub.calls.last())
        hub.calls.removeAt(hub.calls.lastIndex)

        // A signed-out row: the same rule.
        val row = paused().copy(projectId = 7, claudeProfile = "work", attention = Attention("no_credentials", since = 20, state = "blocked"))
        vm.proposeSwitch(row, switchAvailable = true); runCurrent()
        assertEquals(team, vm.state.value.switchTargets[2L])

        // Only spare has room: nowhere to go, rather than a refused restart.
        val only = InboxViewModel(
            FakeInbox(headroom = Headroom(pauseAtPct = 95.0, logins = listOf(HostLogin(accountUuid = "acct-a", usedPct = 100.0), spare))),
            this, canWrite = true, forbidden = { _, _ -> setOf("acct-b") },
        )
        only.proposeSwitch(paused().copy(projectId = 7), switchAvailable = true); runCurrent()
        assertNull(only.state.value.switchTargets[2L])
        assertEquals("No other login on mac has room left.", only.state.value.notices[2L])
    }

    /** Review w23-4: a signed-out row offers Switch account, to a login that is signed in. */
    @Test
    fun a_signed_out_row_switches_to_a_signed_in_login() = runTest {
        val row = paused().copy(claudeProfile = "work", attention = Attention("no_credentials", since = 20, state = "blocked"))
        assertTrue(inboxRowActions(row, canWrite = true, switchAvailable = true, limit = null).switchAccount)
        assertFalse(inboxRowActions(row, canWrite = false, switchAvailable = true, limit = null).switchAccount)

        val own = HostLogin(profile = "work", accountUuid = "acct-a", usedPct = 3.0)
        val home = HostLogin(profile = "home", accountUuid = "acct-a", usedPct = 30.0)
        val full = HostLogin(profile = "full", accountUuid = "acct-b", usedPct = 99.0)
        val hub = FakeInbox(headroom = Headroom(pauseAtPct = 95.0, logins = listOf(own, home, full)))
        val vm = InboxViewModel(hub, this, canWrite = true)
        vm.proposeSwitch(row, switchAvailable = true); runCurrent()
        assertEquals(home, vm.state.value.switchTargets[2L], "not its own signed-out login, nor one past the pause line")

        val none = InboxViewModel(FakeInbox(headroom = Headroom(pauseAtPct = 95.0, logins = listOf(own))), this, canWrite = true)
        none.proposeSwitch(row, switchAvailable = true); runCurrent()
        assertEquals("No other login on mac is signed in with room left.", none.state.value.notices[2L])
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
            Mission(id = 5, name = "e", state = "paused"),
        )
        // Review w09-1: a mission a brake paused with an open question waits too.
        assertEquals(listOf(4L, 2L, 1L), missionWaits(missions).map { it.id })
        assertEquals("sign the autonomy grant", missionAskWords(MissionWait("sign_grant")))
        assertEquals("answer its question", missionAskWords(MissionWait("question")))
        assertEquals("confirm 3 commands", missionAskWords(MissionWait("confirm", openCards = 3)))
        assertEquals("confirm 1 command", missionAskWords(MissionWait("confirm", openCards = 1)))
        assertEquals("6 need you · 0 running", inboxSubtitle(3 + missionWaits(missions).size, 0))
    }
}
