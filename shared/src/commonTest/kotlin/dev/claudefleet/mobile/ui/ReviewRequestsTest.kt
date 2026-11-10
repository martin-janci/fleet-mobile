package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ReviewRequestActions
import dev.claudefleet.mobile.model.ReviewRequest
import dev.claudefleet.mobile.model.reviewAskLine
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Reviews a colleague asked of this person (claude-fleet "Ask for review…"). */
class ReviewRequestsTest {
    private class Fake(var asks: List<ReviewRequest>, val refuse: Boolean = false) : ReviewRequestActions {
        val done = mutableListOf<Long>()
        override suspend fun list(): List<ReviewRequest> {
            if (refuse) throw HubError.Tool("E_UNKNOWN_TOOL", "no such tool")
            return asks
        }
        override suspend fun done(id: Long): ReviewRequest {
            done += id
            return asks.first { it.id == id }.copy(doneAt = 9)
        }
    }

    private fun ask(id: Long, level: String = "drive") =
        ReviewRequest(id = id, sessionId = 7, fromName = "Martin", toName = "Ada", level = level, createdAt = 100)

    @Test
    fun the_asks_list_and_done_takes_one_off() = runTest {
        val fake = Fake(listOf(ask(1), ask(2)))
        val vm = ReviewRequestsViewModel(fake, backgroundScope)
        vm.refresh().join()
        assertEquals(listOf(1L, 2L), vm.state.value.asks.map { it.id })
        vm.done(ask(1))?.join()
        assertEquals(listOf(1L), fake.done)
        assertEquals(listOf(2L), vm.state.value.asks.map { it.id })
    }

    @Test
    fun an_older_hub_shows_nothing() = runTest {
        val vm = ReviewRequestsViewModel(Fake(listOf(ask(1)), refuse = true), backgroundScope)
        vm.refresh().join()
        assertTrue(vm.state.value.asks.isEmpty())
    }

    @Test
    fun a_hub_that_does_not_serve_the_tool_is_not_asked_and_a_failed_done_says_it_plainly() = runTest {
        // Review requests 8: asked on every visit whether served or not; a raw error on Done.
        val fake = object : ReviewRequestActions by Fake(listOf(ask(1))) {
            var lists = 0
            override suspend fun list(): List<ReviewRequest> { lists++; return listOf(ask(1)) }
            override suspend fun done(id: Long): ReviewRequest = throw HubError.Transport(IllegalStateException("socket closed: secret detail"))
        }
        val caps = kotlinx.coroutines.flow.MutableStateFlow(dev.claudefleet.mobile.net.HubCapabilities())
        val vm = ReviewRequestsViewModel(fake, backgroundScope, caps)
        vm.refresh().join()
        assertEquals(0, fake.lists)
        caps.value = dev.claudefleet.mobile.net.HubCapabilities.of(dev.claudefleet.mobile.net.ToolCatalog(setOf("review_request")))
        vm.refresh().join()
        assertEquals(1, fake.lists)
        vm.done(ask(1))?.join()
        val notice = vm.state.value.notices[1L]
        assertTrue(notice != null && "socket closed" !in notice, "the friendly words, not the transport's: $notice")
    }

    @Test
    fun the_line_says_who_asked_and_what_this_person_may_do() {
        assertEquals("Martin asks you to review · you can send notes", reviewAskLine(ask(1)))
        assertEquals("Martin asks you to review · you can read and draft", reviewAskLine(ask(1, "watch")))
        val wire = json.decodeFromString<ReviewRequest>(
            """{"id":3,"session_id":7,"from_person_id":1,"from_name":"Martin","to_person_id":2,"to_name":"Ada","level":"watch","note":"retry loop","created_at":5}""",
        )
        assertEquals("retry loop", wire.note)
        assertEquals(7L, wire.sessionId)
    }
}
