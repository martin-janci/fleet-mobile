package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.CompanyActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.ORGS
import dev.claudefleet.mobile.model.OrgDetail
import dev.claudefleet.mobile.net.HubCapabilities
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

data class CompanyUiState(
    val available: Boolean = false,
    /** The hub has said what it serves; before that nothing reads as "not offered" (r13 P20). */
    val capsKnown: Boolean = false,
    val orgs: List<OrgDetail> = emptyList(),
    /** The org whose detail is open, by id; null shows the list. */
    val openId: Long? = null,
    val loading: Boolean = false,
    val error: Friendly? = null,
    /** The hub has answered the list at least once. */
    val loaded: Boolean = false,
    /** The last list read failed: the screen says so rather than "No organisations". */
    val listFailed: Boolean = false,
) {
    val open: OrgDetail? get() = openId?.let { id -> orgs.firstOrNull { it.id == id } }
}

/**
 * Settings → Company: the desktop's org overview, read-only. Each org's
 * hosts, sessions, spend against its budgets, devices and members — as much
 * of it as the hub sends this phone's token, which is the hub's decision
 * (company administration phases A–D), never the phone's. Adding members,
 * changing roles, pairing devices and setting budgets stay on the desktop.
 */
class CompanyViewModel(
    private val fleet: FleetState,
    private val actions: CompanyActions,
    private val scope: CoroutineScope,
) {
    private data class Local(
        val orgs: List<OrgDetail> = emptyList(),
        val openId: Long? = null,
        val loading: Boolean = false,
        val error: Friendly? = null,
        val loaded: Boolean = false,
        val listFailed: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<CompanyUiState> = combine(local, fleet.capabilities) { l, caps ->
        CompanyUiState(
            available = caps.has(HubCapabilities.WORK, ORGS),
            capsKnown = caps.known,
            orgs = l.orgs,
            openId = l.openId,
            loading = l.loading,
            error = l.error,
            loaded = l.loaded,
            listFailed = l.listFailed,
        )
    }.stateIn(scope, SharingStarted.Eagerly, CompanyUiState())

    fun load(): Job = scope.launch { read() }

    fun refresh(): Job = scope.launch { read() }

    fun open(orgId: Long) {
        local.update { it.copy(openId = orgId) }
    }

    /** Back from an org's detail to the list; false when the list is showing. */
    fun close(): Boolean {
        if (local.value.openId == null) return false
        local.update { it.copy(openId = null) }
        return true
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    private suspend fun read() {
        if (!fleet.capabilities.value.has(HubCapabilities.WORK, ORGS)) return
        local.update { it.copy(loading = true, error = null) }
        try {
            val orgs = actions.orgs().sortedBy { it.name.lowercase() }
            // An org that left the list while its detail was open closes it.
            local.update { l -> l.copy(orgs = orgs, openId = l.openId?.takeIf { id -> orgs.any { it.id == id } }, loaded = true, listFailed = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t), listFailed = true) }
        } finally {
            local.update { it.copy(loading = false) }
        }
    }
}

/** A role as a list draws it. */
internal fun roleWord(role: String): String = when (role) {
    "admin" -> "Admin"
    "member" -> "Member"
    "viewer" -> "Viewer"
    else -> role.replaceFirstChar { it.uppercase() }
}

/**
 * Spent against a budget in whole USD: "$12.40 of $50", and the bare figure
 * when there is no budget (`0` or absent); null when the hub sent no spend.
 */
internal fun spendAgainst(micros: Long?, budgetUsd: Long?): String? {
    micros ?: return null
    val spent = dev.claudefleet.mobile.ui.components.formatUsd(micros)
    return if (budgetUsd != null && budgetUsd > 0) "$spent of $$budgetUsd" else spent
}
