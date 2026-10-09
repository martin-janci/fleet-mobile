package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.ShareActions
import dev.claudefleet.mobile.model.GrantLevel
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.MemberGrants
import dev.claudefleet.mobile.model.MyAccess
import dev.claudefleet.mobile.model.MyGrants
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionGrant
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.ShareTo
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ME = 1L

private class ShareFleet(
    caps: HubCapabilities,
    rows: List<SessionRow>,
    access: MyAccess,
    contract: Int? = 12,
) : FleetState {
    override val sessions = MutableStateFlow(rows)
    override val hosts = MutableStateFlow(emptyList<HostRow>())
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    override val access = MutableStateFlow(access)
    override val hubContract = MutableStateFlow(contract)
    override suspend fun refresh() = Unit
}

private class FakeShareActions : ShareActions {
    val calls = mutableListOf<String>()
    var grants = listOf(
        SessionGrant(sessionId = 10, personId = 2, personName = "silvester", level = "drive"),
        SessionGrant(sessionId = 10, orgId = 7, orgName = "acme", level = "watch"),
    )
    var refuse: HubError? = null

    override suspend fun access(sessionId: Long): List<SessionGrant> = grants.also { calls += "access $sessionId" }

    override suspend fun share(sessionId: Long, to: ShareTo, level: String) {
        calls += "share $sessionId $to $level"
        refuse?.let { throw it }
    }

    override suspend fun narrow(sessionId: Long, to: ShareTo) {
        calls += "narrow $sessionId $to"
    }

    override suspend fun revoke(sessionId: Long, to: ShareTo) {
        calls += "revoke $sessionId $to"
    }
}

private val SHARING = HubCapabilities(tools = setOf("my_grants", "session_access", "session_share", "session_narrow", "session_unshare"))
private val MINE = SessionRow(id = 10, tmuxName = "fix-flake", hostAlias = "mac", ownerPersonId = ME)
private val THEIRS = SessionRow(id = 11, tmuxName = "release-notes", hostAlias = "mac", ownerPersonId = 2)
private val UNCLAIMED = SessionRow(id = 12, tmuxName = "old", hostAlias = "mac")
private val ACCESS = MyAccess(personId = ME, grants = mapOf(11L to GrantLevel.ANSWER))

/**
 * Share and watch on the phone (redesign 11.10): the owner's share sheet,
 * what a shared session lets this person do, and the Inbox's Shared with me.
 */
class ShareTest {

    private fun TestScope.share(
        caps: HubCapabilities = SHARING,
        actions: FakeShareActions = FakeShareActions(),
        canWrite: Boolean = true,
        contract: Int? = 12,
        access: MyAccess = ACCESS,
    ) = ShareViewModel(ShareFleet(caps, listOf(MINE, THEIRS, UNCLAIMED), access, contract), actions, backgroundScope, canWrite)

    @Test
    fun access_tells_mine_from_shared_and_says_nothing_it_does_not_know() {
        assertNull(ACCESS.levelFor(MINE))
        assertTrue(ACCESS.owns(MINE))
        assertEquals(GrantLevel.ANSWER, ACCESS.levelFor(THEIRS))
        assertFalse(ACCESS.owns(THEIRS))
        // Unclaimed and ungranted: nothing to narrow by, so the hub decides.
        assertNull(ACCESS.levelFor(UNCLAIMED))
        assertFalse(ACCESS.owns(UNCLAIMED))
        // A hub that has not said who this is gates nothing and owns nothing.
        assertNull(MyAccess.UNKNOWN.levelFor(THEIRS))
        assertFalse(MyAccess.UNKNOWN.owns(MINE))
    }

    @Test
    fun my_grants_decodes_into_access() {
        val answer = json.decodeFromString(MyGrants.serializer(), """{"person_id":1,"grants":[{"session_id":11,"level":"answer"}]}""")
        assertEquals(ACCESS, MyAccess.of(answer))
        // A device no pairing bound answers no person and no grants.
        assertEquals(MyAccess.UNKNOWN, MyAccess.of(json.decodeFromString(MyGrants.serializer(), """{"grants":[]}""")))
    }

    @Test
    fun session_access_decodes_people_and_orgs_and_names_what_to_send_back() {
        val grants = json.decodeFromString(
            ListSerializer(SessionGrant.serializer()),
            """[{"session_id":10,"person_id":2,"person_name":"silvester","person_display_name":"Silvester","level":"answer","granted_by":1,"granted_at":5},
               {"session_id":10,"org_id":7,"org_name":"acme","level":"watch","granted_by":1,"granted_at":6},
               {"session_id":10,"person_id":9,"level":"drive","granted_by":1,"granted_at":7}]""",
        )
        assertEquals(listOf("Silvester", "acme (its members)", "person #9"), grants.map { it.label })
        assertEquals(listOf(ShareTo.Person("silvester"), ShareTo.Org("acme"), null), grants.map { it.recipient })
        assertEquals(listOf(true, false, true), grants.map { it.narrowable })
    }

    @Test
    fun the_owner_reads_who_it_is_shared_with_on_open() = runTest {
        val actions = FakeShareActions()
        val vm = share(actions = actions)
        vm.open(MINE.id)
        runCurrent()
        val s = vm.state.value
        assertTrue(s.open)
        assertTrue(s.owner)
        assertTrue(s.available)
        assertEquals("fix-flake", s.sessionName)
        assertEquals(2, s.grants?.size)
        assertEquals(listOf("access 10"), actions.calls)
    }

