package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.model.ReopenedWork
import dev.claudefleet.mobile.model.TidyApplyResult
import dev.claudefleet.mobile.model.TidyCandidate
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

data class TidyUiState(
    /** The hub plans Tidy-up and this pairing may apply it. */
    val available: Boolean = false,
    val open: Boolean = false,
    val loading: Boolean = false,
    val candidates: List<TidyCandidate> = emptyList(),
    val ticked: Set<Long> = emptySet(),
    val chosen: Map<Long, TidyChoice> = emptyMap(),
    val reopened: List<ReopenedWork> = emptyList(),
    val applying: Boolean = false,
    val results: List<TidyApplyResult>? = null,
    val error: Friendly? = null,
) {
    fun choiceOf(c: TidyCandidate): TidyChoice? = chosen[c.sessionId] ?: tidyDefault(c)

    /** Kills among what would be applied — asked about separately before they go. */
    val kills: Int
        get() = candidates.count { it.sessionId in ticked && choiceOf(it).let { c -> c == TidyChoice.Kill || c == TidyChoice.SafeKill } }
}

/**
 * The desktop's Tidy-up on the phone: what the hub suggests retiring — done
 * and idle, merged and idle, won't-do, duplicates, ghosts about to expire,
 * idle with no work — each with the choices its rule allows, the suggested
 * ones ticked; Apply sends the ticked choices (`tidy_apply`). Nothing is
 * applied untouched by a person, and a kill can still need the desktop's
 * confirmation. Below it, work that came back after it was done.
 */
class TidyViewModel(
    private val fleet: FleetState,
    private val actions: WorkActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val open: Boolean = false,
        val loading: Boolean = false,
        val candidates: List<TidyCandidate> = emptyList(),
        val ticked: Set<Long> = emptySet(),
        val chosen: Map<Long, TidyChoice> = emptyMap(),
        val reopened: List<ReopenedWork> = emptyList(),
        val applying: Boolean = false,
        val results: List<TidyApplyResult>? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<TidyUiState> = combine(local, fleet.capabilities) { l, caps ->
        TidyUiState(
            available = canWrite && caps.has(HubCapabilities.WORK, "tidy") && caps.has(HubCapabilities.WORK_LINK, "tidy_apply"),
            open = l.open,
            loading = l.loading,
            candidates = l.candidates,
            ticked = l.ticked,
            chosen = l.chosen,
            reopened = l.reopened,
            applying = l.applying,
            results = l.results,
            error = l.error,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TidyUiState())

    fun open(): Job = scope.launch {
        local.update { it.copy(open = true, results = null, error = null) }
        read()
    }

    fun close() {
        local.update { it.copy(open = false) }
    }

    fun toggle(sessionId: Long) {
        local.update { it.copy(ticked = if (sessionId in it.ticked) it.ticked - sessionId else it.ticked + sessionId) }
    }

    fun choose(sessionId: Long, choice: TidyChoice) {
        local.update { it.copy(chosen = it.chosen + (sessionId to choice), ticked = it.ticked + sessionId) }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /** Apply the ticked choices, then read again — what was retired is gone, what failed stays. */
    fun apply(): Job = scope.launch {
        val l = local.value
        if (!state.value.available || l.applying) return@launch
        val items = tidyItems(l.candidates, l.ticked, l.chosen)
        if (items.isEmpty()) return@launch
        local.update { it.copy(applying = true, error = null) }
        try {
            val results = actions.tidyApply(items)
            local.update { it.copy(applying = false, results = results) }
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(applying = false, error = friendly(t)) }
        }
    }

    /** Clear an item's "reopened". */
    fun dismissReopened(itemId: Long): Job = scope.launch {
        try {
            actions.dismissReopened(itemId)
            local.update { l -> l.copy(reopened = l.reopened.filter { it.itemId != itemId }) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        }
    }

    private suspend fun read() {
        local.update { it.copy(loading = true) }
        try {
            val report = actions.tidy()
            val caps = fleet.capabilities.value
            val reopened = if (caps.has(HubCapabilities.WORK, "reopened")) actions.reopened() else emptyList()
            local.update { l ->
                val known = l.candidates.mapTo(HashSet()) { it.sessionId }
                // New candidates come in with the suggested tick; ones already
                // shown keep whatever the person did with theirs.
                val ticked = report.candidates.filter { c -> if (c.sessionId in known) c.sessionId in l.ticked else tidyPreselected(c) }
                    .mapTo(HashSet()) { it.sessionId }
                l.copy(loading = false, candidates = report.candidates, ticked = ticked, reopened = reopened)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(loading = false, error = friendly(t)) }
        }
    }
}
