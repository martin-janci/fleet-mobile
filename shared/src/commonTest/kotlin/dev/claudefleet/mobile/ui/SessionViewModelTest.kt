@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SendPromptResult
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ID = 42L

private fun row(
    status: String? = "working",
    stuck: String? = null,
    activity: String? = null,
) = SessionRow(
    id = ID,
    tmuxName = "fleet-api",
    friendlyName = "API work",
    hostAlias = "pine",
    projectId = 1,
    claudeStatus = status,
    stuckKind = stuck,
    currentActivity = activity,
)

private fun turn(at: String, prompt: String, vararg items: ConvItem) =
    ConvTurn(prompt = prompt, at = at, endedAt = at, items = items.toList())

private fun text(s: String) = ConvItem.Text(s)

private class FakeFleetState(rows: List<SessionRow> = listOf(row())) : FleetState {
    override val sessions = MutableStateFlow(rows)
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "pine", reachable = true)))
    override val projects = MutableStateFlow(listOf(ProjectRow(id = 1, owner = "o", repo = "r")))
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override suspend fun refresh() = Unit
}

/**
 * The two calls a session screen may make, with the test holding both ends.
 *
 * Deliberately not a `HubClient`: the screens are given [SessionActions] so that
 * every authenticated call goes through `AppSession.withClient` and its 401 rule
 * in the real app, and so a test needs no transport at all.
 */
private class FakeActions : SessionActions {
    var reads = 0
        private set
    val prompts = mutableListOf<String>()

    /** What the next read answers. */
    var answer: Conversation = Conversation()

    /** Held open, a call stays in flight so the disabled box can be observed. */
    var sendGate: CompletableDeferred<Unit>? = null
    var readGate: CompletableDeferred<Unit>? = null
    var sendFails: Throwable? = null
    var readFails: Throwable? = null

    /** How many `conversation()` calls are in flight right now, and the peak seen. */
    var inFlightReads = 0
        private set
    var maxInFlightReads = 0
        private set

    /**
     * Per-call gates and answers, consumed in call order ahead of [readGate] /
     * [answer]. [readGate] is one gate shared by every call, which is enough to
     * prove "no two calls overlap" but not "which call's answer lands where" —
     * a test that needs to hold call N open independently of call N+1, or make
     * them answer differently, queues one of these per call instead.
     */
    private val queuedGates = ArrayDeque<CompletableDeferred<Unit>>()
    private val queuedAnswers = ArrayDeque<Conversation>()

    /**
     * Invoked the instant each `conversation()` call begins, before it awaits
     * its gate — lets a test capture state (e.g. what the screen shows) at
     * exactly the moment a later call's hub round trip starts.
     */
    var onReadStart: (() -> Unit)? = null

    /** Queues a gated call: the next `conversation()` call answers [answer] once the returned gate completes. */
    fun queueRead(answer: Conversation): CompletableDeferred<Unit> {
        val gate = CompletableDeferred<Unit>()
        queuedGates += gate
        queuedAnswers += answer
        return gate
    }

    override suspend fun conversation(sessionId: Long, turns: Int?): Conversation {
        reads += 1
        inFlightReads += 1
        maxInFlightReads = maxOf(maxInFlightReads, inFlightReads)
        // `yield()` before the hook, not after: `vm.state` is a `combine(...)`
        // of `local` with the fleet's own flows, `stateIn`'d eagerly, so a
        // caller's `local.update` needs a further dispatched turn beyond
        // whatever woke this call up (the mutex's own release can resume a
        // queued caller before that turn runs) to be visible on `vm.state`.
        // Without this, `onReadStart` could read a stale `vm.state.value` even
        // when `local` itself was already updated, which would pin a false
        // failure rather than the one this hook exists to catch.
        yield()
        onReadStart?.invoke()
        val gate = queuedGates.removeFirstOrNull() ?: readGate
        val queuedAnswer = queuedAnswers.removeFirstOrNull()
        try {
            gate?.await()
            readFails?.let { throw it }
            return queuedAnswer ?: answer
        } finally {
            inFlightReads -= 1
        }
    }

