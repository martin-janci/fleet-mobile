package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.PrFilter
import dev.claudefleet.mobile.model.PullRequest
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.PRS
import dev.claudefleet.mobile.net.HubCapabilities.Companion.PRS_LIST
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.isUnknownAction
import dev.claudefleet.mobile.utcOffsetSeconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

data class PullRequestsUiState(
    /** The hub serves `prs { list }`: My work shows the Pull requests chip. */
    val available: Boolean = false,
    val open: Boolean = false,
    val filter: PrFilter = PrFilter.OPEN,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val items: List<PullRequest> = emptyList(),
    /** Every PR matching [filter], not only the [items] loaded. */
    val total: Int = 0,
    val connected: Boolean = false,
    /** "Offline · as of 10:42" over rows kept from before the connection went. */
    val stale: String? = null,
    val error: Friendly? = null,
)

/**
 * Work's **Pull requests** sheet on the phone (claude-fleet redesign 6.7):
 * the desktop's Work › Pull requests, read through the same `prs { list }`
 * (6.4, contract revision 12). Readonly, so a readonly token sees it too; the
 * hub serves a row only to a token that may see the session that opened it.
 *
 * Drawn only when the hub's `tools/list` names `prs` — an older hub has no
 * such tool, and nothing is offered to be refused. Open first, as on the
 * desktop; a filter change re-reads. Offline the last rows stay, marked with
 * their age.
 */
class PullRequestsViewModel(
    private val fleet: FleetState,
    private val actions: WorkActions,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { epochSeconds() },
    private val utcOffset: (Long) -> Int = ::utcOffsetSeconds,
) {
    private data class Local(
        val open: Boolean = false,
        val filter: PrFilter = PrFilter.OPEN,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val items: List<PullRequest> = emptyList(),
        val total: Int = 0,
        /** Bumped by every read: an answer for a filter no longer shown is dropped. */
        val generation: Long = 0,
        val asOf: Long? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<PullRequestsUiState> =
        combine(fleet.capabilities, fleet.status, local) { caps, status, l -> assemble(caps, status, l) }
            .stateIn(scope, SharingStarted.Eagerly, assemble(fleet.capabilities.value, fleet.status.value, local.value))

    fun open(): Job? {
        if (!fleet.capabilities.value.pullRequests) return null
        local.update { it.copy(open = true, error = null) }
        return reload()
    }

    fun close() {
        local.update { it.copy(open = false, error = null) }
    }

    /** Open / Merged / Closed / All: the list is re-read for it. */
    fun setFilter(filter: PrFilter): Job? {
        if (filter == local.value.filter) return null
        local.update { it.copy(filter = filter, items = emptyList(), total = 0, loaded = false, error = null) }
        return reload()
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    fun reload(): Job = scope.launch { load() }

    private suspend fun load() {
        if (!fleet.capabilities.value.pullRequests) return
        if (!fleet.status.value.isConnected()) return
        val asked = local.value.filter
        val generation = local.updateAndGet { it.copy(loading = true, generation = it.generation + 1) }.generation
        try {
            val page = actions.pullRequests(asked.wire, limit = LIMIT)
            local.update { l ->
                if (l.generation != generation) return@update l
                l.copy(loading = false, loaded = true, items = page.items, total = page.total, asOf = clock(), error = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(PRS, PRS_LIST)
            local.update { l -> if (l.generation != generation) l else l.copy(loading = false, error = friendlyWork(t)) }
        }
    }

    private fun assemble(caps: HubCapabilities, status: ConnectionStatus, l: Local): PullRequestsUiState {
        if (!caps.pullRequests) return PullRequestsUiState()
        val connected = status.isConnected()
        return PullRequestsUiState(
            available = true,
            open = l.open,
            filter = l.filter,
            loading = l.loading,
            loaded = l.loaded,
            items = l.items,
            total = l.total,
            connected = connected,
            stale = if (!connected && l.loaded) l.asOf?.let { staleLine(it, utcOffset(it)) } else null,
            error = l.error,
        )
    }

    private companion object {
        /** The hub's default page (it caps one at 500). */
        const val LIMIT = 200
    }
}
