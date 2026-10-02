package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.model.ActivityProbe
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SendPromptResult
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.ToolDetail
import dev.claudefleet.mobile.model.WaitResult
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Expanded tool rows read `session_tool_detail` once per call, only from a hub
 * that lists it, and a failure can be retried.
 */
class ToolDetailsModelTest {

    private class Fleet(tools: Set<String>) : FleetState {
        override val sessions = MutableStateFlow<List<SessionRow>>(emptyList())
        override val hosts = MutableStateFlow<List<HostRow>>(emptyList())
        override val projects = MutableStateFlow<List<ProjectRow>>(emptyList())
        override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("t"))
        override val hubVersion = MutableStateFlow<String?>(null)
        override val clockSkewSeconds = MutableStateFlow(0L)
        override val sessionChanges = MutableSharedFlow<Long>()
        override suspend fun refresh() = Unit
        override val capabilities = MutableStateFlow(HubCapabilities(tools = tools))
    }

    /** Only [toolDetail] is ever called by the model; the rest would be a bug. */
    private class Actions : SessionActions {
        val asked = mutableListOf<Pair<Long, String>>()
        var fail: Throwable? = null
        var gate: CompletableDeferred<Unit>? = null
        var result: String? = "ok"

        override suspend fun toolDetail(sessionId: Long, toolUseId: String): ToolDetail {
            asked += sessionId to toolUseId
            gate?.await()
            fail?.let { throw it }
            return ToolDetail(id = toolUseId, name = "Bash", result = result)
        }

        override suspend fun conversation(sessionId: Long, turns: Int?, sinceTurn: Long?): Conversation = error("unused")
        override suspend fun sendPrompt(sessionId: Long, text: String): SendPromptResult = error("unused")
        override suspend fun sendKeys(sessionId: Long, key: String): SendPromptResult = error("unused")
        override suspend fun activity(sessionId: Long): ActivityProbe = error("unused")
        override suspend fun capture(sessionId: Long, maxLines: Int): String = error("unused")
        override suspend fun waitForTurn(sessionId: Long, turn: Long, timeoutS: Int): WaitResult = error("unused")
        override suspend fun restart(sessionId: Long) = error("unused")
        override suspend fun safeKill(sessionId: Long) = error("unused")
        override suspend fun kill(sessionId: Long) = error("unused")
        override suspend fun setTags(sessionId: Long, tags: List<String>) = error("unused")
        override suspend fun rename(sessionId: Long, friendlyName: String) = error("unused")
        override suspend fun ping(): Boolean = error("unused")
    }

    private val withTool = setOf(HubCapabilities.SESSION_TOOL_DETAIL)

    @Test
    fun a_detail_is_read_once_and_kept() = runTest {
        val actions = Actions()
        val model = ToolDetailsModel(7, Fleet(withTool), actions, this)
        model.request("tu_1")
        model.request("tu_1")
        runCurrent()
        model.request("tu_1")
        runCurrent()
        assertEquals(listOf(7L to "tu_1"), actions.asked)
        val loaded = assertIs<ToolDetailLoad.Loaded>(model.states.value["tu_1"])
        assertEquals("ok", loaded.detail.result)
    }

    @Test
    fun an_older_hub_is_never_asked() = runTest {
        val actions = Actions()
        val model = ToolDetailsModel(7, Fleet(emptySet()), actions, this)
        model.request("tu_1")
        runCurrent()
        assertEquals(emptyList(), actions.asked)
        assertEquals(emptyMap(), model.states.value)
    }

    /**
     * A TRANSIENT failure can be retried, and only the row's Retry retries it.
     *
     * The old spelling of this test used `E_NOT_FOUND`, a code the hub does not
     * emit, and then asserted that the very same request succeeded on a second
     * try — a sequence the real `E_NOTFOUND` can never produce. The two cases
     * are now separate, because a plain `request` must NOT re-read after a
     * failure: the row opens with a `LaunchedEffect` that fires again on every
     * scroll back into a keyed `LazyColumn`, and each read is a transcript grep
     * over SSH.
     */
    @Test
    fun loading_shows_while_the_call_is_out_and_a_transient_failure_retries_only_when_asked() = runTest {
        val actions = Actions()
        actions.gate = CompletableDeferred()
        actions.fail = HubError.Transport(IllegalStateException("reset"))
        val model = ToolDetailsModel(7, Fleet(withTool), actions, this)
        model.request("tu_1")
        runCurrent()
        assertEquals(ToolDetailLoad.Loading, model.states.value["tu_1"])
        actions.gate!!.complete(Unit)
        runCurrent()
        val failed = assertIs<ToolDetailLoad.Failed>(model.states.value["tu_1"])
        assertTrue(failed.retryable)
        assertNull(failed.said, "a transport failure is not the hub speaking")

        // A scroll back into view re-runs the row's effect: no second read.
        actions.fail = null
        model.request("tu_1")
        runCurrent()
        assertIs<ToolDetailLoad.Failed>(model.states.value["tu_1"])
        assertEquals(1, actions.asked.size)

        // The Retry button does read again.
        model.request("tu_1", force = true)
        runCurrent()
        assertIs<ToolDetailLoad.Loaded>(model.states.value["tu_1"])
        assertEquals(2, actions.asked.size)
    }

    /**
     * A refusal with a terminal code will read the same way however often it is
     * asked, so it carries what the hub said and is never read again — not by
     * the row's effect and not by a Retry, which is not offered.
     */
    @Test
    fun a_terminal_refusal_is_never_read_again() = runTest {
        val actions = Actions()
        actions.fail = HubError.Tool("E_NOTFOUND", "no such tool call in the transcript")
        val model = ToolDetailsModel(7, Fleet(withTool), actions, this)
        model.request("tu_1")
        runCurrent()
        val failed = assertIs<ToolDetailLoad.Failed>(model.states.value["tu_1"])
        assertFalse(failed.retryable)
        assertEquals("no such tool call in the transcript", failed.said)

        model.request("tu_1")
        model.request("tu_1", force = true)
        runCurrent()
        assertEquals(1, actions.asked.size, "asked once, however often it is asked")
    }

    @Test
    fun a_detail_read_while_running_is_read_again_once_the_call_is_done() = runTest {
        val actions = Actions()
        actions.result = null
        val model = ToolDetailsModel(7, Fleet(withTool), actions, this)
        model.request("tu_1", done = false)
        runCurrent()
        // Still running: nothing new to read.
        model.request("tu_1", done = false)
        runCurrent()
        assertEquals(1, actions.asked.size)

        actions.result = "finished"
        model.request("tu_1", done = true)
        runCurrent()
        assertEquals(2, actions.asked.size)
        assertEquals("finished", (model.states.value["tu_1"] as ToolDetailLoad.Loaded).detail.result)
    }
}
