package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.model.OrgDirectory
import dev.claudefleet.mobile.ui.BoundPhoneJson
import dev.claudefleet.mobile.ui.friendlyWork
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The client against a hub that answers a phone paired with `--org 1`
 * ([BoundPhoneJson]): what comes back decodes to org 1's rows alone (and the
 * unassigned ones while D31 is on), and an id outside that scope is an
 * `E_NOTFOUND` tool error the app can put into words — never a crash, never
 * a row of another org.
 */
class BoundPhoneClientTest {

    private val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")

    private fun ok(payload: String): String =
        """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":${Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(payload))}}]}}"""

    /** The hub's refusal of an out-of-scope id: the same shape, code and words as for an unknown one. */
    private fun notFound(message: String): String =
        """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"E_NOTFOUND: $message"}],""" +
            """"structuredContent":{"code":"E_NOTFOUND","message":"$message","details":null},"isError":true}}"""

    /** A bound phone's hub; [unassigned] is the org's `bound_sees_unassigned` (D31). */
    private fun boundHub(unassigned: Boolean = false, sent: MutableList<JsonObject> = mutableListOf()): HubClient {
        val engine = MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val args = body["params"]!!.jsonObject["arguments"]!!.jsonObject
            sent += args
            val rpc = when (args["action"]?.jsonPrimitive?.content) {
                "orgs" -> ok(BoundPhoneJson.ORGS)
                "tree" -> ok(if (unassigned) BoundPhoneJson.TREE_WITH_UNASSIGNED else BoundPhoneJson.TREE)
                "task" -> if (args["task_id"]!!.jsonPrimitive.content == "item:12") ok(BoundPhoneJson.TASK) else notFound(BoundPhoneJson.TASK_NOT_FOUND)
                "session_tasks" -> if (args["session_id"]!!.jsonPrimitive.content == "7") ok(BoundPhoneJson.SESSION_TASKS) else notFound(BoundPhoneJson.SESSION_NOT_FOUND)
                "review" -> ok(BoundPhoneJson.REVIEW)
                else -> error("a bound phone's test hub was asked for $args")
            }
            respond("event: message\ndata: $rpc\n\n", HttpStatusCode.OK, sseHeaders)
        }
        return HubClient(HttpClient(engine), "https://fleet.example.com", "tok-phone")
    }

    @Test
    fun the_orgs_a_bound_phone_is_listed_are_its_own_alone() = runTest {
        val orgs = boundHub().workOrgs()

        assertEquals(listOf(BoundPhoneJson.BOUND_ORG), orgs.map { it.id })
        val directory = OrgDirectory.of(orgs)
        assertEquals(setOf(BoundPhoneJson.BOUND_ORG), directory.orgs.keys)
        assertEquals(mapOf(7L to BoundPhoneJson.BOUND_ORG), directory.trackerOrg)
    }

    @Test
    fun a_bound_phones_tree_is_its_orgs_rows_only() = runTest {
        val page = boundHub().workTree()

        assertEquals(listOf("item:12", "item:20"), page.tasks.map { it.taskId })
        assertTrue(page.tasks.all { it.orgId == BoundPhoneJson.BOUND_ORG })
        assertTrue(page.groups.all { it.orgId == BoundPhoneJson.BOUND_ORG })
        assertEquals(listOf(BoundPhoneJson.BOUND_ORG), page.orgs.map { it.id })
        assertTrue(page.trackers.all { it.orgId == BoundPhoneJson.BOUND_ORG })
    }

    /** D31 on: unassigned rows come too — and still no other org. */
    @Test
    fun with_unassigned_on_the_tree_adds_rows_without_an_org_and_no_other_org() = runTest {
        val page = boundHub(unassigned = true).workTree()

        assertEquals(listOf(BoundPhoneJson.BOUND_ORG, null), page.tasks.map { it.orgId })
        assertEquals(listOf(BoundPhoneJson.BOUND_ORG, null), page.groups.map { it.orgId })
        assertEquals(listOf(BoundPhoneJson.BOUND_ORG), page.orgs.map { it.id })
    }

    @Test
    fun task_session_tasks_and_review_decode_what_the_bound_hub_sends() = runTest {
        val hub = boundHub()

        val task = hub.workTask("item:12")
        val links = hub.workSessionTasks(7)
        val review = hub.workReview()

        assertEquals(BoundPhoneJson.BOUND_ORG, task.task.orgId)
        assertTrue(links.links.all { it.task?.orgId == BoundPhoneJson.BOUND_ORG })
        assertEquals(listOf(BoundPhoneJson.BOUND_ORG), review.items.map { it.task.orgId })
    }

    /** Another org's ids answer as unknown ones: a tool error with the hub's code, said in words. */
    @Test
    fun an_out_of_scope_id_is_E_NOTFOUND_and_is_said_in_words() = runTest {
        val sent = mutableListOf<JsonObject>()
        val hub = boundHub(sent = sent)

        val task = assertFailsWith<HubError.Tool> { hub.workTask("item:77") }
        val session = assertFailsWith<HubError.Tool> { hub.workSessionTasks(9) }

        assertEquals("E_NOTFOUND", task.code)
        assertEquals(BoundPhoneJson.TASK_NOT_FOUND, task.message)
        assertEquals("E_NOTFOUND", session.code)
        val words = friendlyWork(session)
        assertEquals("Not found", words.title)
        assertEquals(BoundPhoneJson.SESSION_NOT_FOUND, words.body)
        assertTrue(words.isError)
        assertEquals(
            listOf(
                buildJsonObject { put("action", "task"); put("task_id", "item:77") },
                buildJsonObject { put("action", "session_tasks"); put("session_id", 9) },
            ),
            sent,
            "one read each, nothing retried under a wider scope",
        )
    }
}
