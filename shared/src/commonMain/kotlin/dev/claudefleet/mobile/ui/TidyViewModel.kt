package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.model.ReopenedWork
import dev.claudefleet.mobile.model.TidyApplyItem
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
    /** Each result's session by name, kept from before the re-read that drops it from the list. */
    val resultNames: Map<Long, String> = emptyMap(),
    /** Archives taken back with Undo. */
    val undone: Set<Long> = emptySet(),
    /** Sessions whose Undo or Retry is on the wire. */
    val pending: Set<Long> = emptySet(),
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
    /**
     * Whether a new candidate comes in ticked. Classic ticks the rule's
     * suggestions; the New layout (redesign 14.15) opens with nothing ticked,
     * so nothing is applied that a person did not pick.
     */
    private val preselect: () -> Boolean = { true },
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
        val resultNames: Map<Long, String> = emptyMap(),
        val sent: Map<Long, TidyApplyItem> = emptyMap(),
        val undone: Set<Long> = emptySet(),
        val pending: Set<Long> = emptySet(),
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
            resultNames = l.resultNames,
            undone = l.undone,
            pending = l.pending,
        )
    }.stateIn(scope, SharingStarted.Eagerly, TidyUiState())

    fun open(): Job = scope.launch {
        local.update { it.copy(open = true, results = null, error = null, resultNames = emptyMap(), sent = emptyMap(), undone = emptySet(), pending = emptySet()) }
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
        val names = l.candidates.associate { c -> c.sessionId to (c.label?.takeIf { it.isNotBlank() } ?: c.tmuxName) }
        local.update { it.copy(applying = true, error = null) }
        try {
            val results = actions.tidyApply(items)
            local.update {
                it.copy(
                    applying = false,
                    results = results,
                    resultNames = names.filterKeys { id -> results.any { r -> r.sessionId == id } },
                    sent = items.associateBy { i -> i.sessionId },
                    undone = emptySet(),
                )
            }
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(applying = false, error = friendly(t)) }
        }
    }

    /**
     * **Undo** on an archive in the result: `tidy_apply` with `unarchive`
     * for the same link, then a re-read so the session is a candidate again.
     */
    fun undo(sessionId: Long): Job? {
        val l = local.value
        val sent = l.sent[sessionId] ?: return null
        if (!tidyUndoable(sent) || sessionId in l.undone || sessionId in l.pending) return null
        if (l.results.orEmpty().none { it.sessionId == sessionId && it.ok }) return null
        return redo(sessionId, TidyApplyItem(sessionId = sessionId, action = UNARCHIVE, linkId = sent.linkId)) { ok ->
            if (ok) copy(undone = undone + sessionId) else this
        }
    }

    /** **Retry** a choice the hub refused: the same item again; its row in the result takes the new answer. */
    fun retry(sessionId: Long): Job? {
        val l = local.value
        val sent = l.sent[sessionId] ?: return null
        if (sessionId in l.pending || l.results.orEmpty().none { it.sessionId == sessionId && !it.ok }) return null
        return redo(sessionId, sent) { this }
    }

    private fun redo(sessionId: Long, item: TidyApplyItem, after: Local.(Boolean) -> Local): Job? {
        if (!state.value.available) return null
        local.update { it.copy(pending = it.pending + sessionId, error = null) }
        return scope.launch {
            try {
                val answer = actions.tidyApply(listOf(item)).firstOrNull { it.sessionId == sessionId }
                local.update { l ->
                    val ok = answer?.ok == true
                    // A Retry's answer replaces the row's; an Undo leaves the archive's row and marks it.
                    val results = if (item.action == UNARCHIVE || answer == null) l.results else l.results?.map { if (it.sessionId == sessionId) answer else it }
                    val withError = if (item.action == UNARCHIVE && !ok) {
                        l.copy(error = Friendly("Could not undo", answer?.error ?: "The hub did not take the undo.", isError = true))
                    } else l
                    withError.copy(results = results, pending = l.pending - sessionId).after(ok)
                }
                read()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(pending = it.pending - sessionId, error = friendly(t)) }
            }
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
                val ticks = preselect()
                val ticked = report.candidates.filter { c -> if (c.sessionId in known) c.sessionId in l.ticked else ticks && tidyPreselected(c) }
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

private const val UNARCHIVE = "unarchive"
