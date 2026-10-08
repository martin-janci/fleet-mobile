package dev.claudefleet.mobile.notify

import dev.claudefleet.mobile.data.AppSession
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.Attention
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.store.Credentials
import dev.claudefleet.mobile.store.FakePrefs
import dev.claudefleet.mobile.store.Prefs
import dev.claudefleet.mobile.store.Secrets
import dev.claudefleet.mobile.store.SecretsUnavailable
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [runTest] skips virtual time whenever the test body waits on another
 * dispatcher, and the mock engine answers on one, so [NeedsYouCheck]'s own
 * timeout would expire before the reply arrived. These tests need real time.
 */
private fun realTime(block: suspend CoroutineScope.() -> Unit): TestResult =
    runTest { withContext(Dispatchers.Default) { block() } }

private const val BASE = "https://fleet.example.com"

private fun sse(body: String) = "event: message\ndata: $body\n\n"

private fun okResult(payloadJson: String): String {
    val text = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(payloadJson))
    return """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":$text}]}}"""
}

/** `list_sessions` rows: id → attention reason (null: needs nothing). */
private fun rows(vararg r: Pair<Long, String?>): String = r.joinToString(",", "[", "]") { (id, reason) ->
    val attention = reason?.let { ""","needs_attention":{"reason":"$it"}""" } ?: ""
    """{"id":$id,"tmux_name":"s$id","host_alias":"pine"$attention}"""
}

private class MemSecrets(
    var stored: Credentials? = Credentials(BASE, "tok-phone", "phone", "full"),
    private val unreadable: Boolean = false,
) : Secrets {
    override suspend fun read(): Credentials? {
        if (unreadable) throw SecretsUnavailable("the Keychain is not available before first unlock")
        return stored
    }
    override suspend fun write(credentials: Credentials) { stored = credentials }
    override suspend fun clear() { stored = null }
}

internal class RecordingPoster : AlertPoster {
    val posted = mutableListOf<Long>()
    val withdrawn = mutableListOf<Long>()
    override fun post(alert: NeedsYouAlert) { posted += alert.sessionId }
    override fun withdraw(sessionId: Long) { withdrawn += sessionId }
}

private class Hub {
    var requests = 0
    var reply: suspend () -> Pair<String, HttpStatusCode> = { sse(okResult("[]")) to HttpStatusCode.OK }
}

private fun check(
    hub: Hub,
    secrets: Secrets = MemSecrets(),
    prefs: Prefs = FakePrefs(),
    poster: AlertPoster = RecordingPoster(),
    timeout: Duration = 20.seconds,
): NeedsYouCheck {
    val engine = MockEngine {
        hub.requests++
        val (body, status) = hub.reply()
        val type = if (body.startsWith("event:")) "text/event-stream" else "application/json"
        respond(body, status, headersOf(HttpHeaders.ContentType, type))
    }
    return NeedsYouCheck(AppSession(secrets, HttpClient(engine)), prefs, poster, timeout)
}

class NeedsYouCheckTest {

    @Test
    fun the_first_look_is_a_baseline_and_says_nothing() = realTime {
        val hub = Hub().apply { reply = { sse(okResult(rows(1L to "waiting"))) to HttpStatusCode.OK } }
        val prefs = FakePrefs()
        val poster = RecordingPoster()

        check(hub, prefs = prefs, poster = poster).once()

        assertEquals(emptyList(), poster.posted)
        assertEquals(mapOf(1L to "waiting"), prefs.readSeen())
    }

