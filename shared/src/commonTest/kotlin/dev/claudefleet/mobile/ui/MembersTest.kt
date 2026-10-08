package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.MemberActions
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.MemberGrants
import dev.claudefleet.mobile.model.MemberRemoved
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.OrgDevice
import dev.claudefleet.mobile.model.OrgMemberRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
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

private class MembersFleet(caps: HubCapabilities) : FleetState {
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(emptyList<HostRow>())
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    override suspend fun refresh() = Unit
}

private val MARTIN = OrgMemberRow(personId = 1, name = "martin", displayName = "Martin", role = "admin", owner = true, devices = listOf("Pixel 9"))
private val EVA = OrgMemberRow(personId = 2, name = "eva", role = "member", addedAt = 0, devices = listOf("Eva's Mac"))
private val JAN = OrgMemberRow(personId = 3, name = "jan", role = "viewer")

private val ACME_ADMIN = OrgDetail(id = 7, name = "Acme", myRole = "admin")
private val ACME_MEMBER = OrgDetail(id = 7, name = "Acme", myRole = "member")

private class FakeMemberActions : MemberActions {
    val calls = mutableListOf<String>()
    var members = listOf(MARTIN, EVA, JAN)
    var grants = MemberGrants(watch = 2, drive = 1)
    var refuse = false

    override suspend fun members(orgId: Long): List<OrgMemberRow> = members.also { calls += "members $orgId" }

    override suspend fun setRole(orgId: Long, personId: Long, role: String): List<OrgMemberRow> {
        calls += "role $personId $role"
        if (refuse) throw HubError.Tool("E_INVALID_STATE", "that is your own membership")
        members = members.map { if (it.personId == personId) it.copy(role = role) else it }
        return members
    }

    override suspend fun grants(orgId: Long, personId: Long): MemberGrants = grants.also { calls += "grants $personId" }

    override suspend fun narrow(orgId: Long, personId: Long): Int = grants.drive.also { calls += "narrow $personId" }

    override suspend fun remove(orgId: Long, personId: Long, keepGrants: Boolean): MemberRemoved {
        calls += "remove $personId keep=$keepGrants"
        members = members.filter { it.personId != personId }
        return MemberRemoved(removed = true, revokedGrants = if (keepGrants) 0 else grants.total)
    }
}

private val WITH_ORG_ADMIN = HubCapabilities(tools = setOf("org_admin"))

/**
 * Member actions on Company (redesign 11.10): an org's admins change a
 * member's role and remove them from the phone, deciding what happens to
 * what was shared with them; everyone else reads.
 */
class MembersTest {

    private fun TestScope.members(
        caps: HubCapabilities = WITH_ORG_ADMIN,
        actions: FakeMemberActions = FakeMemberActions(),
        canWrite: Boolean = true,
    ) = MembersViewModel(MembersFleet(caps), actions, backgroundScope, canWrite)

