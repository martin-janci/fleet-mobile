@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ALL_SESSIONS_CHANGED
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.SessionActions
import dev.claudefleet.mobile.data.STOPPED
import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.ConvTurn
import dev.claudefleet.mobile.model.Conversation
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.PendingInput
import dev.claudefleet.mobile.model.PendingOption
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SendPromptResult
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.WaitResult
import dev.claudefleet.mobile.net.HUB_VERSION_KEYS
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ID = 42L

private fun row(
    status: String? = "working",
    stuck: String? = null,
    activity: String? = null,
    pending: PendingInput? = null,
) = SessionRow(
    id = ID,
    tmuxName = "fleet-api",
    friendlyName = "API work",
    hostAlias = "pine",
    projectId = 1,
    claudeStatus = status,
    stuckKind = stuck,
    currentActivity = activity,
    pendingInput = pending,
)

/** A session the hub says is waiting on a numbered permission prompt. */
private fun blockedRow(
    pending: PendingInput? = PendingInput("permission", "Do it?", listOf(PendingOption(1, "Yes"))),
) = row(status = "blocked", pending = pending)

private fun turn(at: String, prompt: String, vararg items: ConvItem) =
    ConvTurn(prompt = prompt, at = at, endedAt = at, items = items.toList())

private fun text(s: String) = ConvItem.Text(s)