    @Test
    fun answer_is_offered_only_by_a_contract_13_hub() = runTest {
        assertEquals(listOf("watch", "drive"), shareLevels(null))
        assertEquals(listOf("watch", "drive"), shareLevels(12))
        assertEquals(listOf("watch", "answer", "drive"), shareLevels(13))
        val vm = share(contract = 13)
        vm.open(MINE.id)
        runCurrent()
        assertEquals(GrantLevel.ALL, vm.state.value.levels)
        // Picked on an older hub, it is not a share the sheet will send.
        val old = share(contract = 12)
        old.open(MINE.id)
        old.setRecipient("eva")
        old.setLevel(GrantLevel.ANSWER)
        runCurrent()
        assertFalse(old.state.value.canShare)
    }

    @Test
    fun sharing_sends_the_person_or_org_and_level_then_rereads() = runTest {
        val actions = FakeShareActions()
        val vm = share(actions = actions)
        vm.open(MINE.id)
        vm.setRecipient("  eva ")
        vm.setLevel(GrantLevel.DRIVE)
        runCurrent()
        vm.share()
        runCurrent()
        assertEquals(listOf("access 10", "share 10 Person(name=eva) drive", "access 10"), actions.calls)
        assertEquals("", vm.state.value.recipient)
        assertEquals("Shared with eva to drive.", vm.state.value.notice)

        vm.setKind(ShareKind.Org)
        vm.setRecipient("acme")
        runCurrent()
        vm.share()
        runCurrent()
        assertEquals("share 10 Org(name=acme) drive", actions.calls[3])
    }

    @Test
    fun a_refused_share_keeps_the_sheet_and_the_draft() = runTest {
        val actions = FakeShareActions().apply { refuse = HubError.Tool("E_NOTFOUND", "no person named evq") }
        val vm = share(actions = actions)
        vm.open(MINE.id)
        vm.setRecipient("evq")
        runCurrent()
        vm.share()
        runCurrent()
        assertNotNull(vm.state.value.error)
        assertEquals("evq", vm.state.value.recipient)
        assertTrue(vm.state.value.open)
    }

    @Test
    fun narrow_and_a_two_step_revoke() = runTest {
        val actions = FakeShareActions()
        val vm = share(actions = actions)
        vm.open(MINE.id)
        runCurrent()
        val drive = vm.state.value.grants!!.first()
        vm.narrow(drive)
        runCurrent()
        assertEquals("narrow 10 Person(name=silvester)", actions.calls[1])

        // A watch grant is already as narrow as it goes.
        vm.narrow(vm.state.value.grants!!.last())
        runCurrent()
        assertTrue(actions.calls.none { it.startsWith("narrow 10 Org") })

        // Revoke waits for the second tap.
        vm.revoke()
        runCurrent()
        assertTrue(actions.calls.none { it.startsWith("revoke") })
        vm.askRevoke(ShareTo.Org("acme"))
        runCurrent()
        assertEquals(ShareTo.Org("acme"), vm.state.value.confirming)
        vm.revoke()
        runCurrent()
        assertTrue("revoke 10 Org(name=acme)" in actions.calls)
        assertNull(vm.state.value.confirming)
    }

    @Test
    fun nobody_but_the_owner_and_no_readonly_pairing_may_act() = runTest {
        val actions = FakeShareActions()
        val theirs = share(actions = actions)
        theirs.open(THEIRS.id)
        theirs.setRecipient("eva")
        runCurrent()
        assertFalse(theirs.state.value.owner)
        theirs.share()
        runCurrent()
        assertTrue(actions.calls.none { it.startsWith("share") })

        val readonly = share(actions = actions, canWrite = false)
        readonly.open(MINE.id)
        readonly.setRecipient("eva")
        runCurrent()
        assertFalse(readonly.state.value.available)
        assertFalse(readonly.state.value.canShare)

        val oldHub = share(caps = HubCapabilities(tools = setOf("my_grants")))
        oldHub.open(MINE.id)
        runCurrent()
        assertFalse(oldHub.state.value.available)
    }

    @Test
    fun the_inbox_moves_shared_sessions_out_of_needs_you() {
        val waitingMine = MINE.copy(claudeStatus = "blocked")
        val waitingTheirs = THEIRS.copy(claudeStatus = "blocked", lastActivityAt = 50)
        val watched = SessionRow(id = 13, tmuxName = "spec", ownerPersonId = 2, lastActivityAt = 90)
        val access = MyAccess(personId = ME, grants = mapOf(11L to GrantLevel.ANSWER, 13L to GrantLevel.WATCH))
        val rows = listOf(waitingMine, waitingTheirs, watched)
        assertEquals(listOf(10L), inboxRows(rows, access).map { it.id })
        val shared = sharedRows(rows, access)
        assertEquals(listOf(13L, 11L), shared.map { it.row.id })
        assertEquals(listOf("Watch", "Answer · waiting for its owner"), shared.map(::sharedLine))
        // A hub without sharing: every row is the Inbox's, as before.
        assertEquals(listOf(10L, 11L), inboxRows(rows).map { it.id })
        assertTrue(sharedRows(rows, MyAccess.UNKNOWN).isEmpty())
    }

    @Test
    fun a_removed_members_answer_shares_are_asked_about_too() {
        val grants = MemberGrants(watch = 1, answer = 2, drive = 0)
        assertEquals(
            "3 sessions of Acme are shared with them (0 to drive, 2 to answer, 1 to watch). What happens to those shares?",
            removeQuestion("Acme", grants),
        )
        assertEquals(KeepShares.entries.toList(), keepChoices(grants))
        // From a hub before the Answer level the count is absent, and the old words stand.
        val old = json.decodeFromString(MemberGrants.serializer(), """{"watch":2,"drive":1}""")
        assertEquals("3 sessions of Acme are shared with them (1 to drive, 2 to watch). What happens to those shares?", removeQuestion("Acme", old))
    }
}
