package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.ui.WorkViewJson
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Work view's four reads on the wire: each is `work` with its action
 * named, and each hub answer — in the hub's own shape — decodes.
 */
class WorkViewClientTest {

    private fun clientAnswering(handler: (JsonObject) -> String): HubClient {
        val engine = MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val text = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(handler(body)))
            respond(
                """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":$text}]}}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HubClient(HttpClient(engine), "https://fleet.example.com", "tok-phone")
    }

    private fun JsonObject.tool() = this["params"]!!.jsonObject["name"]!!.jsonPrimitive.content
    private fun JsonObject.args() = this["params"]!!.jsonObject["arguments"]!!.jsonObject

    @Test
    fun tree_sends_its_filters_as_an_object_and_leaves_out_what_is_unset() = runTest {
        val sent = mutableListOf<JsonObject>()
        val client = clientAnswering { body ->
            assertEquals("work", body.tool())
            sent += body.args()
            WorkViewJson.TREE_TWO_ORGS
        }

        val page = client.workTree(JsonObject(emptyMap()))
        client.workTree(buildJsonObject { put("org", 1); put("group", "none") }, cursor = "c-main", limit = 50, perTask = 3)

        assertEquals(7, page.total)
        assertEquals(buildJsonObject { put("action", "tree") }, sent[0], "no filters, no cursor, no limit: the hub's defaults")
        assertEquals(
            buildJsonObject {
                put("action", "tree")
                put("filters", buildJsonObject { put("org", 1); put("group", "none") })
                put("cursor", "c-main")
                put("limit", 50)
                put("per_task", 3)
            },
            sent[1],
        )
    }

    @Test
    fun task_and_views_name_their_actions() = runTest {
        val sent = mutableListOf<JsonObject>()
        val client = clientAnswering { body ->
            sent += body.args()
            when (body.args()["action"]!!.jsonPrimitive.content) {
                "task" -> WorkViewJson.TASK_PAY7
                else -> WorkViewJson.VIEWS
            }
        }

        val detail = client.workTask("item:70")
        assertEquals(listOf("ref:PAY-7"), detail.aliases)
        assertEquals("Half done.", detail.lastOutcome?.summary)
        assertEquals(3, detail.task.sessions.size)
        assertEquals(3, client.workViews().size)
        assertEquals(buildJsonObject { put("action", "task"); put("task_id", "item:70") }, sent[0])
        assertEquals(buildJsonObject { put("action", "views") }, sent[1])
    }

    /** `SessionTaskLink` is serde-flattened on the hub: the link's fields and `task` share one object. */
    @Test
    fun session_tasks_reads_both_halves_of_a_flattened_link() = runTest {
        var args: JsonObject? = null
        val client = clientAnswering { body -> args = body.args(); WorkViewJson.SESSION_TASKS }

        val tasks = client.workSessionTasks(7)

        assertEquals(buildJsonObject { put("action", "session_tasks"); put("session_id", 7) }, args)
        assertEquals(7L, tasks.sessionId)
        assertEquals(42L, tasks.primaryLinkId)
        assertEquals(listOf(42L, 44L, 45L, 40L, 39L), tasks.links.map { it.link.linkId })
        assertEquals(listOf("item:70", "item:90", "ref:OPS-2", "item:50", "item:10"), tasks.links.map { it.task.taskId })
        assertEquals("In Review", tasks.links[0].task.statusName)
        assertEquals("branch pay-7-refund", tasks.links[0].link.why)
        assertNull(tasks.links[3].link.sessionId)
    }
}
