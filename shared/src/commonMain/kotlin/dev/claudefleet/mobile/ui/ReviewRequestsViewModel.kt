package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ReviewRequestActions
import dev.claudefleet.mobile.model.ReviewRequest
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The Inbox's review asks: the list, and which ones have a Done on the wire. */
data class ReviewRequestsUiState(
    val asks: List<ReviewRequest> = emptyList(),
    val busy: Set<Long> = emptySet(),
    /** What the last Done that failed said, by ask. */
    val notices: Map<Long, String> = emptyMap(),
)

/*
 * Reviews a colleague asked of this person (claude-fleet "Ask for review…",
 * migration 225). A hub without `review_request` answers an unknown tool:
 * the list stays empty and nothing is drawn.
 */
class ReviewRequestsViewModel(
    private val actions: ReviewRequestActions,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(ReviewRequestsUiState())
    val state: StateFlow<ReviewRequestsUiState> = _state.asStateFlow()

    fun refresh(): Job = scope.launch {
        try {
            val asks = actions.list()
            _state.update { it.copy(asks = asks) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: HubError.Tool) {
            // An older hub, or a device the hub serves no asks: nothing to show.
            _state.update { it.copy(asks = emptyList()) }
        } catch (_: Throwable) {
            // Keep the last list: a dropped connection is not "nothing was asked".
        }
    }

    fun done(r: ReviewRequest): Job? {
        if (r.id in _state.value.busy) return null
        _state.update { it.copy(busy = it.busy + r.id, notices = it.notices - r.id) }
        return scope.launch {
            try {
                actions.done(r.id)
                _state.update { s -> s.copy(asks = s.asks.filterNot { it.id == r.id }, busy = s.busy - r.id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(busy = it.busy - r.id, notices = it.notices + (r.id to (e.message ?: "Could not mark it done"))) }
            }
        }
    }
}
