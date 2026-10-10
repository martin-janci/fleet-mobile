package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.SearchActions
import dev.claudefleet.mobile.model.SearchHit
import dev.claudefleet.mobile.model.SearchPage
import dev.claudefleet.mobile.net.HubCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextSearchViewModelTest {

    private val hit = SearchHit(kind = "transcript", ref = "abc:0", title = "billing", snippet = "the login bug", sessionId = 7)

    private class Recorder(private val page: SearchPage) : SearchActions {
        val asked = mutableListOf<String>()
        override suspend fun search(query: String): SearchPage {
            asked += query
            return page
        }
    }

    private fun caps(search: Boolean) = MutableStateFlow(HubCapabilities(tools = if (search) setOf("search") else setOf("work")))

    @Test
    fun the_hub_is_asked_once_typing_pauses() = runTest {
        val actions = Recorder(SearchPage(listOf(hit), transcriptsIndexed = true))
        val vm = TextSearchViewModel(caps(true), actions, backgroundScope)
        vm.setQuery("log")
        vm.setQuery("logi")
        vm.setQuery(" login ")
        assertTrue(vm.state.value.loading)
        advanceTimeBy(TEXT_SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(listOf("login"), actions.asked, "only the last query, trimmed")
        assertEquals(TextSearchState("login", listOf(hit), transcriptsIndexed = true), vm.state.value)
    }

    @Test
    fun a_short_query_or_an_older_hub_asks_nothing() = runTest {
        val actions = Recorder(SearchPage(listOf(hit)))
        val vm = TextSearchViewModel(caps(true), actions, backgroundScope)
        vm.setQuery("lo")
        val old = TextSearchViewModel(caps(false), actions, backgroundScope)
        old.setQuery("login")
        advanceTimeBy(TEXT_SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(emptyList(), actions.asked)
        assertFalse(old.state.value.loading)
    }

    @Test
    fun a_failed_call_shows_nothing() = runTest {
        val vm = TextSearchViewModel(caps(true), { error("offline") }, backgroundScope)
        vm.setQuery("login")
        advanceTimeBy(TEXT_SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(TextSearchState("login"), vm.state.value)
    }

    @Test
    fun hits_join_the_search_only_for_their_own_query() {
        val local = searchEverywhere("login", emptyList(), emptyList(), ticketsAvailable = false)
        val joined = withText(local, TextSearchState("login", listOf(hit)), available = true)
        assertEquals(listOf(hit), joined.found)
        assertFalse(joined.isEmpty)

        val stale = withText(searchEverywhere("logout", emptyList(), emptyList(), false), TextSearchState("login", listOf(hit)), true)
        assertEquals(emptyList(), stale.found, "an older query's rows never show under a newer one")
        assertTrue(stale.textLoading)

        assertFalse(withText(local, TextSearchState("login", listOf(hit)), available = false).textAvailable)
    }

    @Test
    fun a_hit_opens_its_task_else_its_session() {
        assertTrue(hit.opens())
        assertEquals("the login bug", hit.line())
        assertEquals("Said", hit.kindLabel())
        val pr = SearchHit(kind = "pr", ref = "3", title = "Fix login", snippet = "Fix login", sessionName = "api", hostAlias = "pine")
        assertFalse(pr.opens(), "no task and no session: nothing to open")
        assertEquals("api · pine", pr.line(), "a snippet that repeats the title gives way to where it is")
        assertEquals("PAY-1", SearchHit(kind = "item", ref = "1", title = "Login", key = "PAY-1", taskId = "item:1").kindLabel())
    }
}