    override suspend fun sendPrompt(sessionId: Long, text: String): SendPromptResult {
        prompts += text
        sendGate?.await()
        sendFails?.let { throw it }
        return SendPromptResult(delivered = true, sessionId = sessionId, turnSeqBefore = 3)
    }
}

class SessionViewModelTest {

    @Test
    fun the_conversation_loads_when_the_screen_opens() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "do it", text("done"))))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)

        vm.load().join()
        runCurrent()

        assertEquals(1, actions.reads)
        assertEquals(listOf("do it"), vm.state.value.conversation.turns.map { it.prompt })
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun the_screen_says_it_is_loading_until_the_first_read_answers() = runTest {
        val actions = FakeActions()
        val gate = CompletableDeferred<Unit>()
        actions.readGate = gate
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.loaded)

        val job = vm.load()
        runCurrent()
        assertTrue(vm.state.value.loading, "the screen must be able to say it is fetching")

        gate.complete(Unit)
        job.join()
        runCurrent()

        assertFalse(vm.state.value.loading)
        assertTrue(vm.state.value.loaded, "an answered read is loaded even when it is empty")
    }

    @Test
    fun a_refresh_appends_the_new_turns_and_repeats_none() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a"), turn("t2", "b")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()

        actions.answer = Conversation(listOf(turn("t2", "b"), turn("t3", "c")))
        vm.refresh().join()
        runCurrent()

        assertEquals(listOf("t1", "t2", "t3"), vm.state.value.conversation.turns.map { it.at })
        assertEquals(2, actions.reads)
    }

    @Test
    fun a_read_that_fails_keeps_the_conversation_and_says_why() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()

        actions.readFails = HubError.Tool("E_NOTFOUND", "session 42 is gone")
        vm.refresh().join()
        runCurrent()

        assertEquals(listOf("t1"), vm.state.value.conversation.turns.map { it.at })
        assertEquals("E_NOTFOUND: session 42 is gone", vm.state.value.error)
    }


    /**
     * Review N-B1: the banner can be put away.
     *
     * It could not be, on three of the five screens. A failed call left its
     * sentence on screen until the next one succeeded, and on a hub that is
     * down that is never — an error a person has read and cannot dismiss is how
     * people learn to stop reading the banner.
     */
    @Test
    fun a_failure_can_be_dismissed_without_the_conversation_going_with_it() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        actions.readFails = HubError.Tool("E_NOTFOUND", "session 42 is gone")
        vm.refresh().join()
        runCurrent()
        assertEquals("E_NOTFOUND: session 42 is gone", vm.state.value.error)

        vm.dismissError()
        // The screen's state is assembled from `local` and the fleet flows by a
        // `combine(...).stateIn(...)`, so the new value lands on the next turn
        // of the test dispatcher rather than inside `dismissError`.
        runCurrent()

        assertNull(vm.state.value.error)
        assertEquals(listOf("t1"), vm.state.value.conversation.turns.map { it.at }, "the turns stay")
    }

    @Test
    fun the_prompt_box_is_disabled_while_the_prompt_is_in_flight() = runTest {
        val actions = FakeActions()
        val gate = CompletableDeferred<Unit>()
        actions.sendGate = gate
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("ship it")
        runCurrent()
        assertTrue(vm.state.value.canSend)

        val job = vm.send()
        runCurrent()
        assertTrue(vm.state.value.sending)
        assertFalse(vm.state.value.canSend, "the box must refuse a second prompt while one is in flight")

        gate.complete(Unit)
        job.join()
        runCurrent()
        assertFalse(vm.state.value.sending)
    }

    @Test
    fun a_delivered_prompt_clears_the_box_and_refetches_the_conversation() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("ship it")

        vm.send().join()
        runCurrent()

        assertEquals(listOf("ship it"), actions.prompts)
        assertEquals("", vm.state.value.draft)
        assertNull(vm.state.value.error)
        assertEquals(1, actions.reads, "a delivered prompt should pull the reply in")
    }

    @Test
    fun a_tool_refusal_is_shown_with_its_code_and_the_draft_is_kept() = runTest {
        val actions = FakeActions()
        actions.sendFails = HubError.Tool("E_BUSY", "the session is mid-turn")
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("ship it")

        vm.send().join()
        runCurrent()

        assertEquals("E_BUSY: the session is mid-turn", vm.state.value.error)
        assertEquals("ship it", vm.state.value.draft, "a refused prompt must not be thrown away")
        assertFalse(vm.state.value.sending)
    }

    /** `HubError.Transport` says nothing about its cause; the screen still has to say something. */
    @Test
    fun a_transport_failure_is_shown_too() = runTest {
        val actions = FakeActions()
        actions.sendFails = HubError.Transport(IllegalStateException("socket"))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("hello")

        vm.send().join()
        runCurrent()

        val error = vm.state.value.error
        assertTrue(error != null && error.isNotBlank())
        assertFalse(error.contains("socket"), "the cause's text never reaches a person")
    }

    @Test
    fun a_blank_prompt_is_never_sent() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("   ")
        runCurrent()

        assertFalse(vm.state.value.canSend)
        vm.send().join()

        assertTrue(actions.prompts.isEmpty())
    }

    /**
     * `send_prompt` is not in the hub's readonly allow-list, so a readonly
     * client token would be refused. The app does not make the call at all —
     * the constraint is that it only ever calls tools its token may use.
     */
    @Test
    fun a_readonly_credential_cannot_send_and_does_not_try() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope, canSendPrompts = false)
        vm.onDraftChange("ship it")
        runCurrent()

        assertFalse(vm.state.value.canSend)
        vm.send().join()

        assertTrue(actions.prompts.isEmpty())
        assertTrue(vm.state.value.readOnly)
    }

    // ---- issue #3: Send is disabled while the hub is unreachable ----

    @Test
    fun send_is_disabled_while_reconnecting_and_does_not_call_the_hub() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.onDraftChange("ship it")
        runCurrent()
        assertTrue(vm.state.value.canSend, "connected — the draft alone should be enough")

        fleet.status.value = ConnectionStatus.Reconnecting(attempt = 2, reason = "the hub closed the stream")
        runCurrent()

        assertFalse(vm.state.value.canSend, "a reconnecting hub must disable Send")
        vm.send().join()
        assertTrue(actions.prompts.isEmpty(), "send must not even try while offline")
        assertEquals("ship it", vm.state.value.draft, "the draft must survive being disabled")
    }

    @Test
    fun send_is_disabled_while_fully_offline() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        fleet.status.value = ConnectionStatus.Offline("not connected")
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.onDraftChange("ship it")
        runCurrent()

        assertFalse(vm.state.value.canSend)
        vm.send().join()
        assertTrue(actions.prompts.isEmpty())
    }

    @Test
    fun send_re_enables_once_the_hub_is_reachable_again() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        fleet.status.value = ConnectionStatus.Reconnecting(attempt = 1, reason = null)
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.onDraftChange("ship it")
        runCurrent()
        assertFalse(vm.state.value.canSend)

        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        runCurrent()

        assertTrue(vm.state.value.canSend)
        assertEquals("ship it", vm.state.value.draft)
    }

    @Test
    fun the_bar_follows_the_live_row() = runTest {
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, FakeActions(), backgroundScope)
        assertEquals("working", vm.state.value.session?.claudeStatus)
        assertEquals("pine", vm.state.value.session?.hostAlias)

        fleet.sessions.value = listOf(row(status = "blocked", stuck = "press_enter", activity = "waiting"))
        runCurrent()

        assertEquals("blocked", vm.state.value.session?.claudeStatus)
        assertEquals("press_enter", vm.state.value.session?.stuckKind)
        assertEquals("waiting", vm.state.value.session?.currentActivity)
    }

    /** A session killed from the desktop disappears from the fleet under the screen. */
    @Test
    fun a_session_that_leaves_the_fleet_leaves_the_bar_empty_rather_than_stale() = runTest {
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, FakeActions(), backgroundScope)

        fleet.sessions.value = emptyList()
        runCurrent()

        assertNull(vm.state.value.session)
        assertFalse(vm.state.value.canSend)
    }

    @Test
    fun a_tool_call_one_liner_keeps_its_failure_marker() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(
            listOf(
                turn(
                    "t1",
                    "build it",
                    ConvItem.Tool(summary = "Bash(cargo test)", error = true),
                    text("that failed"),
                ),
            ),
        )
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)

        vm.load().join()
        runCurrent()

        val items = vm.state.value.conversation.turns.single().items
        assertTrue((items.first() as ConvItem.Tool).error)
    }

    // ---- event-driven refresh: issue #2, "conversation does not update live" ----

    private fun TestScope.pastDebounce() =
        testScheduler.advanceTimeBy(SESSION_EVENT_DEBOUNCE.inWholeMilliseconds + 1)

    @Test
    fun an_event_for_the_open_session_triggers_one_refetch_after_the_debounce() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "do it")))
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.load().join()
        assertEquals(1, actions.reads)

        actions.answer = Conversation(listOf(turn("t1", "do it"), turn("t2", "the reply")))
        fleet.sessionChanges.tryEmit(ID)
        runCurrent()
        assertEquals(1, actions.reads, "the debounce has not elapsed yet")

        pastDebounce()
        runCurrent()

        assertEquals(2, actions.reads)
        assertEquals(listOf("do it", "the reply"), vm.state.value.conversation.turns.map { it.prompt })
    }

    /** Several frames during one turn — working, then idle — must cost one read, not several. */
    @Test
    fun a_burst_of_events_for_the_open_session_triggers_one_refetch() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.load().join()
        assertEquals(1, actions.reads)

        repeat(5) { fleet.sessionChanges.tryEmit(ID) }
        pastDebounce()
        runCurrent()

        assertEquals(2, actions.reads, "a burst must coalesce into a single refetch")
    }

    @Test
    fun an_event_for_a_different_session_triggers_no_refetch() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.load().join()
        assertEquals(1, actions.reads)

        fleet.sessionChanges.tryEmit(ID + 1)
        pastDebounce()
        runCurrent()

        assertEquals(1, actions.reads, "a change to a session this screen is not open on must not refetch")
    }

    /** The screen closing cancels the scope it owns; the subscription goes with it. */
    @Test
    fun no_refetch_after_the_view_model_is_closed() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val vm = SessionViewModel(ID, fleet, actions, scope)
        vm.load().join()
        assertEquals(1, actions.reads)

        job.cancel()
        fleet.sessionChanges.tryEmit(ID)
        pastDebounce()
        runCurrent()

        assertEquals(1, actions.reads, "a closed screen must not keep refetching")
    }

    @Test
    fun a_refetch_the_event_triggers_fails_the_same_way_refresh_does_and_a_later_one_still_works() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.load().join()

        actions.readFails = HubError.Tool("E_NOTFOUND", "session 42 is gone")
        fleet.sessionChanges.tryEmit(ID)
        pastDebounce()
        runCurrent()

        assertEquals("E_NOTFOUND: session 42 is gone", vm.state.value.error)
        assertEquals(listOf("t1"), vm.state.value.conversation.turns.map { it.at }, "the turns stay")

        actions.readFails = null
        actions.answer = Conversation(listOf(turn("t1", "a"), turn("t2", "b")))
        fleet.sessionChanges.tryEmit(ID)
        pastDebounce()
        runCurrent()

        assertNull(vm.state.value.error)
        assertEquals(
            listOf("t1", "t2"),
            vm.state.value.conversation.turns.map { it.at },
            "a failed event-triggered refetch must not stop the next one",
        )
    }

    // ---- single-flight reads: review finding on task 2 ----
    //
    // `load()`, `refresh()`, a send's follow-up read, and the event-triggered
    // refetch above each call the same private `read()`, but each used to do
    // so from its own independently launched coroutine. Two in flight at once
    // could apply out of request order through `Conversation.appending`'s own
    // overlap-prone merge. `read()` now serializes the actual hub call through
    // a `Mutex`: every requested read still runs (none is dropped or
    // coalesced), one at a time, and — because the next one cannot even start
    // its call until the previous has finished and applied its own result —
    // whichever was requested later always applies later.

    /** The reviewer's own scenario: an event refetch in flight, then a manual Refresh. */
    @Test
    fun a_refresh_requested_while_an_event_refetch_is_in_flight_queues_rather_than_races_it() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.load().join()
        assertEquals(1, actions.reads)

        // Read #1 (the event-triggered refetch) and read #2 (a manual refresh)
        // each get their own gate and answer, so the test can hold #1 open
        // independently of #2 and tell the two answers apart — a single shared
        // `readGate`/`answer` can only prove "the calls did not overlap", not
        // "the screen showed #1's result before #2's hub call even began".
        val gate1 = actions.queueRead(Conversation(listOf(turn("t1", "a"), turn("t2", "from the event"))))
        fleet.sessionChanges.tryEmit(ID)
        pastDebounce()
        runCurrent()
        assertEquals(2, actions.reads, "the event-triggered read has started and is gated open")

        var conversationWhenRead2Started: List<String?>? = null
        actions.onReadStart = { conversationWhenRead2Started = vm.state.value.conversation.turns.map { it.at } }
        val gate2 = actions.queueRead(
            Conversation(listOf(turn("t1", "a"), turn("t2", "from the event"), turn("t3", "from refresh"))),
        )
        val refreshJob = vm.refresh()
        runCurrent()
        assertEquals(2, actions.reads, "a manual refresh must queue behind the in-flight read, not call the hub too")
        assertFalse(vm.state.value.loading, "refresh never showed a loading spinner before this change either")

        gate1.complete(Unit)
        runCurrent()
        gate2.complete(Unit)
        refreshJob.join()
        runCurrent()

        assertEquals(3, actions.reads)
        assertEquals(1, actions.maxInFlightReads, "the two calls must never overlap")
        assertEquals(
            listOf("t1", "t2"),
            conversationWhenRead2Started,
            "read #2's hub call must not start until read #1's result was already applied " +
                "-- fetch and apply have to be one critical section, not two",
        )
        assertEquals(
            listOf("t1", "t2", "t3"),
            vm.state.value.conversation.turns.map { it.at },
            "the later request (refresh) must be the one the screen ends up showing",
        )
        assertFalse(vm.state.value.loading, "the flag the queued refresh set must still clear once it is done")
    }

    /** `load()` is the entry point that actually sets `loading`; a queued one must too. */
    @Test
    fun a_load_queued_behind_an_in_flight_event_refetch_still_shows_and_clears_loading() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.load().join()
        assertEquals(1, actions.reads)

        val gate = CompletableDeferred<Unit>()
        actions.readGate = gate
        actions.answer = Conversation(listOf(turn("t1", "a"), turn("t2", "from the event")))
        fleet.sessionChanges.tryEmit(ID)
        pastDebounce()
        runCurrent()
        assertEquals(2, actions.reads, "the event-triggered read has started and is gated open")

        actions.answer = Conversation(listOf(turn("t1", "a"), turn("t2", "from the event"), turn("t3", "reloaded")))
        val loadJob = vm.load()
        runCurrent()
        assertEquals(2, actions.reads, "the reload must queue rather than race the in-flight event read")
        assertTrue(vm.state.value.loading, "loading must show immediately, even while the call itself is queued")

        gate.complete(Unit)
        loadJob.join()
        runCurrent()

        assertEquals(3, actions.reads)
        assertEquals(1, actions.maxInFlightReads, "the two calls must never overlap")
        assertFalse(vm.state.value.loading, "loading must clear once the queued call finally runs")
        assertEquals(
            listOf("t1", "t2", "t3"),
            vm.state.value.conversation.turns.map { it.at },
            "the later request (the reload) must be the one the screen ends up showing",
        )
    }
}