    @Test
    fun a_hub_without_org_admin_is_never_asked() = runTest {
        val actions = FakeMemberActions()
        val vm = members(caps = HubCapabilities(), actions = actions)
        runCurrent()
        vm.open(ACME_ADMIN)
        runCurrent()
        assertFalse(vm.state.value.available)
        assertFalse(vm.state.value.open)
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun an_admin_opens_the_members_and_may_act() = runTest {
        val vm = members()
        runCurrent()
        vm.open(ACME_ADMIN)
        runCurrent()
        assertEquals("Acme", vm.state.value.orgName)
        assertEquals(listOf(1L, 2L, 3L), vm.state.value.members.map { it.personId })
        assertTrue(vm.state.value.canAct)
    }

    @Test
    fun a_member_reads_and_changes_nothing() = runTest {
        val actions = FakeMemberActions()
        val vm = members(actions = actions)
        runCurrent()
        vm.open(ACME_MEMBER)
        runCurrent()
        assertFalse(vm.state.value.canAct)
        vm.setRole(EVA, "admin")
        vm.askRemove(EVA)
        runCurrent()
        assertEquals(listOf("members 7"), actions.calls)
    }

    @Test
    fun a_readonly_phone_changes_nothing_even_for_an_admin() = runTest {
        val actions = FakeMemberActions()
        val vm = members(actions = actions, canWrite = false)
        runCurrent()
        vm.open(ACME_ADMIN)
        runCurrent()
        vm.setRole(EVA, "viewer")
        runCurrent()
        assertFalse(vm.state.value.canAct)
        assertEquals(listOf("members 7"), actions.calls)
    }

    @Test
    fun a_role_change_takes_the_hubs_answer() = runTest {
        val actions = FakeMemberActions()
        val vm = members(actions = actions)
        runCurrent()
        vm.open(ACME_ADMIN)
        runCurrent()
        vm.setRole(EVA, "admin")
        runCurrent()
        assertEquals("admin", vm.state.value.members.single { it.personId == 2L }.role)
        assertEquals("eva is now an admin.", vm.state.value.notice)
        assertNull(vm.state.value.busy)
    }

    @Test
    fun a_refused_role_change_says_why() = runTest {
        val actions = FakeMemberActions().apply { refuse = true }
        val vm = members(actions = actions)
        runCurrent()
        vm.open(ACME_ADMIN)
        runCurrent()
        vm.setRole(EVA, "viewer")
        runCurrent()
        assertNotNull(vm.state.value.error)
        assertEquals("member", vm.state.value.members.single { it.personId == 2L }.role)
    }

    @Test
    fun the_hub_owner_and_the_same_role_are_never_sent() = runTest {
        val actions = FakeMemberActions()
        val vm = members(actions = actions)
        runCurrent()
        vm.open(ACME_ADMIN)
        runCurrent()
        vm.setRole(MARTIN, "member")
        vm.askRemove(MARTIN)
        vm.setRole(EVA, "member")
        vm.setRole(EVA, "owner")
        runCurrent()
        assertEquals(listOf("members 7"), actions.calls)
    }

    @Test
    fun remove_reads_the_shares_then_keeps_them_read_only() = runTest {
        val actions = FakeMemberActions()
        val vm = members(actions = actions)
        runCurrent()
        vm.open(ACME_ADMIN)
        runCurrent()
        vm.askRemove(EVA)
        runCurrent()
        assertEquals(MemberGrants(watch = 2, drive = 1), vm.state.value.removing?.grants)
        vm.remove(KeepShares.Narrow)
        runCurrent()
        assertEquals(listOf("members 7", "grants 2", "narrow 2", "remove 2 keep=true", "members 7"), actions.calls)
        assertNull(vm.state.value.removing)
        assertEquals(listOf(1L, 3L), vm.state.value.members.map { it.personId })
        assertEquals("Removed eva from Acme.", vm.state.value.notice)
    }

    @Test
    fun remove_and_stop_sharing_says_how_many_stopped() = runTest {
        val actions = FakeMemberActions()
        val vm = members(actions = actions)
        runCurrent()
        vm.open(ACME_ADMIN)
        runCurrent()
        vm.askRemove(EVA)
        runCurrent()
        vm.remove(KeepShares.Revoke)
        runCurrent()
        assertTrue("remove 2 keep=false" in actions.calls)
        assertFalse(actions.calls.any { it.startsWith("narrow") })
        assertEquals("Removed eva from Acme. 3 shares stopped.", vm.state.value.notice)
    }

    @Test
    fun cancel_removes_nobody() = runTest {
        val actions = FakeMemberActions()
        val vm = members(actions = actions)
        runCurrent()
        vm.open(ACME_ADMIN)
        runCurrent()
        vm.askRemove(JAN)
        runCurrent()
        vm.cancelRemove()
        vm.remove(KeepShares.Revoke)
        runCurrent()
        assertEquals(listOf("members 7", "grants 3"), actions.calls)
    }

    @Test
    fun the_remove_dialog_speaks_the_desktops_words() {
        val g = MemberGrants(watch = 2, drive = 1)
        assertEquals("3 sessions of Acme are shared with them (1 to drive, 2 to watch). What happens to those shares?", removeQuestion("Acme", g))
        assertEquals("1 session of Acme is shared with them (0 to drive, 1 to watch). What happens to those shares?", removeQuestion("Acme", MemberGrants(watch = 1)))
        assertNull(removeQuestion("Acme", MemberGrants()))
        assertEquals(KeepShares.entries.toList(), keepChoices(g))
        assertEquals(listOf(KeepShares.Revoke, KeepShares.Keep), keepChoices(MemberGrants(watch = 4)))
        assertEquals(emptyList<KeepShares>(), keepChoices(MemberGrants()))
        assertEquals("Stop sharing all 3 with them", keepLabel(KeepShares.Revoke, g))
        assertEquals("Keep them, but read only", keepLabel(KeepShares.Narrow, g))
    }

    @Test
    fun a_member_line_says_role_devices_and_when_they_joined() {
        assertEquals("Admin · owns the hub · Pixel 9", memberLine(MARTIN, 0))
        assertEquals("Viewer · no devices", memberLine(JAN, 0))
        assertEquals("Member · Eva's Mac · joined 2 d ago", memberLine(EVA.copy(addedAt = 1_000), 1_000 + 2 * 86_400))
    }

    @Test
    fun who_administers_an_org() {
        assertTrue(administers(ACME_ADMIN))
        assertFalse(administers(ACME_MEMBER))
        // The hub sends an org's devices only to whoever administers it.
        assertTrue(administers(OrgDetail(id = 7, devices = listOf(OrgDevice(name = "Pixel")))))
    }

    @Test
    fun the_hubs_member_row_parses() {
        val rows = json.decodeFromString(
            ListSerializer(OrgMemberRow.serializer()),
            """[{"person_id":1,"name":"martin","display_name":"Martin","role":"admin","added_at":1700000000,"owner":true,"devices":["Pixel 9"]},{"person_id":2,"name":"eva","role":"viewer","added_at":1700000100,"devices":[]}]""",
        )
        assertEquals("Martin", rows[0].label)
        assertTrue(rows[0].owner)
        assertFalse(rows[1].owner)
        assertEquals(1_700_000_100L, rows[1].addedAt)
        assertEquals(MemberGrants(watch = 2, drive = 1), json.decodeFromString(MemberGrants.serializer(), """{"watch":2,"drive":1}"""))
        assertEquals(3, json.decodeFromString(MemberRemoved.serializer(), """{"removed":true,"revoked_grants":3}""").revokedGrants)
    }
}
