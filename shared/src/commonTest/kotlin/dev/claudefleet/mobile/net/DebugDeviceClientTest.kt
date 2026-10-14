package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.model.DeviceScreenshot
import dev.claudefleet.mobile.model.ToolImage
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `debug_devices { screenshot }` answers an image, which no other tool the app
 * calls does, and `session_tool_detail` now carries a result's images.
 */
class DebugDeviceClientTest {
    private val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")

    /** A client whose every `tools/call` is answered with [content] (MCP content blocks, as JSON). */
    private fun answering(content: String, isError: Boolean = false, seen: MutableList<JsonObject> = mutableListOf()): HubClient {
        val engine = MockEngine { request ->
            seen += Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val body = """{"jsonrpc":"2.0","id":1,"result":{"content":$content${if (isError) ""","isError":true""" else ""}}}"""
            respond("event: message\ndata: $body\n\n", HttpStatusCode.OK, sseHeaders)
        }
        return HubClient(HttpClient(engine), "https://fleet.example.com", "tok-phone")
    }

    private fun text(s: String) = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s))

    @Test
    fun a_screenshot_keeps_the_image_the_payload_would_drop() = runTest {
        val seen = mutableListOf<JsonObject>()
        val hub = answering(
            """[{"type":"text","text":"Pixel 9 on mac: 3 bytes"},{"type":"image","data":"iVBO","mimeType":"image/png"}]""",
            seen = seen,
        )
        val shot = hub.debugDeviceScreenshot(7)
        assertEquals(DeviceScreenshot("Pixel 9 on mac: 3 bytes", "image/png", "iVBO"), shot)
        val params = seen.single()["params"]!!.jsonObject
        assertEquals("debug_devices", params["name"]!!.jsonPrimitive.content)
        val args = params["arguments"]!!.jsonObject
        assertEquals("screenshot", args["action"]!!.jsonPrimitive.content)
        assertEquals("7", args["device"]!!.jsonPrimitive.content)
    }

    @Test
    fun a_screenshot_with_no_image_is_a_hub_that_answered_nonsense() = runTest {
        val hub = answering("""[{"type":"text","text":"Pixel 9 on mac: 0 bytes"}]""")
        assertFailsWith<HubError.Transport> { hub.debugDeviceScreenshot(7) }
    }

    @Test
    fun a_refused_screenshot_is_the_hubs_refusal() = runTest {
        val hub = answering("""[{"type":"text","text":"Pixel 9 is claimed by host:mac#4"}]""", isError = true)
        val e = assertFailsWith<HubError.Tool> { hub.debugDeviceScreenshot(7) }
        assertTrue("claimed" in e.message)
    }

    @Test
    fun other_calls_still_read_the_first_text_block() = runTest {
        // `whole` defaults off: an image beside a JSON text block changes nothing for them.
        val hub = answering("""[{"type":"text","text":${text("""{"state":"booting"}""")}},{"type":"image","data":"iVBO","mimeType":"image/png"}]""")
        assertEquals("booting", hub.bootDebugDevice(3))
    }

    @Test
    fun a_tool_detail_carries_its_images_and_what_was_left_out() = runTest {
        val detail = """{"id":"toolu_6","name":"mcp__claude-fleet__debug_devices","input":"{}","result":"Pixel on mac: 3 bytes",""" +
            """"images":[{"mime":"image/png","data":"iVBO"},{"mime":"image/jpeg","data":"/9j/"}],"images_omitted":2}"""
        val hub = answering("""[{"type":"text","text":${text(detail)}}]""")
        val d = hub.toolDetail(1, "toolu_6")
        assertEquals(listOf(ToolImage("image/png", "iVBO"), ToolImage("image/jpeg", "/9j/")), d.images)
        assertEquals(2, d.imagesOmitted)
    }

    @Test
    fun a_tool_detail_from_an_older_hub_has_no_images() = runTest {
        val hub = answering("""[{"type":"text","text":${text("""{"id":"t","name":"Bash","input":"{}","result":"ok"}""")}}]""")
        val d = hub.toolDetail(1, "t")
        assertEquals(emptyList(), d.images)
        assertEquals(0, d.imagesOmitted)
    }
}
