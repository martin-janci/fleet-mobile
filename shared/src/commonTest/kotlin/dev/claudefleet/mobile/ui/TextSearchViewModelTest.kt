package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.SearchActions
import dev.claudefleet.mobile.model.SearchHit
import dev.claudefleet.mobile.model.SearchPage
import dev.claudefleet.mobile.net.HubCapabilities
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
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

    @Test
    fun the_hubs_marks_are_drawn_on_the_words_they_cover() {
        val mark = SpanStyle(fontWeight = FontWeight.Bold)
        val hit = SearchHit(kind = "item", ref = "1", title = "Fix login bug", titleMarks = listOf(listOf(4, 9)))
        val title = hit.markedTitle(mark)
        assertEquals(listOf("login"), title.spanStyles.map { title.text.substring(it.start, it.end) })

        // A range past the end, or empty, is not trusted.
        val bad = marked("abc", listOf(listOf(1, 9), listOf(2, 2), listOf(1)), mark)
        assertEquals(emptyList(), bad.spanStyles)
    }

    @Test
    fun a_snippets_marks_follow_their_words_when_its_spaces_fold() {
        val mark = SpanStyle(fontWeight = FontWeight.Bold)
        // "  the\n\n  login   bug ": "login" at 9..14, "bug" at 17..20.
        val hit = SearchHit(
            kind = "transcript", ref = "a:0", title = "api",
            snippet = "  the\n\n  login   bug ", snippetMarks = listOf(listOf(9, 14), listOf(17, 20)),
        )
        val line = hit.markedLine(mark)
        assertEquals("the login bug", line.text)
        assertEquals(listOf("login", "bug"), line.spanStyles.map { line.text.substring(it.start, it.end) })
        assertEquals("the login bug", hit.line())
    }
}
