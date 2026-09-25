package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.ui.explain
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.Ticket
import dev.claudefleet.mobile.net.EventStream
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubClient
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.HubEvent
import dev.claudefleet.mobile.net.contractVerdict
import dev.claudefleet.mobile.net.sentence
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where the app stands with its hub. */
sealed interface ConnectionStatus {
    /** The stream is up. [hubVersion] is what the `ready` frame named. */
    data class Connected(val hubVersion: String?) : ConnectionStatus

    /**
     * Attempt [attempt] is pending or underway — 1 is the first connect, so a
     * screen that says "reconnecting" should read the attempt, not the name.
     * [reason] is why the last one ended, and is null before the first.
     *
     * [reason] is drawn by `ConnectionBanner` — if it were computed but never
     * read, a person watching the app retry would see a rising counter and
     * never what it was retrying from. It is a sentence from `explain`, not a
     * raw throwable message, for the same reason every other string that
     * reaches a screen is.
     */
    data class Reconnecting(val attempt: Int, val reason: String?) : ConnectionStatus

    /** Not streaming, and not going to without a nudge. */
    data class Offline(val reason: String) : ConnectionStatus

    /**
     * The hub answered, and this build will not talk to it: its `ready` frame
     * named a wire contract outside
     * [dev.claudefleet.mobile.net.MIN_HUB_CONTRACT]..[dev.claudefleet.mobile.net.MAX_HUB_CONTRACT].
     *
     * Its own state rather than an [Offline] carrying a different sentence,
     * because the two call for opposite behaviour. Offline means "the hub may
     * well be there, keep trying": a session screen probes it, and Send
     * follows that probe. Refused means "the hub is there and must not be
     * used" — a probe would answer `true` and hand a person a Send button for
     * a hub whose shape this build has already decided it cannot read. So
     * [reason] is drawn, nothing is probed, and no tool is called.
     */
    data class Refused(val reason: String) : ConnectionStatus
}

/**
 * The app's picture of the fleet: one snapshot, kept current by the hub's own
 * change stream rather than by polling.
 *
 * Three rules live here:
 *
 *  1. **`ready` means resync.** Anything that happened while the app was away
 *     was never streamed to it, so every connection starts with a full refetch.
 *  2. **`lagged` means resync too.** The hub's subscriber ring overflowed and
 *     it is telling us the picture has a hole in it. Reconnecting and re-listing
 *     is the only honest recovery; patching on is not.
 *  3. **A drop is retried with a growing wait; a 401 is not retried at all.**
 *     Only the operator can issue another token, so hammering a revoked one
 *     costs the phone's battery and the hub's rate limiter for nothing.
 *
 * The snapshot outlives the connection on purpose: `stop()` leaves the last
 * rows in place so a backgrounded app has something to draw on resume, behind
 * whatever banner [status] calls for.
 */
