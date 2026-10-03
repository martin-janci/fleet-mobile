package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The phone's own client code against a real `fleet-hub`: the same
 * [HubClient], [HubEventStream] and [FleetRepository] the app runs, over
 * OkHttp — the engine Android ships — rather than a mock transport.
 *
 * Every other test in this module pins the wire from one side, with a
 * recorded reply. These are the ones that notice when the two sides stop
 * agreeing: a route that moved, a pairing reply that changed shape, a resume
 * that the hub no longer honours.
 *
 * Opt-in: `FLEET_HUB_BIN` names a built `fleet-hub` binary
 * (`cargo build -p fleet-hub` in claude-fleet). Without it every test returns
 * at once and says so on stdout; with `FLEET_HUB_REQUIRED=1` a missing binary
 * is a failure, so a CI job that means to run them cannot skip them quietly.
 * Each test gets its own hub, data dir and port; nothing outside a temp dir
 * is touched. The hub runs with `--local-host false` and demo rows only, so
 * no tmux session is ever started.
 */
class LiveHubTest {

    private val hubs = mutableListOf<LiveHub>()

    @AfterTest
    fun stopHubs() {
        hubs.forEach { it.close() }
    }

    private fun hub(): LiveHub? {
        val bin = System.getenv("FLEET_HUB_BIN")?.takeIf { it.isNotBlank() }
        if (bin == null) {
            if (System.getenv("FLEET_HUB_REQUIRED") == "1") fail("FLEET_HUB_REQUIRED=1 but FLEET_HUB_BIN is not set")
            println("SKIP LiveHubTest: set FLEET_HUB_BIN to a built fleet-hub to run it")
            return null
        }
        return LiveHub(File(bin)).also { hubs += it }
    }

    private val http = HttpClient(OkHttp).withHubTimeouts()

    /**
     * QR and typed code are one exchange: `POST /pair` with the code. The
     * token it buys reads the fleet over `/mcp/json`, and the code is spent —
     * a second redemption is refused as 404, the answer every bad code gets.
     */
    @Test
    fun a_pairing_code_buys_a_token_once_and_the_token_reads_the_fleet() = runBlocking {
        val hub = hub() ?: return@runBlocking
        val code = hub.pairCode("phone")

        val paired = HubClient(http, hub.base).pair(code)
        assertEquals("phone", paired.name)
        assertEquals("full", paired.mode)
        assertEquals(64, paired.token.length)

        val client = HubClient(http, hub.base, paired.token)
        val sessions = client.listSessions()
        assertEquals(6, sessions.size, "the demo fleet: 2 hosts × 3 sessions")
        assertTrue(sessions.all { it.hostAlias.startsWith("demo-") })
        assertTrue(client.fleetHealth().dbReady)

        // `POST /pair` allows one attempt per address every 6 s (the hub's
        // ATTEMPT_INTERVAL); inside it the answer is 429, not the verdict.
        delay(PAIR_ATTEMPT_INTERVAL_MS)
        val spent = assertFailsWith<HubError.Http> { HubClient(http, hub.base).pair(code) }
        assertEquals(404, spent.status, "a spent code reads like any other bad code")
    }

    /**
     * A readonly client reads everything and writes nothing, and the hub —
     * not the app's own button-hiding — is what enforces it.
     */
    @Test
    fun a_readonly_client_reads_but_the_hub_refuses_its_writes() = runBlocking {
        val hub = hub() ?: return@runBlocking
        val paired = HubClient(http, hub.base).pair(hub.pairCode("kiosk", mode = "readonly"))
        assertEquals("readonly", paired.mode)
        val kiosk = HubClient(http, hub.base, paired.token)

        val row = kiosk.listSessions().first()
        val refused = assertFailsWith<HubError> { kiosk.setTags(row.id, listOf("nope")) }
        assertTrue(refused !is HubError.Unauthorized, "readonly is not a revoked credential: $refused")
        assertTrue("work_link" !in kiosk.toolCatalog().names, "a readonly token is not shown work_link")
        assertEquals(emptyList(), kiosk.listSessions().first { it.id == row.id }.tags, "nothing was written")
    }

    /**
     * The stream carries a change as a row frame with an `id:`; a reconnect
     * that sends it back as `Last-Event-ID` is told `resumed: true` and gets
     * exactly what it missed. An id this hub never minted is `resumed: false`,
     * which is the phone's cue to re-list.
     */
    @Test
    fun the_event_stream_resumes_from_last_event_id_and_says_when_it_cannot() = runBlocking {
        val hub = hub() ?: return@runBlocking
        val token = HubClient(http, hub.base).pair(hub.pairCode("phone")).token
        val client = HubClient(http, hub.base, token)
        val stream = HubEventStream(http, hub.base, token, kinds = listOf("session"))
        val (first, second) = client.listSessions().take(2)

        val seen = withTimeout(STREAM_DEADLINE_MS) {
            stream.connect().first { event ->
                // The first frame is `ready`; the change is made once it is in.
                if (event is HubEvent.Ready) client.setTags(first.id, listOf("one"))
                event is HubEvent.Row && event.name == "session:updated" && event.touches(first.id)
            }
        }
        val lastId = assertNotNull((seen as HubEvent.Row).id, "a row frame carries the id a resume names")

        // While nothing is connected.
        client.setTags(second.id, listOf("two"))

        val resumed = withTimeout(STREAM_DEADLINE_MS) {
            stream.connect(lastId).take(2).toList()
        }
        val ready = assertIs<HubEvent.Ready>(resumed[0])
        assertEquals(true, ready.resumed, "the hub honoured the Last-Event-ID")
        val replayed = assertIs<HubEvent.Row>(resumed[1])
        assertTrue(replayed.touches(second.id), "the missed change is replayed: $replayed")
        assertNotEquals(lastId, replayed.id)

        val foreign = withTimeout(STREAM_DEADLINE_MS) { stream.connect("1-1").first() }
        assertEquals(false, assertIs<HubEvent.Ready>(foreign).resumed, "an id from another generation is refused")
    }