    @Test
    fun a_session_that_comes_to_need_you_is_posted_once() = realTime {
        val hub = Hub().apply { reply = { sse(okResult(rows(1L to "waiting"))) to HttpStatusCode.OK } }
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to null)) }
        val poster = RecordingPoster()
        val c = check(hub, prefs = prefs, poster = poster)

        c.once()
        c.once()

        assertEquals(listOf(1L), poster.posted, "still waiting is not news the second time")
    }

    /** This phone's switches (redesign 14.11): a kind turned off is not posted, and is still remembered as seen. */
    @Test
    fun a_kind_turned_off_on_this_phone_is_not_posted() = realTime {
        val hub = Hub().apply { reply = { sse(okResult(rows(1L to "waiting", 2L to "failed"))) to HttpStatusCode.OK } }
        val prefs = FakePrefs().apply {
            writeSeen(mapOf(1L to null, 2L to null))
            writeNotifyKinds(NotifyKinds().with(NotifyKind.FAILED, on = false))
        }
        val poster = RecordingPoster()

        check(hub, prefs = prefs, poster = poster).once()

        assertEquals(listOf(1L), poster.posted, "the failed session is quiet, the waiting one is not")
        assertEquals(mapOf(1L to "waiting", 2L to "failed"), prefs.readSeen(), "turning Failed back on replays nothing")
    }

    @Test
    fun a_session_that_stops_needing_you_or_goes_away_is_withdrawn() = realTime {
        val hub = Hub().apply { reply = { sse(okResult(rows(1L to null))) to HttpStatusCode.OK } }
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to "waiting", 2L to "stuck")) }
        val poster = RecordingPoster()

        check(hub, prefs = prefs, poster = poster).once()

        assertEquals(listOf(1L, 2L), poster.withdrawn.sorted(), "1 settled, 2 was killed")
        assertEquals(emptyList(), poster.posted)
    }

    @Test
    fun a_401_unpairs_posts_nothing_and_keeps_the_last_look() = realTime {
        val hub = Hub().apply { reply = { "" to HttpStatusCode.Unauthorized } }
        val secrets = MemSecrets()
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to null)) }
        val poster = RecordingPoster()

        check(hub, secrets = secrets, prefs = prefs, poster = poster).once()

        assertNull(secrets.stored, "a 401 forgets the credential, as everywhere else in the app")
        assertEquals(emptyList(), poster.posted)
        assertEquals(mapOf(1L to null), prefs.readSeen())
    }

    @Test
    fun a_hub_error_keeps_the_last_look() = realTime {
        val hub = Hub().apply { reply = { "" to HttpStatusCode.InternalServerError } }
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to null)) }
        val poster = RecordingPoster()

        check(hub, prefs = prefs, poster = poster).once()

        assertEquals(emptyList(), poster.posted)
        assertEquals(mapOf(1L to null), prefs.readSeen())
    }

    @Test
    fun a_hub_that_never_answers_is_given_up_on() = realTime {
        val hub = Hub().apply { reply = { awaitCancellation() } }
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to null)) }
        val poster = RecordingPoster()

        check(hub, prefs = prefs, poster = poster, timeout = 100.milliseconds).once()

        assertEquals(emptyList(), poster.posted)
        assertEquals(mapOf(1L to null), prefs.readSeen())
    }

    @Test
    fun a_keychain_that_cannot_be_read_is_a_quiet_no() = realTime {
        val hub = Hub()
        val poster = RecordingPoster()

        check(hub, secrets = MemSecrets(unreadable = true), poster = poster).once()

        assertEquals(0, hub.requests)
        assertEquals(emptyList(), poster.posted)
    }

    @Test
    fun an_unpaired_device_asks_nothing() = realTime {
        val hub = Hub()
        val secrets = MemSecrets(stored = null)

        check(hub, secrets = secrets).once()

        assertEquals(0, hub.requests)
    }

    @Test
    fun the_seen_set_tells_never_stored_from_stored_empty() {
        val prefs = FakePrefs()
        assertNull(prefs.readSeen())
        prefs.writeSeen(emptyMap())
        assertEquals(emptyMap(), prefs.readSeen(), "an empty fleet is a look, not the absence of one")
    }

    @Test
    fun the_notification_content_is_keyed_by_session() {
        val c = needsYouContent(NeedsYouAlert(42, "hub client", "Waiting for you · pine", "asks: deploy?"))
        assertEquals("needs-you-42", c.id)
        assertEquals("needs_you", c.thread)
        assertEquals("hub client", c.title)
        assertEquals("Waiting for you · pine\nasks: deploy?", c.body)
        assertEquals(42L, c.sessionId)
        assertTrue(c.id.startsWith(NEEDS_YOU_ID_PREFIX))
    }

    @Test
    fun content_without_a_detail_is_one_line() {
        assertEquals("Stuck · pine", needsYouContent(NeedsYouAlert(1, "a", "Stuck · pine")).body)
    }
}

private fun row(id: Long, reason: String? = null) =
    SessionRow(id = id, tmuxName = "s$id", friendlyName = "session $id", hostAlias = "pine", attention = reason?.let { Attention(it) })

private class OpenFleet(vararg initial: SessionRow) : FleetState {
    override val sessions = MutableStateFlow(initial.toList())
    override val hosts = MutableStateFlow(listOf(HostRow("pine", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>(null)
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>()
    override suspend fun refresh() = Unit
}

class KeepSeenWhileOpenTest {

    @Test
    fun what_the_open_app_saw_is_not_news_to_the_background_check() = runTest {
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to null)) }
        val poster = RecordingPoster()
        val fleet = OpenFleet(row(1, "waiting"))
        val watching = backgroundScope.launch { keepSeenWhileOpen(fleet, prefs, poster) }
        runCurrent()
        watching.cancel()
        assertEquals(mapOf(1L to "waiting"), prefs.readSeen(), "the open app wrote what the person saw")

        val hub = Hub().apply { reply = { sse(okResult(rows(1L to "waiting"))) to HttpStatusCode.OK } }
        withContext(Dispatchers.Default) { check(hub, prefs = prefs, poster = poster).once() }

        assertEquals(emptyList(), poster.posted, "the person saw session 1 waiting in the app")
    }

    @Test
    fun the_open_app_posts_nothing_and_withdraws_what_resolves() = runTest {
        val prefs = FakePrefs().apply { writeSeen(mapOf(1L to null)) }
        val poster = RecordingPoster()
        val fleet = OpenFleet(row(1, "waiting"))
        backgroundScope.launch { keepSeenWhileOpen(fleet, prefs, poster) }
        runCurrent()

        fleet.sessions.value = listOf(row(1))
        runCurrent()

        assertEquals(emptyList(), poster.posted, "on screen, the list already says it")
        assertEquals(listOf(1L), poster.withdrawn)
        assertEquals(mapOf(1L to null), prefs.readSeen())
    }
}
