package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.EventStream
import dev.claudefleet.mobile.net.HubClient
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.HubEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
     */
    data class Reconnecting(val attempt: Int, val reason: String?) : ConnectionStatus

    /** Not streaming, and not going to without a nudge. */
    data class Offline(val reason: String) : ConnectionStatus
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
) {
    private val _sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    val sessions: StateFlow<List<SessionRow>> = _sessions.asStateFlow()

    private val _hosts = MutableStateFlow<List<HostRow>>(emptyList())
    val hosts: StateFlow<List<HostRow>> = _hosts.asStateFlow()

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Offline(NOT_STARTED))
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private var job: Job? = null

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
        _status.value = ConnectionStatus.Offline(STOPPED)
    }

    /**
     * Re-list everything, replacing the snapshot.
     *
     * All or nothing: the flows are written only once both calls have answered,
     * so a refresh that fails half way leaves the old picture intact rather than
     * pairing new sessions with stale hosts. Failures are raised, not swallowed
     * — a pull-to-refresh has to be able to say it did not work.
     */
    suspend fun refresh() {
        val sessions = client.listSessions()
        val hosts = client.listHosts()
        publish(FleetSnapshot(sessions, hosts))
    }

    private suspend fun follow() {
        var failures = 0
        var reason: String? = null
        while (true) {
            _status.value = ConnectionStatus.Reconnecting(failures + 1, reason)
            try {
                events.connect().collect { event ->
                    when (event) {
                        is HubEvent.Ready -> {
                            // Reset before the refetch: the connection itself
                            // succeeded, whatever the refetch then does.
                            failures = 0
                            refresh()
                            _status.value = ConnectionStatus.Connected(event.version)
                        }
                        is HubEvent.Lagged -> refresh()
                        is HubEvent.Row -> publish(snapshot().applying(event))
                    }
                }
                reason = STREAM_CLOSED
            } catch (e: CancellationException) {
                throw e
            } catch (e: HubError.Unauthorized) {
                _status.value = ConnectionStatus.Offline(REVOKED)
                return
            } catch (t: Throwable) {
                reason = t.message ?: t::class.simpleName ?: STREAM_CLOSED
            }
            failures += 1
            _status.value = ConnectionStatus.Reconnecting(failures + 1, reason)
            delay(backoff(failures))
        }
    }

    private fun snapshot() = FleetSnapshot(_sessions.value, _hosts.value)

    private fun publish(snapshot: FleetSnapshot) {
        _sessions.value = snapshot.sessions
        _hosts.value = snapshot.hosts
    }

    private companion object {
        const val NOT_STARTED = "not connected yet"
        const val STOPPED = "not connected"
        const val STREAM_CLOSED = "the hub closed the stream"
        const val REVOKED =
            "the hub no longer accepts this device's credential. Pair again to carry on."
    }
}

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