    /**
     * The whole phone path: a repository follows the stream, and a change
     * another client makes reaches its snapshot without a re-list.
     */
    @Test
    fun a_repository_follows_a_change_made_by_another_client() = runBlocking {
        val hub = hub() ?: return@runBlocking
        val phone = HubClient(http, hub.base).pair(hub.pairCode("phone")).token
        val desk = HubClient(http, hub.base, hub.master)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val repository = FleetRepository(
                HubClient(http, hub.base, phone),
                HubEventStream(http, hub.base, phone),
                scope,
            )
            repository.start()
            withTimeout(STREAM_DEADLINE_MS) { repository.status.first { it is ConnectionStatus.Connected } }
            val target = repository.sessions.value.first()

            desk.setTags(target.id, listOf("from-desk"))

            val updated = withTimeout(STREAM_DEADLINE_MS) {
                repository.sessions.first { rows -> rows.any { it.id == target.id && it.tags == listOf("from-desk") } }
            }
            assertEquals(6, updated.size)
            repository.stop()
        } finally {
            scope.cancel()
        }
    }

    /** A revoked token is refused on the next call and on the next stream, as 401 — the app's cue to return to Pair. */
    @Test
    fun a_revoked_client_is_unauthorized_on_calls_and_on_the_stream() = runBlocking {
        val hub = hub() ?: return@runBlocking
        val token = HubClient(http, hub.base).pair(hub.pairCode("phone")).token
        val client = HubClient(http, hub.base, token)
        client.listSessions()

        hub.revoke("phone")

        assertFailsWith<HubError.Unauthorized> { client.listSessions() }
        assertFailsWith<HubError.Unauthorized> {
            withTimeout(STREAM_DEADLINE_MS) { HubEventStream(http, hub.base, token).connect().first() }
        }
    }

    private fun HubEvent.Row.touches(id: Long): Boolean =
        (payload as? kotlinx.serialization.json.JsonObject)?.get("id")?.toString() == id.toString()

    private companion object {
        const val PAIR_ATTEMPT_INTERVAL_MS = 6_500L
        const val STREAM_DEADLINE_MS = 20_000L
    }
}

/** One `fleet-hub serve` in its own temp dir, with the demo fleet seeded. */
private class LiveHub(private val bin: File) : AutoCloseable {
    private val dir: File = Files.createTempDirectory("live-hub").toFile()
    private val data = File(dir, "data")
    private val port: Int = ServerSocket(0).use { it.localPort }
    val base = "http://127.0.0.1:$port"
    val master: String
    private val process: Process

    init {
        val init = run("init", "--public-url", base, "--local-host", "false")
        master = init.lines().map { it.trim() }.firstOrNull { it.matches(Regex("[0-9a-f]{64}")) }
            ?: error("no master token in:\n$init")
        run("demo-seed")
        process = ProcessBuilder(bin.path, "serve", "--data-dir", data.path, "--port", port.toString())
            .redirectErrorStream(true)
            .redirectOutput(File(dir, "serve.log"))
            .start()
        waitHealthy()
    }

    /** `fleet-hub pair`, answered with the code from the URL it prints. */
    fun pairCode(name: String, mode: String = "full"): String {
        val out = run("pair", "--name", name, "--mode", mode)
        return Regex("/pair#([0-9A-Z]{8})").find(out)?.groupValues?.get(1) ?: error("no pairing URL in:\n$out")
    }

    fun revoke(name: String) {
        run("client", "revoke", name)
    }

    private fun run(vararg args: String): String {
        val cmd = listOf(bin.path, args[0]) + args.drop(1) + listOf("--data-dir", data.path, "--port", port.toString())
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor(60, TimeUnit.SECONDS)) { "timed out: $cmd" }
        check(p.exitValue() == 0) { "${cmd.joinToString(" ")} exited ${p.exitValue()}:\n$out" }
        return out
    }

    private fun waitHealthy() {
        repeat(150) {
            if (!process.isAlive) error("fleet-hub serve exited:\n${File(dir, "serve.log").readText()}")
            val ok = runCatching {
                val c = URI("$base/healthz").toURL().openConnection() as HttpURLConnection
                c.connectTimeout = 500
                c.readTimeout = 500
                c.responseCode == 200
            }.getOrDefault(false)
            if (ok) return
            Thread.sleep(100)
        }
        error("fleet-hub never answered /healthz:\n${File(dir, "serve.log").readText()}")
    }

    override fun close() {
        process.destroy()
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly()
        dir.deleteRecursively()
    }
}