private class FakeFleetState(rows: List<SessionRow> = listOf(row())) : FleetState {
    override val sessions = MutableStateFlow(rows)
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "pine", reachable = true)))
    override val projects = MutableStateFlow(listOf(ProjectRow(id = 1, owner = "o", repo = "r")))
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    // Null is the honest starting point: no `ready` frame has named a version
    // yet, which is exactly what a hub too old to send one looks like too.
    override val hubVersion = MutableStateFlow<String?>(null)
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
    val sentPrompts = mutableListOf<String>()

    /** What the next read answers. */
    var answer: Conversation = Conversation()

    /** Held open, a call stays in flight so the disabled box can be observed. */
    var sendGate: CompletableDeferred<Unit>? = null
    var readGate: CompletableDeferred<Unit>? = null
    var sendFails: Throwable? = null
    var readFails: Throwable? = null

    /** What [ping] answers — the hub itself, independent of whether the stream is up. */
    var pingAnswer: Boolean = false

    /** How many times [ping] was called, so a test can bound the probe's own call count. */
    var pings = 0
        private set

    override suspend fun ping(): Boolean {
        pings += 1
        return pingAnswer
    }

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
        sentPrompts += text
        sendGate?.await()
        sendFails?.let { throw it }
        return SendPromptResult(delivered = true, sessionId = sessionId, turnSeqBefore = 3)
    }

    /** Every key the card pressed, in order. */
    val sentKeys = mutableListOf<String>()

    /** Every `wait_for_session` call, as (session, turn to wait past). */
    val waited = mutableListOf<Pair<Long, Long>>()

    /** What [waitForTurn] answers — a timeout is what keeps a card on screen. */
    var waitAnswer: WaitResult = WaitResult(status = "satisfied", turnSeq = 4)

    /** What [capture] answers, and how many times it was asked. */
    var captureAnswer: String = ""
    var captures = 0
        private set
    var captureFails: Throwable? = null

    override suspend fun sendKeys(sessionId: Long, key: String): SendPromptResult {
        sentKeys += key
        sendGate?.await()
        sendFails?.let { throw it }
        return SendPromptResult(delivered = true, sessionId = sessionId, turnSeqBefore = 3)
    }

    override suspend fun capture(sessionId: Long, maxLines: Int): String {
        captures += 1
        captureFails?.let { throw it }
        return captureAnswer
    }

    override suspend fun waitForTurn(sessionId: Long, turn: Long, timeoutS: Int): WaitResult {
        waited += sessionId to turn
        return waitAnswer
    }

    /** Every session id [restart] was called with, in order. */
    val restarted = mutableListOf<Long>()

    /** Every session id [safeKill] was called with, in order. */
    val safeKilled = mutableListOf<Long>()

    /** Every session id [kill] was called with, in order — empty when [killError] refused it. */
    val killed = mutableListOf<Long>()

    /** Every `(sessionId, tags)` pair [setTags] was called with, in order. */
    val tags = mutableListOf<Pair<Long, List<String>>>()

    /** Every `(sessionId, friendlyName)` pair [rename] was called with, in order. */
    val renamed = mutableListOf<Pair<Long, String>>()

    /** When set, [kill] throws this instead of recording the call. */
    var killError: Throwable? = null

    override suspend fun restart(sessionId: Long) {
        restarted += sessionId
    }

    override suspend fun safeKill(sessionId: Long) {
        safeKilled += sessionId
    }

    override suspend fun kill(sessionId: Long) {
        killError?.let { throw it }
        killed += sessionId
    }

    override suspend fun setTags(sessionId: Long, tags: List<String>) {
        this.tags += sessionId to tags
    }

    override suspend fun rename(sessionId: Long, friendlyName: String) {
        renamed += sessionId to friendlyName
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
        assertEquals("E_NOTFOUND: session 42 is gone", vm.state.value.error?.details)
    }

    /**
     * `E_NO_TRANSCRIPT` is the hub's way of saying a session has nothing said
     * yet — not a failure. A read that fails this way must mark the screen
     * [SessionUiState.silent] rather than raise the error banner.
     */
    @Test
    fun a_no_transcript_read_is_silent_not_an_error() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)

        actions.readFails = HubError.Tool(NO_TRANSCRIPT, "no transcript for claude session 0b63c561-66fd on htz")
        vm.load().join()
        runCurrent()

        assertTrue(vm.state.value.silent)
        assertNull(vm.state.value.error)
        assertTrue(vm.state.value.loaded, "a silent session is still a loaded one")
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
        assertEquals("E_NOTFOUND: session 42 is gone", vm.state.value.error?.details)

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

        assertEquals(listOf("ship it"), actions.sentPrompts)
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

        assertEquals("E_BUSY: the session is mid-turn", vm.state.value.error?.details)
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
        assertTrue(error != null && error.body.isNotBlank())
        assertFalse(error.body.contains("socket"), "the cause's text never reaches a person")
    }

    @Test
    fun a_blank_prompt_is_never_sent() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("   ")
        runCurrent()

        assertFalse(vm.state.value.canSend)
        vm.send().join()

        assertTrue(actions.sentPrompts.isEmpty())
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

        assertTrue(actions.sentPrompts.isEmpty())
        assertTrue(vm.state.value.readOnly)
    }

    // ---- sendCommand: the status strip's `/compact` chip, task 5 ----
    //
    // The one rule `send()` already follows (readonly / connected / idle),
    // reused rather than re-decided — `sendCommand` is what the strip's
    // amber `/compact` chip calls instead of stuffing the composer's draft
    // and calling `send()`, so the box on screen is never touched by a tap
    // that has nothing to do with what someone was typing.

    @Test
    fun sendCommand_delivers_the_given_text_without_touching_the_draft() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("still typing")

        vm.sendCommand("/compact").join()
        runCurrent()

        assertEquals(listOf("/compact"), actions.sentPrompts)
        assertEquals("still typing", vm.state.value.draft, "sendCommand must not clobber what is being composed")
        assertEquals(1, actions.reads, "a delivered command should pull the reply in, exactly like send()")
    }

    @Test
    fun sendCommand_is_disabled_while_something_else_is_already_sending() = runTest {
        val actions = FakeActions()
        val gate = CompletableDeferred<Unit>()
        actions.sendGate = gate
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("ship it")
        val sendJob = vm.send()
        runCurrent()
        assertTrue(vm.state.value.sending)

        vm.sendCommand("/compact").join()

        assertEquals(emptyList(), actions.sentPrompts.filter { it == "/compact" }, "a command must not race an in-flight prompt")

        gate.complete(Unit)
        sendJob.join()
    }

    @Test
    fun sendCommand_does_nothing_for_a_readonly_credential() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope, canSendPrompts = false)

        vm.sendCommand("/compact").join()

        assertTrue(actions.sentPrompts.isEmpty())
    }

    @Test
    fun sendCommand_does_nothing_while_the_hub_is_unreachable() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        fleet.status.value = ConnectionStatus.Offline("not connected")
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.sendCommand("/compact").join()

        assertTrue(actions.sentPrompts.isEmpty())
    }

    @Test
    fun sendCommand_does_nothing_once_the_session_has_left_the_fleet() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(rows = emptyList())
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.sendCommand("/compact").join()

        assertTrue(actions.sentPrompts.isEmpty())
    }

    // ---- Send is disabled while the hub is unreachable ----

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
        assertTrue(actions.sentPrompts.isEmpty(), "send must not even try while offline")
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
        assertTrue(actions.sentPrompts.isEmpty())
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

    /**
     * A dropped stream is not the same fact as an unreachable hub: `/events`
     * can flap for reasons that have nothing to do with the hub itself (a
     * backgrounded phone's radio, a flaky Wi-Fi hop), and disabling Send on
     * that alone would refuse a prompt the hub was perfectly able to take. So
     * while `fleet.status` is anything but `Connected`, the screen probes the
     * hub directly (`fleet_health`, via [SessionActions.ping]) and `canSend`
     * follows *that* answer too.
     */
    @Test
    fun send_is_allowed_while_the_stream_is_down_but_the_hub_answers() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        fleet.status.value = ConnectionStatus.Reconnecting(attempt = 3, reason = "stream dropped")
        actions.pingAnswer = true
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.load().join()
        vm.onDraftChange("go on")
        runCurrent()

        assertTrue(vm.state.first { it.loaded }.canSend)
    }

    /** The other half: a stream down AND a hub that does not answer either must still refuse Send. */
    @Test
    fun send_is_refused_when_the_hub_itself_does_not_answer() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        fleet.status.value = ConnectionStatus.Reconnecting(attempt = 3, reason = "stream dropped")
        actions.pingAnswer = false
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.load().join()
        vm.onDraftChange("go on")
        runCurrent()

        assertFalse(vm.state.first { it.loaded }.canSend)
    }

    // ---- The probe is bounded: it stops, and it does not start when it cannot help ----

    /**
     * A `true` probe ends the probing.
     *
     * The loop used to ask again every [PROBE_DEBOUNCE] for as long as the
     * screen existed: a `fleet_health` call every two seconds, forever, on a
     * phone's radio, for an answer already in hand and unchanged. It is not a
     * fact that decays — only a change in `fleet.status` can make it stale,
     * and that is what re-arms it.
     */
    @Test
    fun a_hub_that_answers_is_not_asked_again_until_the_status_moves() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        fleet.status.value = ConnectionStatus.Reconnecting(attempt = 3, reason = "stream dropped")
        actions.pingAnswer = true
        SessionViewModel(ID, fleet, actions, backgroundScope)
        runCurrent()

        val afterFirstAnswer = actions.pings
        assertEquals(1, afterFirstAnswer, "one probe is enough to know the hub is up")

        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(afterFirstAnswer, actions.pings, "a true probe must not be repeated on a timer")
    }

    /** And the status moving is what re-arms it — the old answer was about the old state. */
    @Test
    fun a_status_change_re_arms_the_probe() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        fleet.status.value = ConnectionStatus.Reconnecting(attempt = 3, reason = "stream dropped")
        actions.pingAnswer = true
        SessionViewModel(ID, fleet, actions, backgroundScope)
        // Past the debounce the first probe holds, so the loop is parked on
        // the status rather than merely between two ticks of a timer.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, actions.pings)

        fleet.status.value = ConnectionStatus.Reconnecting(attempt = 4, reason = "stream dropped")
        runCurrent()
        assertEquals(2, actions.pings, "a new connection state deserves its own answer")

        // And the second answer bounds it again, rather than leaving a loop
        // that re-armed once and then ran forever.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, actions.pings)
    }

    /**
     * A readonly screen never probes at all. `canSend` is false for it
     * whatever the hub says, so every call it made was a call made for
     * nothing — and the app's rule is that it only calls tools it has a use
     * for.
     */
    @Test
    fun a_readonly_screen_never_probes_the_hub() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        fleet.status.value = ConnectionStatus.Reconnecting(attempt = 2, reason = "stream dropped")
        actions.pingAnswer = true
        SessionViewModel(ID, fleet, actions, backgroundScope, canSendPrompts = false)

        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(0, actions.pings, "a screen that cannot send has no use for the answer")
    }

    /**
     * A repository the lifecycle stopped is not reconnecting and is not going
     * to: nothing is waiting on "is the hub up", so nothing asks. This is the
     * exact `Offline` value `FleetRepository.stop()` publishes.
     */
    @Test
    fun a_stopped_repository_is_never_probed() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        fleet.status.value = ConnectionStatus.Offline(STOPPED)
        actions.pingAnswer = true
        SessionViewModel(ID, fleet, actions, backgroundScope)

        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(0, actions.pings, "the app put the stream down; there is nobody to answer for")
    }

    // ---- A refused hub is answering, and still must not be used ----

    /**
     * The one case where a reachable hub disables Send.
     *
     * `Refused` means the hub named a wire contract this build will not read.
     * A probe of such a hub answers `true` perfectly happily — it is up — so
     * a `connected` rule that looked only at the probe handed a person a live
     * Send button pointed at a hub whose replies the repository was already
     * dropping on the floor.
     */
    @Test
    fun a_refused_hub_keeps_send_disabled_however_healthy_the_probe() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        actions.pingAnswer = true
        fleet.status.value = ConnectionStatus.Refused("This app is too old for this hub (contract 2). Update the app.")
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.onDraftChange("ship it")
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()

        assertFalse(vm.state.value.connected, "a refused hub is not a hub this screen may talk to")
        assertFalse(vm.state.value.canSend)
        assertEquals(0, actions.pings, "and it is not even asked")

        vm.send().join()
        assertTrue(actions.sentPrompts.isEmpty(), "send must not try against a refused hub")
        assertEquals("ship it", vm.state.value.draft)
    }

    /** No read either: `session_conversation` would decode the same refused shape. */
    @Test
    fun a_refused_hub_gets_no_conversation_read() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        fleet.status.value = ConnectionStatus.Refused("This hub is too old for this app (contract 0). Update the hub.")
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.load().join()
        vm.refresh().join()
        runCurrent()

        assertEquals(0, actions.reads, "no tool call against a hub this build refuses")
        assertFalse(vm.state.value.loaded, "and the screen does not claim to have read an empty conversation")
    }

    /** The probe answer is exposed, so the banner can draw the third connection state. */
    @Test
    fun the_screen_publishes_what_its_probe_found() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        fleet.status.value = ConnectionStatus.Reconnecting(attempt = 2, reason = "stream dropped")
        actions.pingAnswer = true
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        runCurrent()

        assertEquals(true, vm.state.value.hubReachable)
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

    /**
     * `nowSeconds` is what [dev.claudefleet.mobile.ui.components.StatusStrip]
     * computes elapsed/idle-since wording against — the same clock shape
     * `SessionsViewModel` already carries for the fleet list, injectable so
     * this test is neither flaky nor slow, and ticking on its own so a
     * session screen left open keeps advancing without a refresh.
     */
    @Test
    fun the_state_carries_a_clock_that_ticks() = runTest {
        var now = 1_000L
        val vm = SessionViewModel(ID, FakeFleetState(), FakeActions(), backgroundScope, clock = { now })
        val first = vm.state.first { it.nowSeconds > 0 }
        assertEquals(1_000L, first.nowSeconds)

        now = 1_040L
        advanceTimeBy(31_000)
        assertEquals(1_040L, vm.state.value.nowSeconds)
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

    /**
     * A non-first read landing while `load()`'s own read is still in flight
     * must not clear the first-load spinner. Named for the scenario it
     * guards, independent of the coalescing tests below: `loading` is derived
     * from whether any *first*-flavoured generation is outstanding, so a
     * same-time non-first one (its own separate generation, since load()'s is
     * already running rather than merely queued) cannot touch it.
     */
    @Test
    fun an_event_refetch_that_starts_while_loading_does_not_clear_the_first_load_spinner() = runTest {
        val actions = FakeActions()
        val gate = CompletableDeferred<Unit>()
        actions.readGate = gate
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        val loadJob = vm.load()
        runCurrent()
        assertTrue(vm.state.value.loading, "load()'s own read is in flight")

        fleet.sessionChanges.tryEmit(ID)
        pastDebounce()
        runCurrent()
        assertTrue(
            vm.state.value.loading,
            "the event-triggered generation only queued behind load()'s — the spinner must survive it",
        )

        gate.complete(Unit)
        loadJob.join()
        runCurrent()

        assertFalse(vm.state.value.loading, "loading clears once load()'s own read has applied")
    }

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

    /**
     * Finding #4: a reconnect resync (`ready` or `lagged`) carries no per-row
     * event for anything that changed while the app was away — a reply may
     * have landed during the gap — so `FleetRepository` publishes
     * [ALL_SESSIONS_CHANGED] on `sessionChanges` once its own resync has
     * landed, and every open session screen treats it exactly like an event
     * naming its own id.
     */
    @Test
    fun a_reconnect_resync_triggers_one_debounced_refetch_for_the_open_session() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "do it")))
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.load().join()
        assertEquals(1, actions.reads)

        actions.answer = Conversation(listOf(turn("t1", "do it"), turn("t2", "the reply that arrived during the gap")))
        fleet.sessionChanges.tryEmit(ALL_SESSIONS_CHANGED)
        pastDebounce()
        runCurrent()

        assertEquals(2, actions.reads)
        assertEquals(
            listOf("do it", "the reply that arrived during the gap"),
            vm.state.value.conversation.turns.map { it.prompt },
        )
    }

    /** A burst of resyncs (repeated `ready`s while flapping) still costs one read. */
    @Test
    fun a_burst_of_reconnect_resyncs_triggers_one_refetch() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.load().join()
        assertEquals(1, actions.reads)

        repeat(3) { fleet.sessionChanges.tryEmit(ALL_SESSIONS_CHANGED) }
        pastDebounce()
        runCurrent()

        assertEquals(2, actions.reads, "a burst of resyncs must coalesce into a single refetch")
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

        assertEquals("E_NOTFOUND: session 42 is gone", vm.state.value.error?.details)
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

    // ---- coalescing and `refreshing`: final review finding #3 ----
    //
    // Single-flight (above) stopped concurrent reads from racing, but every
    // caller still launched its own coroutine, so N rapid Refresh taps behind
    // one held read queued N hub calls back to back — each one able to hold
    // `fetchLock` for up to the hub's own call timeout. `requestRead` now
    // coalesces: a request folds into whatever is already queued (registered
    // but not yet fetching) instead of adding a second queued call, because
    // that queued read hasn't fetched yet and will still answer for it. A
    // request made once a read is already RUNNING cannot fold into it — that
    // read's fetch already started — so it gets its own, freshly queued
    // generation instead. `refreshing` tracks whether any such generation
    // (queued or running) is outstanding, distinct from `loading`.

    @Test
    fun refreshing_shows_the_instant_a_refresh_is_requested_and_clears_on_success() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        assertFalse(vm.state.value.refreshing)

        val gate = CompletableDeferred<Unit>()
        actions.readGate = gate
        val job = vm.refresh()
        runCurrent()
        assertTrue(vm.state.value.refreshing, "refreshing must show as soon as the refresh is requested, before the hub even answers")
        assertFalse(vm.state.value.loading, "refreshing is a distinct flag from the first-load indicator")

        gate.complete(Unit)
        job.join()
        runCurrent()

        assertFalse(vm.state.value.refreshing, "refreshing must clear once the read has applied")
    }

    @Test
    fun refreshing_clears_after_a_failed_refresh_too() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()

        actions.readFails = HubError.Tool("E_NOTFOUND", "session 42 is gone")
        vm.refresh().join()
        runCurrent()

        assertFalse(vm.state.value.refreshing, "a failure must clear refreshing exactly like a success does")
        assertEquals("E_NOTFOUND: session 42 is gone", vm.state.value.error?.details)
    }

    /** The reviewer's own scenario, named: five taps behind one held read cost exactly one extra hub call. */
    @Test
    fun n_rapid_refresh_taps_behind_a_held_read_produce_at_most_one_additional_hub_call() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        assertEquals(1, actions.reads)

        val heldGate = actions.queueRead(Conversation(listOf(turn("t1", "a"), turn("t2", "held"))))
        vm.refresh()
        runCurrent()
        assertEquals(2, actions.reads, "the held refresh has started its own hub call")

        val coalescedGate = actions.queueRead(
            Conversation(listOf(turn("t1", "a"), turn("t2", "held"), turn("t3", "coalesced"))),
        )
        val taps = (1..5).map { vm.refresh() }
        runCurrent()
        assertEquals(
            2,
            actions.reads,
            "five taps behind a held read must share one queued read, not call the hub five more times",
        )
        assertTrue(vm.state.value.refreshing)

        heldGate.complete(Unit)
        runCurrent()
        assertEquals(3, actions.reads, "exactly one additional hub call for every tap that arrived while the first was held")

        coalescedGate.complete(Unit)
        taps.forEach { it.join() }
        runCurrent()

        assertEquals(3, actions.reads, "no further hub calls beyond the one shared, coalesced read")
        assertEquals(listOf("t1", "t2", "t3"), vm.state.value.conversation.turns.map { it.at })
        assertFalse(vm.state.value.refreshing)
    }

    /**
     * A post-send read that arrives while another non-send read is already
     * queued must not be dropped: it is folded into that queued read, whose
     * fetch has not started yet and so still reflects the prompt that was
     * just sent. This proves both halves at once — the hub call count stays
     * bounded, and `send()`'s own Job still only completes once that shared
     * read has actually run and applied.
     */
    @Test
    fun a_post_send_read_coalesced_with_an_already_queued_refresh_is_never_dropped() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.load().join()
        assertEquals(1, actions.reads)

        // An event-triggered refetch occupies the hub call.
        val heldGate = actions.queueRead(Conversation(listOf(turn("t1", "a"), turn("t2", "event"))))
        fleet.sessionChanges.tryEmit(ID)
        pastDebounce()
        runCurrent()
        assertEquals(2, actions.reads, "the event-triggered read has started and is held open")

        // A manual refresh queues behind it — the generation the post-send
        // read below will be folded into.
        val coalescedGate = actions.queueRead(
            Conversation(listOf(turn("t1", "a"), turn("t2", "event"), turn("t3", "the reply"))),
        )
        val refreshJob = vm.refresh()
        runCurrent()
        assertEquals(2, actions.reads, "the refresh must queue rather than call the hub while the event read is held")

        vm.onDraftChange("ship it")
        val sendJob = vm.send()
        runCurrent()
        assertEquals(listOf("ship it"), actions.sentPrompts, "the prompt itself is always delivered")
        assertEquals(2, actions.reads, "the post-send read must coalesce with the already-queued refresh, not add a third call")

        heldGate.complete(Unit)
        runCurrent()
        assertEquals(3, actions.reads, "the shared, coalesced generation runs once the held read is out of the way")

        coalescedGate.complete(Unit)
        refreshJob.join()
        sendJob.join()
        runCurrent()

        assertEquals(3, actions.reads, "the post-send read was satisfied by the shared generation — never dropped, never duplicated")
        assertEquals(
            listOf("t1", "t2", "t3"),
            vm.state.value.conversation.turns.map { it.at },
            "the read that eventually ran reflects everything pending when it started, including the send",
        )
        assertFalse(vm.state.value.refreshing)
    }

    /**
     * Two overlapping reads, not coalesced (the second is requested once the
     * first is already running, so it gets its own generation): the first
     * fails, the second succeeds. The second's own request already cleared
     * `error` when it was made — before the first had even failed — so only
     * the second's own *success* clearing it again is what leaves the banner
     * down; without it, the first read's failure (landing after the second's
     * request-time clear) would leave a stale banner behind a conversation
     * that just refreshed successfully.
     */
    @Test
    fun a_successful_overlapping_read_clears_an_error_a_different_overlapping_read_left_behind() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()

        val gate1 = actions.queueRead(Conversation())
        val refreshJob1 = vm.refresh()
        runCurrent()
        assertEquals(2, actions.reads)

        val gate2 = actions.queueRead(Conversation(listOf(turn("t1", "a"), turn("t2", "recovered"))))
        val refreshJob2 = vm.refresh()
        runCurrent()
        assertEquals(2, actions.reads, "the second request queues rather than racing the first")

        actions.readFails = HubError.Tool("E_DOWN", "temporarily unavailable")
        gate1.complete(Unit)
        runCurrent()
        assertEquals("E_DOWN: temporarily unavailable", vm.state.value.error?.details, "the first, failed read's error is shown")

        actions.readFails = null
        gate2.complete(Unit)
        refreshJob1.join()
        refreshJob2.join()
        runCurrent()

        assertNull(vm.state.value.error, "the second read's success must clear the stale error the first one left")
        assertEquals(listOf("t1", "t2"), vm.state.value.conversation.turns.map { it.at })
    }

    /** Closing the screen while a read is held open must not hang. */
    @Test
    fun closing_the_screen_mid_read_does_not_hang() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val job = Job()
        val scope = CoroutineScope(coroutineContext + job)
        val vm = SessionViewModel(ID, FakeFleetState(), actions, scope)
        vm.load().join()

        val gate = CompletableDeferred<Unit>()
        actions.readGate = gate
        vm.refresh()
        runCurrent()
        assertEquals(2, actions.reads)
        assertTrue(vm.state.value.refreshing)

        job.cancel()
        runCurrent()

        // Nothing is left waiting on the cancelled generation, and the fake's
        // own in-flight bookkeeping — unwound in its own `finally` — is back
        // to zero: the read did not leak as "still in flight" forever.
        assertEquals(0, actions.inFlightReads)
    }

    // ---- newReply / onAtBottom: task 3, "open where you left off" ----

    @Test
    fun newReply_is_false_until_anything_says_otherwise() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)

        vm.load().join()
        runCurrent()

        assertFalse(vm.state.value.newReply)
    }

    /**
     * The screen calls `onAtBottom(false)` once the reader scrolls away from
     * the newest turn. A refetch that grows the tail while that is true is
     * exactly the case the pill's "↓ New reply" label exists for.
     */
    @Test
    fun a_refetch_that_grows_the_tail_while_not_at_the_bottom_sets_newReply() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        vm.onAtBottom(false)

        actions.answer = Conversation(listOf(turn("t1", "a"), turn("t2", "b")))
        vm.refresh().join()
        runCurrent()

        assertTrue(vm.state.value.newReply)
    }

    /** The ordinary case: a reader sitting at the bottom sees new turns arrive with no pill at all. */
    @Test
    fun a_refetch_that_grows_the_tail_while_at_the_bottom_leaves_newReply_false() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        // No onAtBottom(false) — the reader is presumed at the bottom until told otherwise.

        actions.answer = Conversation(listOf(turn("t1", "a"), turn("t2", "b")))
        vm.refresh().join()
        runCurrent()

        assertFalse(vm.state.value.newReply)
    }

    @Test
    fun onAtBottom_true_clears_a_pending_newReply() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        vm.onAtBottom(false)
        actions.answer = Conversation(listOf(turn("t1", "a"), turn("t2", "b")))
        vm.refresh().join()
        runCurrent()
        assertTrue(vm.state.value.newReply, "setup: newReply must be set before this test can check it clears")

        vm.onAtBottom(true)
        runCurrent()

        assertFalse(vm.state.value.newReply)
    }

    /** `onAtBottom(false)` alone, with nothing new arriving, is not itself a new reply. */
    @Test
    fun onAtBottom_false_alone_does_not_set_newReply() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a")))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()

        vm.onAtBottom(false)
        runCurrent()

        assertFalse(vm.state.value.newReply)
    }

    /**
     * Final review fix wave, I2: the live-turn-grows case — a refetch that
     * answers the SAME turn (same `at`/`prompt`, so `Conversation.appending`
     * merges it rather than appending a new one) with a later `endedAt` and
     * one more item, because the agent is still working. `tailGrew` has to
     * notice this via `endedAt` alone; turn count does not move.
     */
    @Test
    fun a_refetch_that_grows_the_live_turn_via_a_later_endedAt_sets_newReply() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a", text("working"))))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        vm.onAtBottom(false)

        actions.answer = Conversation(
            listOf(ConvTurn(prompt = "a", at = "t1", endedAt = "t1-later", items = listOf(text("working"), text("done")))),
        )
        vm.refresh().join()
        runCurrent()

        assertEquals(1, vm.state.value.conversation.turns.size, "setup: still the same turn, not a new one")
        assertTrue(vm.state.value.newReply)
    }

    /** The counterpart: an identical tail (same size, same `endedAt`) is not a new reply. */
    @Test
    fun a_refetch_with_an_identical_tail_leaves_newReply_false() = runTest {
        val actions = FakeActions()
        actions.answer = Conversation(listOf(turn("t1", "a", text("working"))))
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        vm.onAtBottom(false)

        // The hub answers the exact same window again — same `at`, same
        // `prompt`, same `endedAt`, same items.
        actions.answer = Conversation(listOf(turn("t1", "a", text("working"))))
        vm.refresh().join()
        runCurrent()

        assertFalse(vm.state.value.newReply)
    }

    // ---- Answering from the card ----

    /**
     * The card is derived from the row, so nothing clears it by hand: the
     * answer goes out, the turn counter is waited past, and the row the hub
     * publishes next is no longer `blocked`, which is what makes the card
     * go away.
     */
    @Test
    fun answering_an_option_sends_its_number_waits_for_the_turn_and_clears_the_card() = runTest {
        val actions = FakeActions()
        val gate = CompletableDeferred<Unit>()
        actions.sendGate = gate
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.load().join()
        runCurrent()
        assertNotNull(vm.state.value.card)

        val job = vm.answer(Answer.Option(1, "Yes"))
        runCurrent()
        assertTrue(vm.state.value.answering)
        assertEquals(listOf("1"), actions.sentPrompts)

        gate.complete(Unit)
        // The hub moved on: the same row, no longer blocked.
        fleet.sessions.value = listOf(row(status = "working"))
        job.join()
        runCurrent()

        assertNull(vm.state.value.card)
        assertFalse(vm.state.value.answering)
        assertEquals(listOf(ID to 3L), actions.waited, "waited on the send's turn_seq_before")
    }

    @Test
    fun enter_and_escape_go_through_keys_rather_than_an_empty_prompt() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.answer(Answer.Enter).join()
        vm.answer(Answer.Escape).join()
        vm.answer(Answer.Interrupt).join()
        runCurrent()

        assertEquals(listOf("Enter", "Escape", "C-c"), actions.sentKeys)
        assertTrue(actions.sentPrompts.isEmpty(), "a key is never an empty send_prompt")
    }

    /** The trust prompt is a typed `y`/`n`, not a key: it goes through `send_prompt`. */
    @Test
    fun the_trust_prompts_y_goes_through_send_prompt() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(row(status = "blocked", stuck = "trust_prompt")))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.answer(Answer.Text("y")).join()
        runCurrent()

        assertEquals(listOf("y"), actions.sentPrompts)
        assertTrue(actions.sentKeys.isEmpty())
    }

    @Test
    fun a_timeout_keeps_the_card_and_says_still_waiting() = runTest {
        val actions = FakeActions()
        actions.waitAnswer = WaitResult(status = "timeout")
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.answer(Answer.Enter).join()
        runCurrent()

        assertNotNull(vm.state.value.card, "the session is still blocked, so the card stays")
        assertTrue(vm.state.value.stillWaiting)
        assertFalse(vm.state.value.answering)
    }

    /** A second answer while the first is still out would race the turn counter. */
    @Test
    fun a_second_answer_while_one_is_in_flight_is_ignored() = runTest {
        val actions = FakeActions()
        val gate = CompletableDeferred<Unit>()
        actions.sendGate = gate
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        val first = vm.answer(Answer.Enter)
        runCurrent()
        vm.answer(Answer.Escape).join()
        runCurrent()

        assertEquals(listOf("Enter"), actions.sentKeys)
        gate.complete(Unit)
        first.join()
    }

    @Test
    fun a_refused_answer_is_reported_and_leaves_the_card_answerable_again() = runTest {
        val actions = FakeActions()
        actions.sendFails = HubError.Tool("E_BUSY", "the session is mid-turn")
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.answer(Answer.Enter).join()
        runCurrent()

        assertEquals("E_BUSY: the session is mid-turn", vm.state.value.error?.details)
        assertFalse(vm.state.value.answering)
    }

    /**
     * `send_prompt` is not in the hub's readonly allow-list, and neither is
     * `send_prompt { keys }` — so a readonly device makes no call at all, and
     * the screen hides the chips rather than offering a refusal.
     */
    @Test
    fun a_readonly_device_answers_nothing() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope, canSendPrompts = false)

        vm.answer(Answer.Enter).join()
        vm.answer(Answer.Option(1, "Yes")).join()
        runCurrent()

        assertTrue(actions.sentKeys.isEmpty())
        assertTrue(actions.sentPrompts.isEmpty())
        assertTrue(actions.waited.isEmpty())
        assertFalse(vm.state.value.answering)
    }

    /**
     * A hub older than [HUB_VERSION_KEYS] can take neither a structured key
     * nor report a `pending_input`, so its blocked row is a bare one and the
     * card carries no chips at all — but it still says what is happening, and
     * the terminal is still the way through. The mapping itself is
     * `blockedCard`'s and is tested in `BlockedTest`; what this pins is that
     * the view model asks it with the hub's own version rather than assuming
     * the newest.
     */
    @Test
    fun an_old_hub_gets_a_card_with_no_answers_but_the_terminal_is_still_offered() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(row(status = "blocked", activity = "waiting for input: recreate turanga?")))
        fleet.hubVersion.value = "0.2.34"
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        runCurrent()

        val card = assertNotNull(vm.state.value.card)
        assertTrue(card.answers.isEmpty(), "no structured keys against a hub that cannot take them")
        assertTrue(card.terminalAvailable)
        assertEquals("recreate turanga?", card.headline)
    }

    /** The same row against a current hub does get its Enter/Esc chips. */
    @Test
    fun a_current_hub_gets_the_key_chips_for_the_same_row() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(row(status = "blocked", activity = "waiting for input: recreate turanga?")))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        runCurrent()

        assertEquals(listOf(Answer.Enter, Answer.Escape), assertNotNull(vm.state.value.card).answers)
    }

    // ---- The terminal fallback ----

    @Test
    fun show_terminal_captures_once_and_refreshes_on_session_events() = runTest {
        val actions = FakeActions()
        actions.captureAnswer = "1. Yes"
        val fleet = FakeFleetState(listOf(blockedRow()))
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.showTerminal().join()
        runCurrent()
        assertEquals("1. Yes", vm.state.value.terminal)
        assertEquals(1, actions.captures)

        actions.captureAnswer = "2. No"
        fleet.sessionChanges.tryEmit(ID)
        advanceTimeBy(SESSION_EVENT_DEBOUNCE.inWholeMilliseconds + 1)
        runCurrent()
        assertEquals("2. No", vm.state.value.terminal)

        vm.hideTerminal()
        runCurrent()
        assertNull(vm.state.value.terminal)
    }

    /** Hidden, the capture stops costing a hub call on every session event. */
    @Test
    fun a_hidden_terminal_is_not_recaptured_on_session_events() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(blockedRow()))
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        runCurrent()

        fleet.sessionChanges.tryEmit(ID)
        advanceTimeBy(SESSION_EVENT_DEBOUNCE.inWholeMilliseconds + 1)
        runCurrent()

        assertEquals(0, actions.captures)
    }

    @Test
    fun a_capture_that_fails_says_so_rather_than_showing_an_empty_pane() = runTest {
        val actions = FakeActions()
        actions.captureFails = HubError.Tool("E_BG_SESSION", "no tmux pane")
        val fleet = FakeFleetState(listOf(blockedRow()))
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.showTerminal().join()
        runCurrent()

        assertEquals("E_BG_SESSION: no tmux pane", vm.state.value.error?.details)
        assertNull(vm.state.value.terminal)
    }

    // ---- The card and the composer take turns ----

    /**
     * An answer is out for up to [ANSWER_WAIT_SECONDS] — `waitForTurn` is most
     * of that window — and a prompt typed into the composer meanwhile would
     * race the very turn counter the answer is waiting past. The two guards
     * have to know about each other; each one alone only stops its own path
     * from doubling up.
     */
    @Test
    fun send_is_disabled_while_an_answer_is_in_flight() = runTest {
        val actions = FakeActions()
        val gate = CompletableDeferred<Unit>()
        actions.sendGate = gate
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.onDraftChange("ship it")
        runCurrent()
        assertTrue(vm.state.value.canSend, "setup: the composer is live before the answer")

        val answering = vm.answer(Answer.Enter)
        runCurrent()
        assertFalse(vm.state.value.canSend, "an answer in flight must darken Send")
        assertFalse(vm.state.value.canAnswer, "and darken the chips it came from")
        vm.send().join()
        assertTrue(actions.sentPrompts.isEmpty(), "and send must not even try")

        gate.complete(Unit)
        answering.join()
        runCurrent()
        assertTrue(vm.state.value.canSend, "live again once the answer has landed")
    }

    /** The same rule from the other side. */
    @Test
    fun the_card_refuses_an_answer_while_a_prompt_is_in_flight() = runTest {
        val actions = FakeActions()
        val gate = CompletableDeferred<Unit>()
        actions.sendGate = gate
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.onDraftChange("ship it")
        runCurrent()

        val sending = vm.send()
        runCurrent()
        assertTrue(vm.state.value.sending, "setup: the prompt is out")
        assertFalse(vm.state.value.canAnswer, "and the chips say so before the tap does")
        vm.answer(Answer.Enter).join()

        assertTrue(actions.sentKeys.isEmpty(), "the card must not answer over a prompt in flight")
        assertTrue(actions.waited.isEmpty())
        gate.complete(Unit)
        sending.join()
    }

    /**
     * `stillWaiting` is about the answer just sent for the card on screen. Once
     * that card is gone the episode is over, so the next prompt — a different
     * question, nothing sent for it yet — must not inherit the line.
     */
    @Test
    fun still_waiting_does_not_survive_the_card_it_was_reported_for() = runTest {
        val actions = FakeActions()
        actions.waitAnswer = WaitResult(status = "timeout")
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.answer(Answer.Enter).join()
        runCurrent()
        assertTrue(vm.state.value.stillWaiting, "setup: the wait timed out")

        fleet.sessions.value = listOf(row(status = "working"))
        runCurrent()
        assertNull(vm.state.value.card)
        assertFalse(vm.state.value.stillWaiting)

        // A different prompt, later. Nothing has been sent for this one.
        fleet.sessions.value = listOf(blockedRow())
        runCurrent()
        assertNotNull(vm.state.value.card)
        assertFalse(vm.state.value.stillWaiting, "a fresh card starts with nothing sent for it")
    }

    /**
     * The rows a refused hub leaves behind are the ones from the connection
     * BEFORE it — `FleetRepository` sets `Refused` and returns without
     * clearing the snapshot — so the card outlives the refusal, and the only
     * thing that can stop it calling the hub is the same gate `send()` has.
     */
    @Test
    fun a_refused_hub_gets_no_answer_even_though_the_card_is_still_drawn() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        fleet.status.value = ConnectionStatus.Refused("this hub is too old for this app")
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        runCurrent()

        assertNotNull(vm.state.value.card, "setup: the stale row still draws a card")
        assertFalse(vm.state.value.connected)
        assertFalse(vm.state.value.canAnswer, "the chips go dark rather than being tapped into nothing")
        vm.answer(Answer.Enter).join()
        vm.answer(Answer.Option(1, "Yes")).join()
        runCurrent()

        assertTrue(actions.sentKeys.isEmpty())
        assertTrue(actions.sentPrompts.isEmpty())
        assertTrue(actions.waited.isEmpty())
    }

    @Test
    fun an_offline_hub_gets_no_answer_either() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        fleet.status.value = ConnectionStatus.Offline(STOPPED)
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        runCurrent()

        vm.answer(Answer.Enter).join()
        runCurrent()

        assertTrue(actions.sentKeys.isEmpty(), "answer must not try while the hub is unreachable")
        assertFalse(vm.state.value.answering)
    }

    /** And it comes back the moment the hub does, like Send. */
    @Test
    fun an_answer_works_again_once_the_hub_is_reachable() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        fleet.status.value = ConnectionStatus.Offline(STOPPED)
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        runCurrent()
        vm.answer(Answer.Enter).join()
        assertTrue(actions.sentKeys.isEmpty())

        fleet.status.value = ConnectionStatus.Connected("0.9.3")
        runCurrent()
        vm.answer(Answer.Enter).join()
        runCurrent()

        assertEquals(listOf("Enter"), actions.sentKeys)
    }

    // ---- Task 4: the overflow menu's management actions ----

    /** The ordinary row from [row()] is manageable: not readonly, present, not the controller. */
    @Test
    fun canManage_is_true_for_an_ordinary_row_on_a_full_credential() = runTest {
        val vm = SessionViewModel(ID, FakeFleetState(), FakeActions(), backgroundScope)
        runCurrent()
        assertTrue(vm.state.value.canManage)
        assertTrue(vm.state.value.canRestart)
        assertTrue(vm.state.value.canKill)
    }

    @Test
    fun canManage_is_false_for_a_readonly_credential() = runTest {
        val vm = SessionViewModel(ID, FakeFleetState(), FakeActions(), backgroundScope, canSendPrompts = false)
        runCurrent()
        assertFalse(vm.state.value.canManage)
    }

    @Test
    fun canManage_is_false_once_the_session_leaves_the_fleet() = runTest {
        val fleet = FakeFleetState()
        val vm = SessionViewModel(ID, fleet, FakeActions(), backgroundScope)
        fleet.sessions.value = emptyList()
        runCurrent()
        assertFalse(vm.state.value.canManage)
    }

    /** The hub refuses `kill_session` against the controller with `E_INVALID_STATE`; the app never tries. */
    @Test
    fun canManage_canRestart_and_canKill_are_all_false_for_the_controller() = runTest {
        val fleet = FakeFleetState(listOf(row().copy(isController = true)))
        val vm = SessionViewModel(ID, fleet, FakeActions(), backgroundScope)
        runCurrent()

        assertFalse(vm.state.value.canManage)
        assertFalse(vm.state.value.canRestart)
        assertFalse(vm.state.value.canKill)
    }

    /** The hub refuses `kill_session` on an external session with `E_INVALID_STATE`; Restart and Tags stay offered. */
    @Test
    fun canKill_alone_is_false_for_an_external_session() = runTest {
        val fleet = FakeFleetState(listOf(row().copy(kind = "external")))
        val vm = SessionViewModel(ID, fleet, FakeActions(), backgroundScope)
        runCurrent()

        assertTrue(vm.state.value.canManage)
        assertTrue(vm.state.value.canRestart)
        assertFalse(vm.state.value.canKill)
    }

    @Test
    fun restart_calls_the_hub_and_refetches() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        val readsBeforeRestart = actions.reads

        vm.restart().join()
        runCurrent()

        assertEquals(listOf(ID), actions.restarted)
        assertTrue(actions.reads > readsBeforeRestart, "a restart should pull the fresh row/conversation in")
        assertNull(vm.state.value.error)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun restart_no_ops_for_the_controller() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(row().copy(isController = true)))
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.restart().join()
        runCurrent()

        assertTrue(actions.restarted.isEmpty())
    }

    @Test
    fun restart_no_ops_for_a_readonly_credential() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope, canSendPrompts = false)

        vm.restart().join()
        runCurrent()

        assertTrue(actions.restarted.isEmpty())
    }

    @Test
    fun safe_kill_calls_the_hub_and_refetches() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        val readsBefore = actions.reads

        vm.safeKill().join()
        runCurrent()

        assertEquals(listOf(ID), actions.safeKilled)
        assertTrue(actions.reads > readsBefore)
    }

    @Test
    fun set_tags_calls_the_hub_and_refetches() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        val readsBefore = actions.reads

        vm.setTags(listOf("a", "b")).join()
        runCurrent()

        assertEquals(listOf(ID to listOf("a", "b")), actions.tags)
        assertTrue(actions.reads > readsBefore)
    }

    @Test
    fun rename_calls_the_hub_and_refetches() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        val readsBefore = actions.reads

        vm.rename("new name").join()
        runCurrent()

        assertEquals(listOf(ID to "new name"), actions.renamed)
        assertTrue(actions.reads > readsBefore)
    }

    /**
     * `kill_session` is refused for the controller (`canManage` is already
     * false there — see [canManage_canRestart_and_canKill_are_all_false_for_the_controller])
     * and for a readonly device, which never has `canManage` either.
     */
    @Test
    fun kill_is_refused_for_the_controller_and_for_a_readonly_device() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(row().copy(isController = true)))
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        runCurrent()
        assertFalse(vm.state.value.canManage)

        vm.kill().join()
        runCurrent()
        assertTrue(actions.killed.isEmpty())

        val readonlyActions = FakeActions()
        val readonlyVm = SessionViewModel(ID, FakeFleetState(), readonlyActions, backgroundScope, canSendPrompts = false)
        readonlyVm.kill().join()
        runCurrent()
        assertTrue(readonlyActions.killed.isEmpty())
    }

    @Test
    fun kill_no_ops_for_an_external_session() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(row().copy(kind = "external")))
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.kill().join()
        runCurrent()

        assertTrue(actions.killed.isEmpty())
    }

    @Test
    fun kill_calls_the_hub_and_refetches() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        val readsBefore = actions.reads

        vm.kill().join()
        runCurrent()

        assertEquals(listOf(ID), actions.killed)
        assertTrue(actions.reads > readsBefore)
    }

    /** `friendly(t)` already maps this code; a kill refused this way reads as a desktop confirmation, not a generic error. */
    @Test
    fun a_confirm_required_refusal_reads_as_a_desktop_confirmation() = runTest {
        val actions = FakeActions()
        actions.killError = HubError.Tool("E_CONFIRM_REQUIRED", "confirm on the desktop")
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)

        vm.kill().join()
        runCurrent()

        assertEquals("Needs a confirmation on the desktop", vm.state.value.error?.title)
        assertFalse(vm.state.value.busy, "a failed call must still clear busy")
    }

    @Test
    fun safe_kill_state_follows_the_row() = runTest {
        val fleet = FakeFleetState(listOf(row().copy(safeKillState = "ready")))
        val vm = SessionViewModel(ID, fleet, FakeActions(), backgroundScope)
        runCurrent()

        assertEquals("ready", vm.state.value.safeKillState)
    }

    @Test
    fun safe_kill_state_is_null_when_the_row_has_none() = runTest {
        val vm = SessionViewModel(ID, FakeFleetState(), FakeActions(), backgroundScope)
        runCurrent()

        assertNull(vm.state.value.safeKillState)
    }

    /** Every management action is refused, exactly like `send`, while the hub is unreachable. */
    @Test
    fun management_actions_are_refused_while_offline() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState()
        fleet.status.value = ConnectionStatus.Offline("not connected")
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        vm.restart().join()
        vm.safeKill().join()
        vm.kill().join()
        vm.setTags(listOf("x")).join()
        vm.rename("y").join()
        runCurrent()

        assertTrue(actions.restarted.isEmpty())
        assertTrue(actions.safeKilled.isEmpty())
        assertTrue(actions.killed.isEmpty())
        assertTrue(actions.tags.isEmpty())
        assertTrue(actions.renamed.isEmpty())
    }

    /** A second management call while one is already in flight is ignored — the same single-flight rule as `send`/`answer`. */
    @Test
    fun a_second_management_call_while_one_is_in_flight_is_ignored() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()

        val gate = CompletableDeferred<Unit>()
        actions.readGate = gate
        val first = vm.restart()
        runCurrent()
        assertTrue(vm.state.value.busy, "restart's own refetch is what keeps busy set")

        vm.rename("second").join()
        runCurrent()
        assertTrue(actions.renamed.isEmpty(), "a second management call must not run while the first is still busy")

        gate.complete(Unit)
        first.join()
        runCurrent()
        assertFalse(vm.state.value.busy)
    }

    // ---- Fix round 1: management writes take their turn alongside send/answer ----
    //
    // `canSendNow`/`canAnswerNow` already excluded each other (`!l.sending &&
    // !l.answering`), but the new management family's own `runManaged` only
    // checked its own `busy` flag, and `canSendNow`/`canAnswerNow` were never
    // taught about `busy` either — so a kill could run while an answer was
    // still out, or Send could fire while a restart's refetch was still in
    // flight. All five guards now share one "nothing else in flight" rule.

    /** The new direction: a management call must not run while an answer is still out. */
    @Test
    fun kill_is_refused_while_an_answer_is_in_flight() = runTest {
        val actions = FakeActions()
        val gate = CompletableDeferred<Unit>()
        actions.sendGate = gate
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)

        val answering = vm.answer(Answer.Enter)
        runCurrent()
        assertTrue(vm.state.value.answering, "setup: the answer is out")

        vm.kill().join()
        runCurrent()
        assertTrue(actions.killed.isEmpty(), "a management call must not race an answer still in flight")

        gate.complete(Unit)
        answering.join()
    }

    /** The other new direction: a management call must not run while a prompt is still out. */
    @Test
    fun restart_is_refused_while_sending_is_in_flight() = runTest {
        val actions = FakeActions()
        val gate = CompletableDeferred<Unit>()
        actions.sendGate = gate
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.onDraftChange("ship it")
        runCurrent()

        val sending = vm.send()
        runCurrent()
        assertTrue(vm.state.value.sending, "setup: the prompt is out")

        vm.restart().join()
        runCurrent()
        assertTrue(actions.restarted.isEmpty(), "a management call must not race a prompt still in flight")

        gate.complete(Unit)
        sending.join()
    }

    /** The pre-existing direction, now including the new family: Send must go dark while a management call is out. */
    @Test
    fun send_is_disabled_while_a_management_action_is_in_flight() = runTest {
        val actions = FakeActions()
        val vm = SessionViewModel(ID, FakeFleetState(), actions, backgroundScope)
        vm.load().join()
        vm.onDraftChange("ship it")
        runCurrent()
        assertTrue(vm.state.value.canSend, "setup: the composer is live before the restart")

        val gate = CompletableDeferred<Unit>()
        actions.readGate = gate
        val restarting = vm.restart()
        runCurrent()
        assertTrue(vm.state.value.busy, "setup: the restart's own refetch is outstanding")
        assertFalse(vm.state.value.canSend, "a management call in flight must darken Send")

        vm.send().join()
        assertTrue(actions.sentPrompts.isEmpty(), "and send must not even try")

        gate.complete(Unit)
        restarting.join()
        runCurrent()
        assertTrue(vm.state.value.canSend, "live again once the management call has landed")
    }

    /** And the card's own chips, exactly the same way. */
    @Test
    fun answer_is_refused_while_a_management_action_is_in_flight() = runTest {
        val actions = FakeActions()
        val fleet = FakeFleetState(listOf(blockedRow()))
        fleet.hubVersion.value = HUB_VERSION_KEYS
        val vm = SessionViewModel(ID, fleet, actions, backgroundScope)
        vm.load().join()

        val gate = CompletableDeferred<Unit>()
        actions.readGate = gate
        val restarting = vm.restart()
        runCurrent()
        assertTrue(vm.state.value.busy, "setup: the restart's own refetch is outstanding")
        assertFalse(vm.state.value.canAnswer, "a management call in flight must darken the card's chips too")

        vm.answer(Answer.Enter).join()
        assertTrue(actions.sentKeys.isEmpty(), "and the card must not answer over a management call in flight")

        gate.complete(Unit)
        restarting.join()
        runCurrent()
        assertTrue(vm.state.value.canAnswer, "live again once the management call has landed")
    }
}
