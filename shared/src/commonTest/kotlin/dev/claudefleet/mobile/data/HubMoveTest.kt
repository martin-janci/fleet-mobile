package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.store.Credentials
import dev.claudefleet.mobile.store.FakePrefs
import dev.claudefleet.mobile.store.Secrets
import dev.claudefleet.mobile.ui.SettingsViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OLD = "http://192.168.1.20:8899"
private const val NEW = "https://hub.example.com"
private const val TOKEN = "tok-move-secret"
private val PAIRED = Credentials(OLD, TOKEN, "phone", "answer")

private class MoveStore(var stored: Credentials? = PAIRED) : Secrets {
    var writes = 0
    override suspend fun read(): Credentials? = stored
    override suspend fun write(credentials: Credentials) {
        writes++
        stored = credentials
    }
    override suspend fun clear() {
        stored = null
    }
}

/** The mock engine answers on another dispatcher: virtual time would time the `ready` wait out at once. */
private fun realTime(block: suspend CoroutineScope.() -> Unit): TestResult =
    runTest { withContext(Dispatchers.Default) { block() } }

/** A hub at [NEW]: accepts [accepts] as its token and names [fleetId] in `ready`. */
private class NewHub(var accepts: String = TOKEN, var fleetId: String? = "fleet-a") {
    val hosts = mutableListOf<String>()
}

private suspend fun session(hub: NewHub, store: MoveStore = MoveStore(), prefs: FakePrefs = FakePrefs()): Triple<AppSession, MoveStore, FakePrefs> {
    val engine = MockEngine { request ->
        hub.hosts += "${request.url.protocol.name}://${request.url.host}"
        val bearer = request.headers[HttpHeaders.Authorization]
        if (bearer != "Bearer ${hub.accepts}") return@MockEngine respond("", HttpStatusCode.Unauthorized)
        if (request.url.encodedPath.endsWith("/events")) {
            val id = hub.fleetId?.let { "\"$it\"" } ?: "null"
            respond(
                "event: ready\ndata: {\"version\":\"1\",\"kinds\":[],\"fleet_id\":$id}\n\n",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        } else {
            respond(
                """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"list_sessions"}]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
    }
    val s = AppSession(store, HttpClient(engine), prefs)
    s.restore()
    return Triple(s, store, prefs)
}

/** Review w18-9: Settings › Hub address re-points a paired phone, checked first. */
class HubMoveTest {

    @Test
    fun the_same_fleet_at_a_new_address_keeps_the_token_name_and_mode() = realTime {
        val hub = NewHub()
        val (s, store, _) = session(hub)
        s.fleetSeen(OLD, "fleet-a")
        val moved = s.moveHub("$NEW/")
        assertEquals(Credentials(NEW, TOKEN, "phone", "answer"), moved)
        assertEquals(moved, store.stored)
        assertEquals(moved, (s.state.value as AuthState.Paired).credentials)
        assertTrue(hub.hosts.all { it == NEW }, "the token went only to the typed address")
    }

    @Test
    fun another_fleet_is_refused_and_nothing_changes() = realTime {
        val (s, store, _) = session(NewHub(fleetId = "fleet-b"))
        s.fleetSeen(OLD, "fleet-a")
        val e = assertFailsWith<HubMoveRefused> { s.moveHub(NEW) }
        assertEquals(HubMoveRefused.ANOTHER_FLEET, e.message)
        assertEquals(PAIRED, store.stored)
        assertEquals(0, store.writes)
    }

    @Test
    fun a_refused_token_is_refused_and_does_not_unpair() = runTest {
        val (s, store, _) = session(NewHub(accepts = "someone-else"))
        val e = assertFailsWith<HubMoveRefused> { s.moveHub(NEW) }
        assertEquals(HubMoveRefused.TOKEN_REFUSED, e.message)
        assertEquals(PAIRED, store.stored)
        assertIs<AuthState.Paired>(s.state.value)
        assertNull(s.unpairReason.value)
    }

    @Test
    fun not_an_address_is_refused_before_anything_is_asked() = runTest {
        val hub = NewHub()
        val (s, store, _) = session(hub)
        for (bad in listOf("hub.example.com", "https://user:pw@hub.example.com", "ftp://hub")) {
            assertEquals(HubMoveRefused.NOT_AN_ADDRESS, assertFailsWith<HubMoveRefused> { s.moveHub(bad) }.message, bad)
        }
        assertEquals(emptyList(), hub.hosts)
        assertEquals(PAIRED, store.stored)
    }

    @Test
    fun with_no_fleet_id_known_the_token_alone_decides_and_the_new_id_is_kept() = realTime {
        val (s, _, prefs) = session(NewHub(fleetId = "fleet-a"))
        s.moveHub(NEW)
        // Moving on again: the id the new address named is now the one to match.
        assertEquals(listOf(NEW, "fleet-a"), prefs.getStringList("hub.fleet_id"))
    }

    @Test
    fun an_id_seen_for_another_address_is_not_kept() = runTest {
        val (s, _, prefs) = session(NewHub())
        s.fleetSeen("https://elsewhere.example", "fleet-z")
        assertEquals(emptyList(), prefs.getStringList("hub.fleet_id"))
    }

    @Test
    fun settings_says_why_a_move_was_refused() = runTest {
        val (s, _, _) = session(NewHub(accepts = "someone-else"))
        val vm = SettingsViewModel(s, backgroundScope, "1.0")
        assertFalse(vm.moveHub(NEW))
        testScheduler.runCurrent()
        assertEquals(HubMoveRefused.TOKEN_REFUSED, vm.state.value.moveError)
        assertFalse(vm.state.value.moving)
        vm.dismissMoveError()
        testScheduler.runCurrent()
        assertNull(vm.state.value.moveError)
    }
}
