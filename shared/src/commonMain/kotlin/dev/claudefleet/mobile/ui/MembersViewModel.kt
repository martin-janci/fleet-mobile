package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.MemberActions
import dev.claudefleet.mobile.model.MemberGrants
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.model.OrgMemberRow
import dev.claudefleet.mobile.model.relativeAgo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What happens to the shares a removed member had on the org's sessions (the desktop's three choices). */
enum class KeepShares(val label: String) {
    Revoke("Stop sharing them"),
    Narrow("Keep them, but read only"),
    Keep("Keep them as they are"),
}

/** A Remove pressed and its shares read: the dialog that asks what happens to them. */
data class RemoveAsk(val member: OrgMemberRow, val grants: MemberGrants)

data class MembersUiState(
    /** The hub serves `org_admin` to this token. */
    val available: Boolean = false,
    /** This person administers the open org and the phone may write. */
    val canAct: Boolean = false,
    val orgId: Long? = null,
    val orgName: String = "",
    val loading: Boolean = false,
    val members: List<OrgMemberRow> = emptyList(),
    /** The person whose change is on the wire. */
    val busy: Long? = null,
    val removing: RemoveAsk? = null,
    val notice: String? = null,
    val error: Friendly? = null,
) {
    val open: Boolean get() = orgId != null
}

/**
 * An org's members on the phone (redesign 11.10, member actions on Company):
 * who is in it, their role and devices, with Change role and Remove for the
 * org's admins. The hub decides: it refuses anyone who does not administer
 * the org, and an admin changing their own membership or the hub owner's.
 * Adding a member pairs a device, so it stays on the desktop.
 */
class MembersViewModel(
    private val fleet: FleetState,
    private val actions: MemberActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val orgId: Long? = null,
        val orgName: String = "",
        val admin: Boolean = false,
        val loading: Boolean = false,
        val members: List<OrgMemberRow> = emptyList(),
        val busy: Long? = null,
        val removing: RemoveAsk? = null,
        val notice: String? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<MembersUiState> = combine(local, fleet.capabilities) { l, caps ->
        MembersUiState(
            available = caps.orgAdmin,
            canAct = canWrite && caps.orgAdmin && l.admin,
            orgId = l.orgId,
            orgName = l.orgName,
            loading = l.loading,
            members = l.members,
            busy = l.busy,
            removing = l.removing,
            notice = l.notice,
            error = l.error,
        )
    }.stateIn(scope, SharingStarted.Eagerly, MembersUiState())

    /** Open [org]'s members; its admins (the hub sent them its devices, or their role) may change them. */
    fun open(org: OrgDetail): Job = scope.launch {
        if (!fleet.capabilities.value.orgAdmin) return@launch
        local.value = Local(orgId = org.id, orgName = org.name.ifBlank { "Organisation ${org.id}" }, admin = administers(org), loading = true)
        try {
            val members = actions.members(org.id)
            local.update { if (it.orgId == org.id) it.copy(loading = false, members = members) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(loading = false, error = friendly(t)) }
        }
    }

    fun close() {
        local.value = Local()
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /** Give [member] [role]; the list takes the hub's answer. */
    fun setRole(member: OrgMemberRow, role: String): Job = scope.launch {
        val org = local.value.orgId ?: return@launch
        if (!state.value.canAct || local.value.busy != null || role == member.role || role !in ROLES || member.owner) return@launch
        local.update { it.copy(busy = member.personId, error = null, notice = null) }
        try {
            val members = actions.setRole(org, member.personId, role)
            local.update { it.copy(busy = null, members = members, notice = "${member.label} is now ${article(role)} ${roleWord(role).lowercase()}.") }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
    }

    /** Remove pressed: read what is shared with them first, so the dialog can ask about it. */
    fun askRemove(member: OrgMemberRow): Job = scope.launch {
        val org = local.value.orgId ?: return@launch
        if (!state.value.canAct || local.value.busy != null || member.owner) return@launch
        local.update { it.copy(busy = member.personId, error = null, notice = null) }
        try {
            val grants = actions.grants(org, member.personId)
            local.update { it.copy(busy = null, removing = RemoveAsk(member, grants)) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
    }

    fun cancelRemove() {
        local.update { it.copy(removing = null) }
    }

    /** Remove the member being asked about, doing [shares] with what was shared with them. */
    fun remove(shares: KeepShares): Job = scope.launch {
        val org = local.value.orgId ?: return@launch
        val ask = local.value.removing ?: return@launch
        if (!state.value.canAct || local.value.busy != null) return@launch
        val member = ask.member
        local.update { it.copy(removing = null, busy = member.personId, error = null, notice = null) }
        try {
            if (shares == KeepShares.Narrow && ask.grants.drive > 0) actions.narrow(org, member.personId)
            val removed = actions.remove(org, member.personId, keepGrants = shares != KeepShares.Revoke)
            val members = actions.members(org)
            local.update {
                it.copy(
                    busy = null,
                    members = members,
                    notice = if (removed.removed) removedNotice(member, it.orgName, removed.revokedGrants) else "${member.label} was not in it.",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
    }

    companion object {
        val ROLES = listOf("admin", "member", "viewer")
    }
}

/**
 * Whether this person administers [org]: the hub sends an org's devices only
 * to whoever administers it, and says the caller's role to a member.
 */
fun administers(org: OrgDetail): Boolean = org.myRole == "admin" || org.devices != null

private fun article(role: String) = if (role == "admin") "an" else "a"

private fun removedNotice(member: OrgMemberRow, org: String, revoked: Int): String =
    "Removed ${member.label} from $org." + when (revoked) {
        0 -> ""
        1 -> " 1 share stopped."
        else -> " $revoked shares stopped."
    }

/** "Admin · owns the hub · Pixel 9, MacBook · joined 3 days ago". */
fun memberLine(m: OrgMemberRow, nowSeconds: Long): String = listOfNotNull(
    roleWord(m.role),
    "owns the hub".takeIf { m.owner },
    m.devices.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "no devices",
    relativeAgo(m.addedAt.takeIf { it > 0 }, nowSeconds)?.let { "joined $it" },
).joinToString(" · ")

/**
 * The remove dialog's question, in the desktop's words: "3 sessions of Acme
 * are shared with them (1 to drive, 2 to watch). What happens to those
 * shares?"; null when nothing is shared, which needs no choice.
 */
fun removeQuestion(org: String, grants: MemberGrants): String? {
    if (grants.total == 0) return null
    val sessions = if (grants.total == 1) "1 session of $org is" else "${grants.total} sessions of $org are"
    return "$sessions shared with them (${grants.drive} to drive, ${grants.watch} to watch). What happens to those shares?"
}

/** The choices a remove offers: narrowing means something only when some share drives. */
fun keepChoices(grants: MemberGrants): List<KeepShares> = when {
    grants.total == 0 -> emptyList()
    grants.drive == 0 -> listOf(KeepShares.Revoke, KeepShares.Keep)
    else -> KeepShares.entries.toList()
}

/** A choice as the dialog says it: "Stop sharing all 3 with them". */
fun keepLabel(choice: KeepShares, grants: MemberGrants): String = when (choice) {
    KeepShares.Revoke -> if (grants.total == 1) "Stop sharing it with them" else "Stop sharing all ${grants.total} with them"
    else -> choice.label
}