class FleetRepository(
    private val client: HubClient,
    private val events: EventStream,
    private val scope: CoroutineScope,
    /** How long to wait after N consecutive failures. Injectable for tests. */
    private val backoff: (Int) -> Duration = ::reconnectDelay,
    /**
     * What to do when the hub answers 401 — drop the credential and return to
     * Pair.
     *
     * This repository is the one caller that holds a raw [HubClient] rather than
     * going through `AppSession.withClient`, which is where "a 401 drops the
     * credential" lives. Without this it stopped the loop and said so in a
     * banner, and the app sat on a revoked token while the identical 401 through
     * `HubSessionActions` would have routed to Pair. A callback rather than an
     * `AppSession` reference, so the repository still knows nothing about
     * storage and the test can count the calls.
     *
     * Failures are swallowed on purpose: the stream is already going `Offline`
     * and a store that will not clear must not turn that into a crash.
     */
    private val onRevoked: suspend () -> Unit = {},
    /** This device's clock, in unix seconds. Injectable so the skew is testable. */
    private val clock: () -> Long = { epochSeconds() },
) : FleetState {
    // Starts empty rather than from a cache: see the "no cold-start cache"
    // deviation in the design appendix — the app's one persistence seam is
    // sized for a credential, not fleet data.
    private val _sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    override val sessions: StateFlow<List<SessionRow>> = _sessions.asStateFlow()

    private val _hosts = MutableStateFlow<List<HostRow>>(emptyList())
    override val hosts: StateFlow<List<HostRow>> = _hosts.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectRow>>(emptyList())
    override val projects: StateFlow<List<ProjectRow>> = _projects.asStateFlow()

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Offline(NOT_STARTED))
    override val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    // Written from every `ready` and never cleared: see [FleetState.hubVersion].
    // A drop does not un-see which hub this is, and the next connection's own
    // `ready` overwrites it with whatever is there now.
    private val _hubVersion = MutableStateFlow<String?>(null)
    override val hubVersion: StateFlow<String?> = _hubVersion.asStateFlow()
    private val _clockSkewSeconds = MutableStateFlow(0L)
    override val clockSkewSeconds: StateFlow<Long> = _clockSkewSeconds.asStateFlow()

    // Buffered rather than rendezvous: emitting must never suspend `follow()`
    // waiting on a session screen that may not be open at all. `DROP_OLDEST`
    // is fine because this is a hint to refetch, not the fact itself — a
    // dropped id just means the next one (or the caller's own `ready`/`lagged`
    // resync) carries the same signal.
    private val _sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val sessionChanges: Flow<Long> = _sessionChanges.asSharedFlow()

    private val _capabilities = MutableStateFlow(HubCapabilities())
    override val capabilities: StateFlow<HubCapabilities> = _capabilities.asStateFlow()

    private val _tickets = MutableStateFlow<List<Ticket>>(emptyList())
    override val tickets: StateFlow<List<Ticket>> = _tickets.asStateFlow()

    private val _myWork = MutableStateFlow<Set<Long>?>(null)
    override val myWork: StateFlow<Set<Long>?> = _myWork.asStateFlow()

    override fun actionMissing(tool: String, action: String) {
        _capabilities.update { it.forgetting(tool, action) }
    }

    override fun rememberTickets(tickets: List<Ticket>) {
        if (tickets.isEmpty()) return
        val fresh = tickets.associateBy { it.id }
        _tickets.update { cached ->
            cached.map { fresh[it.id] ?: it } + tickets.filter { t -> cached.none { it.id == t.id } }
        }
    }

    private var job: Job? = null

    /** This connection's `tools/list` and My-work read; replaced on every `ready`. */
    private var discovery: Job? = null

    /** Subscribe, and keep subscribing. Idempotent: a second call is a no-op. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { follow() }
    }

    /** Drop the subscription. The snapshot stays; [status] goes [ConnectionStatus.Offline]. */
    fun stop() {
        val running = job
        job = null
        running?.cancel()
        discovery?.cancel()
        myWorkRead?.cancel()
        _status.value = ConnectionStatus.Offline(STOPPED)
    }

    /**
     * Re-list everything, replacing the snapshot.
     *
     * All or nothing: the flows are written only once every call has answered,
     * so a refresh that fails half way leaves the old picture intact rather than
     * pairing new sessions with stale hosts. Failures are raised, not swallowed
     * — a pull-to-refresh has to be able to say it did not work.
     *
     * The three calls go out together. They were sequential, and this runs on
     * every app open and every reconnect: three round trips at a measured
     * ~105 ms each from the same continent as the hub, on a link where that is
     * the optimistic figure. `coroutineScope` keeps the contract above — the
     * first failure cancels its siblings and is raised before `publish`, so a
     * half-built snapshot still cannot reach the flows.
     */
    override suspend fun refresh() {
        relist()
        // A pull or a `lagged` resync re-reads *My work* too: which tickets
        // are the person's changes on the tracker, and no frame says so. A
        // `ready` does not come through here — its discovery reads it.
        if (_capabilities.value.work) readMyWorkSoon()
    }

    /**
     * The re-list itself.
     *
     * The ticket cache is emptied by it, not carried over. The cache is newer
     * than the session rows only while every `work:item` frame since it was
     * filled has been applied, and a re-list is exactly the case where some
     * were not — the stream lagged or dropped. Carried over, a stale ticket
     * would be overlaid on rows the re-list just made fresh (the work
     * heading's status, a chip's title). What refills it is whatever next
     * reads tickets: *My work*, or the Tickets sheet.
     */
    private suspend fun relist() {
        // Spent before the re-list, not after: the fresh snapshot is newer
        // than every frame the id points past, so a later resume from it would
        // replay older rows over newer ones. Cleared up front so a refresh
        // that fails still leaves no id promising continuity it cannot give.
        lastEventId = null
        coroutineScope {
            val sessions = async { client.listSessions() }
            val hosts = async { client.listHosts() }
            val projects = async { client.listProjects() }
            publish(FleetSnapshot(sessions.await(), hosts.await(), projects.await(), tickets = emptyList()))
        }
    }

    /**
     * The `id:` of the last row frame applied to the snapshot, which the next
     * connection sends as `Last-Event-ID` so a dropped stream costs the frames
     * it missed instead of a full re-list (61 KB and three round trips,
     * measured, on every lift, tunnel and app switch).
     *
     * Taken from what [follow] APPLIED, not from what the transport read: a
     * frame read into a buffer and lost with the connection must not be
     * counted as seen. [refresh] clears it.
     */
    private var lastEventId: String? = null

    private suspend fun follow() {
        var failures = 0
        var reason: String? = null
        // The first attempt, announced once. Every later one is announced by
        // the line at the foot of the loop, before the wait — which is where it
        // has to be, so the banner names the attempt a person is waiting
        // *through* rather than only after the wait is over. This used to be
        // published at the head of the loop as well, with the same `failures`
        // and the same `reason`: an identical value into a `StateFlow`, which
        // conflates it, on every iteration but the first.
        _status.value = ConnectionStatus.Reconnecting(1, null)
        while (true) {
            try {
                // Set once this connection's `ready` names a contract this
                // build does not trust, and never cleared for the rest of
                // this `collect` — every later frame on the SAME connection
                // is then a no-op. Reset to false on every new attempt
                // (declared inside the loop body, not above it) so a fresh
                // connection — one that might carry an upgraded hub, or run
                // against an upgraded app — gets its own fair verdict rather
                // than inheriting the last one's refusal.
                var contractRefused = false
                events.connect(lastEventId).collect { event ->
                    if (contractRefused) return@collect
                    when (event) {
                        is HubEvent.Ready -> {
                            // Before the contract verdict, deliberately: a
                            // refused hub is still a hub whose version a
                            // screen may want to name, and this is the only
                            // frame that carries it.
                            _hubVersion.value = event.version
                            val refusal = contractVerdict(event.contract).sentence()
                            if (refusal != null) {
                                // Not a transport failure — the hub answered
                                // fine, just with a contract this build (or
                                // that hub) is on the wrong side of. Skip the
                                // resync and ignore every later frame of this
                                // connection; the reconnect/backoff loop below
                                // is untouched, so an upgrade on either side
                                // is picked up the next time it connects.
                                contractRefused = true
                                // Forget what the LAST hub let this token do.
                                // Discovery is skipped on a refused hub, and
                                // the work screens gate on capabilities alone:
                                // left in place, the previous connection's
                                // `work_link` would keep Confirm, Start and
                                // Resume live against a hub this build has
                                // just decided not to call. A My work read
                                // still in flight would refill `myWork`.
                                discovery?.cancel()
                                myWorkRead?.cancel()
                                _capabilities.value = HubCapabilities()
                                _myWork.value = null
                                _status.value = ConnectionStatus.Refused(refusal)
                                return@collect
                            }
                            // Reset AFTER the refetch, not before. A connection
                            // is not a success until the resync it exists for
                            // has worked: a hub whose `/events` answers and
                            // whose `/mcp` does not used to zero the count on
                            // every `ready`, so the wait never grew past its
                            // first step and the phone reconnected once a second
                            // for as long as the half-outage lasted.
                            // A hub that honoured `Last-Event-ID` replays what
                            // this app missed as ordinary row frames, so there
                            // is nothing to re-list. Anything short of a clear
                            // yes — `false`, or a hub that cannot resume — is
                            // the old path.
                            if (event.resumed != true) relist()
                            failures = 0
                            // Re-measured per connection, so a device whose
                            // clock is corrected by NTP heals on the next
                            // reconnect rather than staying wrong until a
                            // restart. A hub that sends no `now` leaves the
                            // last reading alone rather than zeroing it.
                            event.now?.let { _clockSkewSeconds.value = it - clock() }
                            _status.value = ConnectionStatus.Connected(event.version)
                            _sessionChanges.tryEmit(ALL_SESSIONS_CHANGED)
                            discover()
                        }
                        is HubEvent.Lagged -> {
                            refresh()
                            _sessionChanges.tryEmit(ALL_SESSIONS_CHANGED)
                        }
                        is HubEvent.Row -> {
                            publish(snapshot().applying(event))
                            event.id?.let { lastEventId = it }
                            event.sessionId()?.let { _sessionChanges.tryEmit(it) }
                        }
                    }
                }
                reason = STREAM_CLOSED
            } catch (e: CancellationException) {
                throw e
            } catch (e: HubError.Unauthorized) {
                _status.value = ConnectionStatus.Offline(REVOKED_CREDENTIAL_REASON)
                try {
                    onRevoked()
                } catch (_: Exception) {
                    // Already Offline and already giving up; see the KDoc.
                }
                return
            } catch (t: Throwable) {
                // `explain(t)`, not `t.message`. This was a SECOND
                // throwable-to-words mapping, which is the one thing `explain`
                // exists to prevent: an unexpected throwable's own message
                // walking onto the screen. It mattered little while `reason`
                // was never drawn; now that the banner shows it, it is the
                // difference between a sentence written for a person and
                // whatever a library author put in a constructor.
                reason = explain(t)
            }
            failures += 1
            _status.value = ConnectionStatus.Reconnecting(failures + 1, reason)
            delay(backoff(failures))
        }
    }

    /**
     * Ask the hub what this token may call, then — when it has the work graph
     * and a tracker — which tickets are *My work*.
     *
     * Once per connection, off the stream's own coroutine: it is a second
     * opinion on features, not part of the resync, so a slow `tools/list`
     * must not hold `Connected` back and a failed one must not tear the
     * stream down. A failure reads as the old hub — every work feature
     * hidden — which is the safe answer; the next `ready` asks again.
     * `missing` actions reset here with the rest, because a reconnect may be
     * to an upgraded hub.
     */
    private fun discover() {
        discovery?.cancel()
        discovery = scope.launch {
            val caps = try {
                HubCapabilities.of(client.toolCatalog())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                HubCapabilities()
            }
            _capabilities.value = caps
            myWorkRead?.cancel()
            _myWork.value = if (caps.work) readMyWork() else null
        }
    }

    /** A *My work* read in flight from [refresh]; a newer read replaces it. */
    private var myWorkRead: Job? = null

    private fun readMyWorkSoon() {
        myWorkRead?.cancel()
        myWorkRead = scope.launch { _myWork.value = readMyWork() }
    }

    /**
     * The item ids in the hub's *My work* view, or null when there is none to
     * ask for — no tracker — or the read failed, which hides the chip rather
     * than filtering the list to nothing. The tickets it answers refill the
     * cache.
     */
    private suspend fun readMyWork(): Set<Long>? = try {
        if (client.workTrackers().isEmpty()) {
            null
        } else {
            client.workTickets(MY_WORK).also { rememberTickets(it) }.map { it.id }.toSet()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    private fun snapshot() = FleetSnapshot(_sessions.value, _hosts.value, _projects.value, _tickets.value)

    private fun publish(snapshot: FleetSnapshot) {
        _sessions.value = snapshot.sessions
        _hosts.value = snapshot.hosts
        _projects.value = snapshot.projects
        _tickets.value = snapshot.tickets
    }

    private companion object {
        const val NOT_STARTED = "not connected yet"
        const val STREAM_CLOSED = "the hub closed the stream"
    }
}

/**
 * The reason [FleetRepository.stop] publishes — the lifecycle put the stream
 * down on purpose, so nothing is coming and nothing should be tried.
 *
 * Out here rather than in the repository's private companion because a screen
 * has to be able to tell this Offline apart from the ones worth probing
 * through: `SessionViewModel` does not ping a hub the app itself has stopped
 * talking to. One constant, compared against, rather than the same four words
 * written down twice.
 */
internal const val STOPPED = "not connected"

/**
 * Not a real session id: the signal a `ready` or `lagged` resync emits on
 * [FleetRepository.sessionChanges] once its `refresh()` has landed, since a
 * full snapshot has no per-row events of its own for a screen to key on.
 *
 * A resync can touch far more rows than `sessionChanges`'s 16-slot buffer
 * holds, so `tryEmit`-ing every id in the fresh snapshot could have that
 * buffer's own `DROP_OLDEST` silently drop the one id an open session screen
 * is actually filtering for. One sentinel per resync avoids that: it never
 * scales with the snapshot's size, and dropping an *older*, undelivered copy
 * of it changes nothing, since a newer one carries the identical instruction.
 * `SessionViewModel` treats it as "refetch me too," alongside its own id.
 */
internal const val ALL_SESSIONS_CHANGED: Long = Long.MIN_VALUE

/** The hub's view name for the tickets assigned to the tracker account. */
internal const val MY_WORK = "mine"

/** The first wait, after one failure. */
internal val BASE_RECONNECT_DELAY = 1.seconds

/**
 * The longest wait between attempts. A phone that has been out of signal for an
 * hour should notice the hub within half a minute of coming back, and half a
 * minute of idle radio is cheap.
 */
internal val MAX_RECONNECT_DELAY = 30.seconds

/**
 * How long to wait after [failures] consecutive failed connections: 1 s, 2 s,
 * 4 s, 8 s, 16 s, then [MAX_RECONNECT_DELAY] forever.
 *
 * Deterministic, with no jitter. One phone reconnecting to one hub is not a
 * thundering herd, and a fixed schedule is one a test can assert exactly.
 */
internal fun reconnectDelay(failures: Int): Duration {
    val doublings = (failures - 1).coerceIn(0, 5)
    return minOf(BASE_RECONNECT_DELAY * (1 shl doublings), MAX_RECONNECT_DELAY)
}
