package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.SearchActions
import dev.claudefleet.mobile.model.SearchHit
import dev.claudefleet.mobile.net.HubCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the hub's full-text index found for [query]. */
data class TextSearchState(
    /** The query these hits answer, trimmed; blank when none was asked. */
    val query: String = "",
    val hits: List<SearchHit> = emptyList(),
    /** The hub indexes what was said; without it a conversation is found by its first prompt. */
    val transcriptsIndexed: Boolean = false,
    val loading: Boolean = false,
)

/** Fewer letters than this find too much to be worth a round trip (the desktop's ⌘K waits for as many). */
internal const val TEXT_SEARCH_MIN = 3

/** How long typing must pause before the hub is asked. */
internal const val TEXT_SEARCH_DEBOUNCE_MS = 300L

/**
 * Search everywhere's Text rows: the Sessions search's query, sent to the
 * hub's `search` once typing pauses. Only against a hub that lists the tool;
 * an older one leaves the search to what the phone already holds. A failed
 * call shows nothing rather than an error: the rows are extra, the sessions
 * and the rest of the search still answer.
 */
class TextSearchViewModel(
    private val capabilities: StateFlow<HubCapabilities>,
    private val actions: SearchActions,
    private val scope: CoroutineScope,
) {
    private val local = MutableStateFlow(TextSearchState())
    val state: StateFlow<TextSearchState> = local.asStateFlow()

    private var job: Job? = null

    fun setQuery(query: String) {
        val q = query.trim()
        job?.cancel()
        if (q.length < TEXT_SEARCH_MIN || !capabilities.value.search) {
            local.value = TextSearchState(query = q)
            return
        }
        local.update { it.copy(query = q, loading = true) }
        job = scope.launch {
            delay(TEXT_SEARCH_DEBOUNCE_MS)
            try {
                val page = actions.search(q)
                local.value = TextSearchState(q, page.hits, page.transcriptsIndexed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                local.value = TextSearchState(query = q)
            }
        }
    }
}
