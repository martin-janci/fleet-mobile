package dev.claudefleet.mobile.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val BASE = "https://fleet.example.com"
private const val HELLO_SHA = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"

/** A `tools/call` answer as the hub frames it: the payload inside a text block. */
private fun okResult(payloadJson: String): String =
    """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(payloadJson))}}]}}"""

private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

/** A hub answering every tool call with [payload], recording each request. */
private fun toolHub(payload: String, seen: MutableList<HttpRequestData> = mutableListOf()): HubClient =
    HubClient(
        HttpClient(MockEngine { request -> seen += request; respond(okResult(payload), HttpStatusCode.OK, jsonHeaders) }),
        BASE,
        "tok-phone",
    )

private fun HttpRequestData.call(): JsonObject = Json.parseToJsonElement((body as TextContent).text).jsonObject["params"]!!.jsonObject

private fun JsonObject.tool(): String = this["name"]!!.jsonPrimitive.content
private fun JsonObject.args(): JsonObject = this["arguments"]!!.jsonObject

/** A hub serving `GET /downloads/<id>` with [bytes] and the given headers. */
private fun fileHub(
    bytes: ByteArray,
    status: HttpStatusCode = HttpStatusCode.OK,
    headers: Map<String, String> = emptyMap(),
    seen: MutableList<HttpRequestData> = mutableListOf(),
): HubClient = HubClient(
    HttpClient(
        MockEngine { request ->
            seen += request
            respond(ByteReadChannel(bytes), status, headersOf(*headers.map { (k, v) -> k to listOf(v) }.toTypedArray()))
        },
    ),
    BASE,
    "tok-phone",
)

private val row = """{"id":7,"at":1,"host_alias":"gpu-1","session_id":12,"session_name":"s","path":"/p/r.pdf","name":"r.pdf","size":11,"state":"fetching","source":"person"}"""

/**
 * The three download tools and the binary route (claude-fleet contract
 * revision 7), against a mock hub: what goes out, what comes back, and what a
 * transfer refuses.
 */
class DownloadsClientTest {

    @Test
    fun list_downloads_sends_only_the_filters_given() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val hub = toolHub("""{"downloads":[$row],"total_bytes":11,"max_total_bytes":2048,"max_file_bytes":100}""", seen)

        val all = hub.listDownloads()
        val narrowed = hub.listDownloads(sessionId = 12, limit = 50)

        assertEquals(listOf(7L), all.downloads.map { it.id })
        assertEquals(11, all.totalBytes)
        assertEquals("list_downloads", seen[0].call().tool())
        assertEquals(JsonObject(emptyMap()), seen[0].call().args())
        assertEquals(12, seen[1].call().args()["session_id"]!!.jsonPrimitive.long)
        assertEquals(50, seen[1].call().args()["limit"]!!.jsonPrimitive.long)
        assertEquals(1, narrowed.downloads.size)
    }

    @Test
    fun send_file_names_the_session_and_path_and_leaves_a_blank_note_out() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val hub = toolHub(row, seen)

        val made = hub.sendFile(12, "out/r.pdf", note = "the Q3 report")
        hub.sendFile(12, "/abs/r.pdf", note = "  ")

        assertEquals(7, made.id)
        assertTrue(made.isFetching)
        val first = seen[0].call()
        assertEquals("send_file", first.tool())
        assertEquals(12, first.args()["session_id"]!!.jsonPrimitive.long)
        assertEquals("out/r.pdf", first.args()["path"]!!.jsonPrimitive.content)
        assertEquals("the Q3 report", first.args()["note"]!!.jsonPrimitive.content)
        assertNull(seen[1].call().args()["note"], "a blank note is not a note")
    }

    @Test
    fun remove_download_answers_whether_it_was_there() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        assertTrue(toolHub("""{"removed":true}""", seen).removeDownload(7))
        assertFalse(toolHub("""{"removed":false}""").removeDownload(7))
        assertEquals("remove_download", seen[0].call().tool())
        assertEquals(7, seen[0].call().args()["id"]!!.jsonPrimitive.long)
    }

    @Test
    fun a_download_is_a_bearer_GET_on_the_same_base_and_its_bytes_are_checked() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val hub = fileHub(
            "hello world".encodeToByteArray(),
            headers = mapOf(HttpHeaders.ContentLength to "11", SHA256_HEADER to HELLO_SHA.uppercase()),
            seen = seen,
        )
        val sink = Buffer()
        val progress = mutableListOf<Pair<Long, Long?>>()

        val fetched = hub.downloadFile(7, sink) { received, total -> progress += received to total }

        assertEquals(FetchedFile(11, HELLO_SHA), fetched)
        assertContentEquals("hello world".encodeToByteArray(), sink.readByteArray())
        val request = seen.single()
        assertEquals(HttpMethod.Get, request.method)
        assertEquals("$BASE/downloads/7", request.url.toString())
        assertEquals("Bearer tok-phone", request.headers[HttpHeaders.Authorization])
        assertEquals(11L to 11L, progress.last())
    }

    /**
     * A file is not a tool reply: the 8 MiB ceiling on what is read into a
     * string must not apply, or every file past it would fail.
     */
    @Test
    fun a_file_larger_than_the_rpc_reply_ceiling_streams_through() = runTest {
        val big = ByteArray(MAX_RESPONSE_BYTES + 1_000_000) { (it % 251).toByte() }
        val sink = Buffer()

        val fetched = fileHub(big, headers = mapOf(HttpHeaders.ContentLength to big.size.toString())).downloadFile(7, sink)

        assertEquals(big.size.toLong(), fetched.bytes)
        assertEquals(big.size.toLong(), sink.size)
        assertEquals(Sha256().apply { update(big) }.hex(), fetched.sha256)
    }

    @Test
    fun a_404_says_the_file_is_no_longer_available() = runTest {
        val e = assertFailsWith<HubError.Tool> { fileHub(ByteArray(0), HttpStatusCode.NotFound).downloadFile(7, Buffer()) }

        assertEquals(DOWNLOAD_GONE, e.code)
        assertTrue("no longer" in e.message, e.message)
    }

    @Test
    fun a_401_is_the_signed_out_error_like_every_other_call() = runTest {
        assertFailsWith<HubError.Unauthorized> { fileHub(ByteArray(0), HttpStatusCode.Unauthorized).downloadFile(7, Buffer()) }
    }

    @Test
    fun a_digest_that_is_not_the_hubs_is_refused() = runTest {
        val hub = fileHub("hello world".encodeToByteArray(), headers = mapOf(SHA256_HEADER to "0".repeat(64)))
        assertFailsWith<HubError.Damaged> { hub.downloadFile(7, Buffer()) }
    }

    /** Without a `Content-Length`, the row's own `size` is the bound, both ways. */
    @Test
    fun a_body_shorter_or_longer_than_announced_is_refused() = runTest {
        val bytes = "hello world".encodeToByteArray()
        assertFailsWith<HubError.Damaged> { fileHub(bytes).downloadFile(7, Buffer(), expectedSize = 12) }
        assertFailsWith<HubError.Damaged> { fileHub(bytes).downloadFile(7, Buffer(), expectedSize = 10) }
        assertEquals(11, fileHub(bytes).downloadFile(7, Buffer(), expectedSize = 11).bytes)
    }
}
