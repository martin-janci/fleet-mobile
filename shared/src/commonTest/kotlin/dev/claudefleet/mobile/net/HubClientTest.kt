package dev.claudefleet.mobile.net

import dev.claudefleet.mobile.model.ConvItem
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import dev.claudefleet.mobile.ui.HubWorkJson
import dev.claudefleet.mobile.ui.explain
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hub answers `POST /mcp` SSE-framed, so every reply in these tests is
 * wrapped the way `rmcp`'s streamable-HTTP transport wraps it: an `event:`
 * line, one `data:` line carrying the JSON-RPC body, and a blank line.
 */
private fun sse(body: String): String = "event: message\ndata: $body\n\n"

private val sseHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")
private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

/** A successful `tools/call` reply: the payload rides as a JSON text block. */
private fun okResult(payloadJson: String): String {
    val text = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(payloadJson))
    return """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":$text}]}}"""
}

private const val BASE = "https://fleet.example.com"

/** Records every request the client made, so headers and bodies can be asserted. */
private class Calls {
    val requests = mutableListOf<HttpRequestData>()
    fun path(i: Int) = requests[i].url.encodedPath
    fun bodyText(i: Int) = (requests[i].body as TextContent).text
}

private fun client(
    token: String? = "tok-phone",
    calls: Calls = Calls(),
    handler: (HttpRequestData) -> Pair<String, HttpStatusCode>,
): Pair<HubClient, Calls> {
    val engine = MockEngine { request ->
        calls.requests += request
        val (body, status) = handler(request)
        val headers = if (body.startsWith("event:")) sseHeaders else jsonHeaders
        respond(body, status, headers)
    }
    return HubClient(HttpClient(engine), BASE, token) to calls
}

/**
 * A tool-call client for tests that only care about one request's tool name
 * and arguments, and answer one payload back. [handler] returns the payload
 * exactly as the tool would put it in its text block — a JSON document for a
 * structured result, or plain prose for a tool like `capture_session` that
 * answers text — and [okResult] does the SSE/JSON-RPC/text-block wrapping.
 */
private fun clientAnswering(handler: (JsonObject) -> String): HubClient {
    val engine = MockEngine { request ->
        val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
        respond(sse(okResult(handler(body))), HttpStatusCode.OK, sseHeaders)
    }
    return HubClient(HttpClient(engine), BASE, "tok-phone")
}

/** The tool name off a parsed `tools/call` request body. */
private fun JsonObject.tool(): String = this["params"]!!.jsonObject["name"]!!.jsonPrimitive.content

/** The tool arguments off a parsed `tools/call` request body. */
private fun JsonObject.args(): JsonObject = this["params"]!!.jsonObject["arguments"]!!.jsonObject

class HubClientTest {


    /**
     * `"isError": false` is a success, and a spec-compliant server sends it on
     * every call.
     *
     * The MCP result carries `isError` as a *flag*, so the shape that says
     * "this went fine" is `false`, not absent. Testing only the absent case and
     * the `true` case leaves the most common successful reply in the world
     * untested — and reading "the key is present" as "the call failed" turns
     * every success into a `HubError.Tool` with no code, on every screen at
     * once.
     *
     * Found by mutation: no existing test sent the flag set to false.
     */
    @Test
    fun a_result_that_says_isError_false_is_a_success() = runTest {
        val rpc = """{"jsonrpc":"2.0","id":1,"result":{""" +
            """"content":[{"type":"text","text":"{\"n\":7}"}],"isError":false}}"""
        val (hub, _) = client { sse(rpc) to HttpStatusCode.OK }

        val n = hub.call("fleet_health", JsonObject(emptyMap())) {
            it.jsonObject["n"]!!.jsonPrimitive.content.toInt()
        }

        assertEquals(7, n, "isError:false is the ordinary successful reply, not a failure")
    }

    /**
     * And a flag that is not a boolean is not a failure either.
     *
     * `asBooleanOrNull` is strict on purpose: only the literal `true` means
     * the call failed. A string, a number or a null in that slot is a reply
     * this app does not understand, and the safe reading of "I do not
     * understand this flag" is not "everything failed".
     */
    @Test
    fun a_non_boolean_isError_is_not_read_as_a_failure() = runTest {
        // `"TRUE"` earns its place: it is the one spelling a lenient parse
        // would accept. A JSON boolean's content is always lowercase `true`,
        // so a capitalised one is a *string*, and a string is not the flag.
        for (flag in listOf(""""maybe"""", """"TRUE"""", """"True"""", "0", "null", """{"v":true}""")) {
            val rpc = """{"jsonrpc":"2.0","id":1,"result":{""" +
                """"content":[{"type":"text","text":"{\"n\":7}"}],"isError":$flag}}"""
            val (hub, _) = client { sse(rpc) to HttpStatusCode.OK }

            val n = hub.call("fleet_health", JsonObject(emptyMap())) {
                it.jsonObject["n"]!!.jsonPrimitive.content.toInt()
            }
            assertEquals(7, n, "isError:$flag is not the literal true")
        }
    }

    /**
     * The payload is read from the **text** block, not the first block.
     *
     * An MCP content array may hold images and embedded resources beside text.
     * Taking whichever block comes first reads an image's fields as the tool's
     * JSON answer, which fails somewhere far from here with a message about
     * the wrong thing entirely.
     */
    @Test
    fun the_answer_is_read_from_the_text_block_even_when_it_is_not_first() = runTest {
        val rpc = """{"jsonrpc":"2.0","id":1,"result":{"content":[""" +
            """{"type":"image","data":"iVBOR","mimeType":"image/png"},""" +
            """{"type":"text","text":"{\"n\":42}"}]}}"""
        val (hub, _) = client { sse(rpc) to HttpStatusCode.OK }

        val n = hub.call("fleet_health", JsonObject(emptyMap())) {
            it.jsonObject["n"]!!.jsonPrimitive.content.toInt()
        }

        assertEquals(42, n, "the text block is the one carrying the tool's answer")
    }

    @Test
    fun a_tool_result_on_an_sse_data_line_is_parsed() = runTest {
        val (hub, calls) = client { sse(okResult("""{"ok":true,"n":3}""")) to HttpStatusCode.OK }

        val n = hub.call("fleet_health", JsonObject(emptyMap())) {
            it.jsonObject["n"]!!.jsonPrimitive.content.toInt()
        }

        assertEquals(3, n)
        // The mount is whatever an ordinary call uses; the point here is that
        // an SSE-framed *answer* parses on either of them — a proxy or a hub
        // may still frame a reply the client asked for unframed.
        assertEquals("/mcp/json", calls.path(0))
        val sent = Json.parseToJsonElement(calls.bodyText(0)).jsonObject
        assertEquals("2.0", sent["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals("tools/call", sent["method"]!!.jsonPrimitive.content)
        assertEquals("fleet_health", sent["params"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun a_result_flagged_isError_becomes_a_tool_error_with_its_code() = runTest {
        val rpc = """
            {"jsonrpc":"2.0","id":1,"result":{
              "content":[{"type":"text","text":"E_NO_TRANSCRIPT: nothing written yet"}],
              "structuredContent":{"code":"E_NO_TRANSCRIPT","message":"nothing written yet","details":null},
              "isError":true}}
        """.trimIndent().replace("\n", "")

        val (hub, _) = client { sse(rpc) to HttpStatusCode.OK }

        val e = assertFailsWith<HubError.Tool> {
            hub.call("session_conversation", buildJsonObject { put("session_id", 1) }) { it }
        }
        assertEquals("E_NO_TRANSCRIPT", e.code)
        assertEquals("nothing written yet", e.message)
    }

    @Test
    fun a_jsonrpc_error_object_becomes_a_tool_error_too() = runTest {
        val rpc = """{"jsonrpc":"2.0","id":1,"error":{"code":-32603,""" +
            """"message":"E_FORBIDDEN: a client token may not call add_host",""" +
            """"data":{"code":"E_FORBIDDEN","details":null}}}"""

        val (hub, _) = client { sse(rpc) to HttpStatusCode.OK }

        val e = assertFailsWith<HubError.Tool> { hub.call("add_host", JsonObject(emptyMap())) { it } }
        assertEquals("E_FORBIDDEN", e.code)
        assertTrue(e.message.contains("may not call add_host"), e.message)
    }

    @Test
    fun a_401_becomes_unauthorized() = runTest {
        val (hub, _) = client { "" to HttpStatusCode.Unauthorized }

        assertFailsWith<HubError.Unauthorized> { hub.listSessions() }
    }

    /**
     * What fleet actually sends: `authorize` refuses with a bare
     * `StatusCode::FORBIDDEN` (`mcp/mod.rs:208-212`), which axum renders with an
     * **empty body**. This test used to assert "Host header not allowed", a
     * string no fleet build emits; only rmcp's own later Host check carries
     * text, and it runs after fleet's layer has already passed.
     */
    @Test
    fun a_403_becomes_forbidden_with_the_empty_body_the_hub_really_sends() = runTest {
        val (hub, _) = client { "" to HttpStatusCode.Forbidden }

        val e = assertFailsWith<HubError.Forbidden> { hub.listSessions() }
        assertEquals("", e.body)
        // The explanation must survive the body being empty.
        assertTrue(e.message.orEmpty().contains(BASE))
    }

    /**
     * The message names the failure's *type*, never its text — see
     * `HubError.Transport` and `TokenNeverLeaksTest`. A failure that does not
     * quote its input still keeps its cause, so a stack trace is not lost for
     * the ordinary network case.
     */
    @Test
    fun a_connection_failure_becomes_transport() = runTest {
        val engine = MockEngine { throw RuntimeException("connection refused") }
        val hub = HubClient(HttpClient(engine), BASE, "tok-phone")

        val e = assertFailsWith<HubError.Transport> { hub.listSessions() }

        assertEquals("RuntimeException", e.kind)
        assertTrue(e.message.orEmpty().contains("RuntimeException"))
        assertEquals("connection refused", e.cause?.message)
    }

    @Test
    fun any_other_http_status_becomes_an_http_error() = runTest {
        val (hub, _) = client { "bad gateway" to HttpStatusCode.BadGateway }

        val e = assertFailsWith<HubError.Http> { hub.listSessions() }
        assertEquals(502, e.status)
        assertEquals("bad gateway", e.body)
    }

    @Test
    fun pair_posts_the_code_and_reads_back_the_token_and_the_hub_url() = runTest {
        val (hub, calls) = client(token = null) {
            """{"token":"tok-new","name":"phone","mode":"full","hub":"https://fleet.example.com"}""" to
                HttpStatusCode.OK
        }

        val paired = hub.pair("ABCD1234")

        assertEquals("tok-new", paired.token)
        assertEquals("phone", paired.name)
        assertEquals("full", paired.mode)
        assertEquals("https://fleet.example.com", paired.hub)
        assertEquals("/pair", calls.path(0))
        assertEquals(
            "ABCD1234",
            Json.parseToJsonElement(calls.bodyText(0)).jsonObject["code"]!!.jsonPrimitive.content,
        )
    }

    /**
     * **A pairing code is a credential, and it must not survive into an error a
     * person can read.**
     *
     * On `/pair` there is no token yet — `AppSession.pair` builds the client
     * with `token = null` — so `redacted` had nothing to scrub and only capped.
     * The code was in scope the whole time and simply never passed. A reverse
     * proxy or WAF that answers `POST /pair` with an error page echoing the
     * request body therefore put a live pairing code verbatim into
     * `HubError.Http.body`, through `explain()`, onto the banner a person reads
     * and screenshots. A live code mints a token.
     *
     * That undoes the entire reason the code travels in the URL *fragment*,
     * which no browser puts on the wire and no access log ever sees.
     */
    @Test
    fun a_proxy_that_echoes_the_pairing_code_does_not_put_it_on_the_screen() = runTest {
        val code = "ABCD1234"
        val (hub, _) = client(token = null) {
            """<html><body>400 Bad Request<pre>{"code":"$code"}</pre></body></html>""" to
                HttpStatusCode.BadRequest
        }

        val e = assertFailsWith<HubError.Http> { hub.pair(code) }

        assertFalse(code in e.body, "the pairing code reached the error body: ${e.body}")
        assertFalse(code in explain(e), "the pairing code reached the banner: ${explain(e)}")
        assertFalse(code in e.toString(), e.toString())
        assertFalse(code in e.stackTraceToString(), "the code reached a stack trace")
    }

    /** The same on the 403 path, which takes a different branch of `throwForStatus`. */
    @Test
    fun a_403_echoing_the_pairing_code_does_not_carry_it_either() = runTest {
        val code = "WXYZ9999"
        val (hub, _) = client(token = null) {
            "forbidden: code=$code" to HttpStatusCode.Forbidden
        }

        val e = assertFailsWith<HubError.Forbidden> { hub.pair(code) }

        assertFalse(code in e.body, "the pairing code reached the 403 body: ${e.body}")
        assertFalse(code in explain(e), explain(e))
    }

    @Test
    fun the_bearer_token_is_sent_to_mcp_and_never_to_pair() = runTest {
        val calls = Calls()
        val (hub, _) = client(token = "tok-phone", calls = calls) { request ->
            if (request.url.encodedPath == "/pair") {
                """{"token":"t","name":"phone","mode":"full","hub":"$BASE"}""" to HttpStatusCode.OK
            } else {
                sse(okResult("[]")) to HttpStatusCode.OK
            }
        }

        hub.listSessions()
        hub.pair("ABCD1234")

        assertEquals("Bearer tok-phone", calls.requests[0].headers[HttpHeaders.Authorization])
        assertTrue(
            calls.requests[0].headers[HttpHeaders.Accept].orEmpty().contains("text/event-stream"),
            "the hub's streamable-HTTP transport requires the Accept pair",
        )
        assertNull(
            calls.requests[1].headers[HttpHeaders.Authorization],
            "/pair is how a client gets its FIRST credential — it must not present one",
        )
    }

    @Test
    fun a_client_without_a_token_sends_no_authorization_header() = runTest {
        val (hub, calls) = client(token = null) { sse(okResult("[]")) to HttpStatusCode.OK }

        hub.listSessions()

        assertNull(calls.requests[0].headers[HttpHeaders.Authorization])
    }

    @Test
    fun list_sessions_asks_for_full_rows_and_parses_them() = runTest {
        // Exactly the shape `ok_json_compact` emits: compact, nulls stripped,
        // `is_controller` flattened onto the row, plus usage fields the app
        // does not model (which must not break the parse).
        val row = """[{"is_controller":false,"id":12,"tmux_name":"fleet-abc",""" +
            """"host_alias":"pine","project_id":3,"created_at":1758100000,""" +
            """"last_activity_at":1758200000,"status":"running","kind":"work",""" +
            """"claude_status":"blocked","stuck_kind":"press_enter",""" +
            """"current_activity":"waiting on a prompt","friendly_name":"hub client",""" +
            """"turn_seq":7,"tags":["mobile"],"usage_input_tokens":10,"usage_cost_micros":42}]"""

        val (hub, calls) = client { sse(okResult(row)) to HttpStatusCode.OK }

        val sessions = hub.listSessions()

        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertEquals(12L, s.id)
        assertEquals("fleet-abc", s.tmuxName)
        assertEquals("hub client", s.friendlyName)
        assertEquals("pine", s.hostAlias)
        assertEquals(3L, s.projectId)
        assertEquals("blocked", s.claudeStatus)
        assertEquals("press_enter", s.stuckKind)
        assertEquals("waiting on a prompt", s.currentActivity)
        assertEquals(1758200000L, s.lastActivityAt)
        assertEquals(listOf("mobile"), s.tags)

        val args = Json.parseToJsonElement(calls.bodyText(0))
            .jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals(
            "false",
            args["summary"]!!.jsonPrimitive.content,
            "summary=true drops friendly_name, current_activity and last_activity_at",
        )
        // The hub's named projection for this app. A hub that predates it
        // ignores the key, and `summary=false` above still gets full rows.
        assertEquals("phone", args["view"]!!.jsonPrimitive.content)
    }

    @Test
    fun list_hosts_parses_the_host_rows() = runTest {
        val rows = """[{"alias":"pine","ssh_alias":"fleet-pine","reachable":true,""" +
            """"claude_version":"2.1.0","tmux_version":"3.4","hidden":false,""" +
            """"last_pinged_at":1758200000,"account_uuid":null,"provisioned":true,"transport":"ssh"}]"""

        val (hub, _) = client { sse(okResult(rows)) to HttpStatusCode.OK }

        val hosts = hub.listHosts()

        assertEquals(1, hosts.size)
        assertEquals("pine", hosts[0].alias)
        assertTrue(hosts[0].reachable)
        assertEquals("2.1.0", hosts[0].claudeVersion)
        assertEquals("ssh", hosts[0].transport)
        assertNull(hosts[0].accountUuid)
    }

    /**
     * `list_projects` answers the slim summary by default, which is all the app
     * needs: a session row names its project by id, and this is what turns that
     * id into `owner/repo`. `worktree_count` is on the wire and deliberately not
     * modelled — `project:updated` carries the store row, which has no such
     * field, so modelling it would let a frame blank what the list had filled in.
     */
    @Test
    fun list_projects_parses_the_summary_rows() = runTest {
        val rows = """[{"id":3,"owner":"martin-janci","repo":"claude-fleet",""" +
            """"worktree_count":4,"last_session_at":1758200000}]"""

        val (hub, _) = client { sse(okResult(rows)) to HttpStatusCode.OK }

        val projects = hub.listProjects()

        assertEquals(1, projects.size)
        assertEquals(3L, projects[0].id)
        assertEquals("martin-janci/claude-fleet", projects[0].label)
        assertEquals(1758200000L, projects[0].lastSessionAt)
    }

    @Test
    fun a_conversation_parses_its_kind_tagged_items() = runTest {
        val conv = """{"turns":[{"prompt":"run the tests","at":"2026-09-18T10:00:00Z",""" +
            """"ended_at":"2026-09-18T10:00:09Z","items":[{"kind":"text","text":"On it."},""" +
            """{"kind":"tool","summary":"Bash(./gradlew test)","error":true}]},""" +
            """{"prompt":null,"at":null,"ended_at":null,"items":[]}],"truncated":true}"""

        val (hub, calls) = client { sse(okResult(conv)) to HttpStatusCode.OK }

        val result = hub.conversation(sessionId = 12, turns = 5)

        assertTrue(result.truncated)
        assertEquals(2, result.turns.size)
        assertEquals("run the tests", result.turns[0].prompt)
        assertEquals("2026-09-18T10:00:09Z", result.turns[0].endedAt)
        assertEquals(ConvItem.Text("On it."), result.turns[0].items[0])
        assertEquals(ConvItem.Tool("Bash(./gradlew test)", error = true), result.turns[0].items[1])
        assertNull(result.turns[1].prompt)

        val args = Json.parseToJsonElement(calls.bodyText(0))
            .jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals("12", args["session_id"]!!.jsonPrimitive.content)
        assertEquals("5", args["turns"]!!.jsonPrimitive.content)
    }

    @Test
    fun conversation_omits_turns_when_the_caller_does_not_choose_one() = runTest {
        val (hub, calls) = client { sse(okResult("""{"turns":[],"truncated":false}""")) to HttpStatusCode.OK }

        hub.conversation(sessionId = 12)

        val args = Json.parseToJsonElement(calls.bodyText(0))
            .jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject
        assertNull(args["turns"], "the hub's own default (10) must stand")
    }

    /**
     * The reply carries the conversation's timeline, and [Conversation] has
     * nowhere to put it: `turns` and `truncated`, and nothing else. Asking
     * for none is the difference between parsing it and dropping it on every
     * poll, and not receiving it at all.
     */
    @Test
    fun conversation_asks_for_no_timeline_events() = runTest {
        val (hub, calls) = client { sse(okResult("""{"turns":[],"truncated":false}""")) to HttpStatusCode.OK }

        hub.conversation(sessionId = 12)

        val args = Json.parseToJsonElement(calls.bodyText(0))
            .jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals("0", args["events_limit"]!!.jsonPrimitive.content)
    }

    /**
     * A caller that knows where it got to says so, and the hub sends the
     * turns completed since plus the one still running — not the last ten
     * every time. Omitted when there is nothing to resume from, so the hub's
     * default window stands.
     */
    @Test
    fun conversation_passes_a_cursor_only_when_it_has_one() = runTest {
        val (hub, calls) = client { sse(okResult("""{"turns":[],"truncated":false}""")) to HttpStatusCode.OK }

        hub.conversation(sessionId = 12, sinceTurn = 20)
        hub.conversation(sessionId = 12)

        val args = { i: Int ->
            Json.parseToJsonElement(calls.bodyText(i))
                .jsonObject["params"]!!.jsonObject["arguments"]!!.jsonObject
        }
        assertEquals("20", args(0)["since_turn"]!!.jsonPrimitive.content)
        assertNull(args(1)["since_turn"], "no cursor, no parameter")
    }

    @Test
    fun send_prompt_posts_the_text_and_parses_the_receipt() = runTest {
        val (hub, calls) = client {
            sse(okResult("""{"delivered":true,"session_id":12,"turn_seq_before":7}""")) to HttpStatusCode.OK
        }

        val receipt = hub.sendPrompt(sessionId = 12, text = "carry on")

        assertTrue(receipt.delivered)
        assertEquals(12L, receipt.sessionId)
        assertEquals(7L, receipt.turnSeqBefore)

        val params = Json.parseToJsonElement(calls.bodyText(0)).jsonObject["params"]!!.jsonObject
        assertEquals("send_prompt", params["name"]!!.jsonPrimitive.content)
        val args = params["arguments"]!!.jsonObject
        assertEquals("12", args["session_id"]!!.jsonPrimitive.content)
        assertEquals("carry on", args["prompt"]!!.jsonPrimitive.content)
    }

    @Test
    fun a_plain_json_reply_without_sse_framing_is_parsed_too() = runTest {
        // `json_response` is a server switch; the client must not care.
        val (hub, _) = client { okResult("[]") to HttpStatusCode.OK }

        assertEquals(emptyList(), hub.listSessions())
    }

    /**
     * An ordinary call goes to the mount whose answer a proxy will compress.
     * `/mcp` answers `text/event-stream`, which Caddy and Cloudflare both skip
     * by design, so on that mount nothing this app fetches is ever compressed.
     */
    @Test
    fun an_ordinary_call_uses_the_json_mount() = runTest {
        val calls = Calls()
        val (hub, _) = client(calls = calls) { okResult("[]") to HttpStatusCode.OK }

        hub.listSessions()

        assertEquals("/mcp/json", calls.path(0))
    }

    /**
     * A hub that predates the JSON mount answers 404. The call still succeeds,
     * and the client stops asking: the 404 is a property of the hub, not of
     * this one call.
     */
    @Test
    fun a_hub_without_the_json_mount_falls_back_once_and_stays_there() = runTest {
        val calls = Calls()
        val (hub, _) = client(calls = calls) { request ->
            if (request.url.encodedPath == "/mcp/json") {
                "no such route" to HttpStatusCode.NotFound
            } else {
                okResult("[]") to HttpStatusCode.OK
            }
        }

        assertEquals(emptyList(), hub.listSessions())
        assertEquals(listOf("/mcp/json", "/mcp"), listOf(calls.path(0), calls.path(1)))

        // The second call does not re-probe.
        assertEquals(emptyList(), hub.listSessions())
        assertEquals(3, calls.requests.size)
        assertEquals("/mcp", calls.path(2))
    }

    /**
     * A long poll keeps the SSE mount even on a hub that has both: the hub
     * holds the request open for minutes, and the 15 s keep-alive comment is
     * the only thing stopping a proxy or a phone's NAT from dropping it.
     */
    @Test
    fun a_long_poll_tool_stays_on_the_framed_mount() = runTest {
        val calls = Calls()
        val (hub, _) = client(calls = calls) { sse(okResult("{}")) to HttpStatusCode.OK }

        hub.call("wait_for_session") { it }

        assertEquals("/mcp", calls.path(0))
    }

    @Test
    fun sse_keep_alive_comments_between_frames_are_ignored() = runTest {
        // Long polls (`wait_for_session`, `run_prompt`) keep the connection
        // warm with SSE comment lines before the real frame arrives.
        val framed = ": keep-alive\n\n: keep-alive\n\n" + sse(okResult("[]"))
        val (hub, _) = client { framed to HttpStatusCode.OK }

        assertEquals(emptyList(), hub.listSessions())
    }

    @Test
    fun a_reply_that_is_neither_a_result_nor_an_error_is_a_transport_failure() = runTest {
        val (hub, _) = client { sse("""{"jsonrpc":"2.0","id":1}""") to HttpStatusCode.OK }

        assertFailsWith<HubError.Transport> { hub.listSessions() }
    }

    @Test
    fun a_pair_code_the_hub_refuses_surfaces_as_an_http_error() = runTest {
        val (hub, _) = client(token = null) { """{"error":"invalid code"}""" to HttpStatusCode.NotFound }

        val e = assertFailsWith<HubError.Http> { hub.pair("ZZZZZZZZ") }
        assertEquals(404, e.status)
        assertTrue(e.body.contains("invalid code"))
    }

    /**
     * `HubClient`'s calls are also `/mcp` POSTs the hub keeps warm with the
     * same SSE keep-alive `sse_keep_alive_comments_between_frames_are_ignored`
     * above relies on, so they need a deadline comfortably above the hub's
     * 15 s interval too — but, unlike `/events`, a finite one: nothing here
     * polls forever, and a hub that has genuinely gone away should surface as
     * an error rather than hang. `withHubTimeouts()` is the one shared place
     * both `AppContainer` and this test apply it from.
     */
    @Test
    fun an_ordinary_call_keeps_a_finite_deadline_above_the_keep_alive_interval() = runTest {
        val calls = Calls()
        val engine = MockEngine { request ->
            calls.requests += request
            respond(sse(okResult("[]")), HttpStatusCode.OK, sseHeaders)
        }
        val hub = HubClient(HttpClient(engine).withHubTimeouts(), BASE, "tok-phone")

        hub.listSessions()

        val timeout = calls.requests.single().getCapabilityOrNull(HttpTimeoutCapability)
        assertEquals(HUB_CALL_TIMEOUT_MS, timeout?.requestTimeoutMillis)
        assertEquals(HUB_CALL_TIMEOUT_MS, timeout?.socketTimeoutMillis)
        assertEquals(HUB_CONNECT_TIMEOUT_MS, timeout?.connectTimeoutMillis)
        assertTrue(HUB_CALL_TIMEOUT_MS > 15_000L, "shorter than the hub's own keep-alive would defeat the point")
    }

    @Test
    fun keys_are_sent_as_the_keys_argument_with_an_empty_prompt() = runTest {
        val client = clientAnswering { body ->
            assertEquals("send_prompt", body.tool())
            assertEquals("Escape", body.args()["keys"]?.jsonPrimitive?.content)
            assertEquals("", body.args()["prompt"]?.jsonPrimitive?.content)
            """{"delivered":true,"session_id":7,"turn_seq_before":3}"""
        }
        assertEquals(3L, client.sendKeys(7, "Escape").turnSeqBefore)
    }

    /**
     * `capture_session` answers plain text, not JSON — the pane's own text
     * riding in the tool's text content block, not a JSON string inside it.
     * `payloadOf` hands that text over as a `JsonPrimitive` fallback (its
     * `parseWire` attempt fails because pane text is not valid JSON), so the
     * assertion is that the pane text survives byte for byte.
     */
    @Test
    fun capture_returns_the_pane_text_verbatim() = runTest {
        val client = clientAnswering { body ->
            assertEquals("capture_session", body.tool())
            assertEquals(40, body.args()["max_lines"]?.jsonPrimitive?.int)
            "❯ 1. Yes\n  2. No"
        }
        assertEquals("❯ 1. Yes\n  2. No", client.capture(7))
    }

    @Test
    fun wait_for_turn_passes_the_turn_and_a_timeout() = runTest {
        val client = clientAnswering { body ->
            assertEquals("wait_for_session", body.tool())
            assertEquals("turn_gt", body.args()["until"]?.jsonPrimitive?.content)
            assertEquals(3, body.args()["turn"]?.jsonPrimitive?.int)
            assertEquals(30, body.args()["timeout_s"]?.jsonPrimitive?.int)
            """{"status":"satisfied","claude_status":"idle","turn_seq":4}"""
        }
        assertEquals("satisfied", client.waitForTurn(7, 3).status)
    }

    @Test
    fun lifecycle_and_metadata_calls_name_their_tools() = runTest {
        for ((call, tool, argKey) in listOf<Triple<suspend (HubClient) -> Unit, String, String>>(
            Triple({ it.restart(7) }, "restart_session", "session_id"),
            Triple({ it.safeKill(7) }, "safe_kill_session", "session_id"),
            Triple({ it.kill(7) }, "kill_session", "session_id"),
            Triple({ it.setTags(7, listOf("wip")) }, "set_session_tags", "tags"),
            Triple({ it.rename(7, "ADR") }, "set_friendly_name", "friendly_name"),
        )) {
            val client = clientAnswering { body -> assertEquals(tool, body.tool()); assertTrue(argKey in body.args()); "7" }
            call(client)
        }
    }

    /**
     * `name` is sent, and sent empty. The hub's `NewSessionParams.name` is a
     * required string, and an empty one is the documented way to have the hub
     * mint it (`fill_session_name`: `dev-<owner>-<repo>[--<worktree>]`, with a
     * memorable suffix when that is taken) — the same convention the desktop's
     * dialog follows, so a session made from the phone is named like any other.
     * Optional arguments the person left out are absent, not `null` or `""`:
     * an empty `new_worktree` would read as "no worktree" today only by the
     * hub's grace.
     */
    @Test
    fun new_session_sends_the_host_the_project_and_an_empty_name_and_reads_the_row() = runTest {
        var args: JsonObject? = null
        val client = clientAnswering { body ->
            assertEquals("new_session", body.tool())
            args = body.args()
            """{"id":41,"tmux_name":"dev-me-repo","host_alias":"pine","project_id":3}"""
        }

        val row = client.newSession(hostAlias = "pine", projectId = 3)

        assertEquals(41L, row.id)
        assertEquals("pine", row.hostAlias)
        val sent = args!!
        assertEquals("pine", sent["host_alias"]!!.jsonPrimitive.content)
        assertEquals(3, sent["project_id"]!!.jsonPrimitive.int)
        assertEquals("", sent["name"]!!.jsonPrimitive.content)
        for (absent in listOf("new_worktree", "base_branch", "friendly_name", "worktree_id", "kind")) {
            assertFalse(absent in sent, "$absent was not asked for, so it is not sent")
        }
    }

    @Test
    fun new_session_passes_a_new_worktree_its_base_and_a_label_and_drops_blank_ones() = runTest {
        val sent = mutableListOf<JsonObject>()
        val client = clientAnswering { body -> sent += body.args(); """{"id":1,"tmux_name":"t","host_alias":"h"}""" }

        client.newSession(hostAlias = "h", projectId = 9, newWorktree = "feat/x", baseBranch = "develop", friendlyName = "Fix it")
        client.newSession(hostAlias = "h", projectId = 9, newWorktree = "feat/x", baseBranch = " ", friendlyName = "")

        val (first, second) = sent
        assertEquals("feat/x", first["new_worktree"]!!.jsonPrimitive.content)
        assertEquals("develop", first["base_branch"]!!.jsonPrimitive.content)
        assertEquals("Fix it", first["friendly_name"]!!.jsonPrimitive.content)
        assertFalse("base_branch" in second, "a blank base means the default branch, which is the hub's default")
        assertFalse("friendly_name" in second, "a blank label means the hub derives one")
    }

    /**
     * Creating a session is the slowest thing the app asks for. The hub bounds
     * it at `LIFECYCLE_CAP` (300 s, `mcp/tools/support.rs`) because it may
     * clone the repository onto the host first, and the app's ordinary 45 s
     * deadline would give up on a clone that is going fine — leaving a session
     * the person was told had failed. So it rides the framed mount, whose 15 s
     * keep-alive holds the socket open, under a deadline above the hub's own.
     */
    @Test
    fun new_session_rides_the_framed_mount_under_a_deadline_above_the_hubs_lifecycle_cap() = runTest {
        val calls = Calls()
        val engine = MockEngine { request ->
            calls.requests += request
            respond(sse(okResult("""{"id":1,"tmux_name":"t","host_alias":"h"}""")), HttpStatusCode.OK, sseHeaders)
        }
        val hub = HubClient(HttpClient(engine).withHubTimeouts(), BASE, "tok-phone")

        hub.newSession(hostAlias = "h", projectId = 1)

        assertEquals("/mcp", calls.path(0))
        val timeout = calls.requests.single().getCapabilityOrNull(HttpTimeoutCapability)
        assertEquals(HUB_LIFECYCLE_TIMEOUT_MS, timeout?.requestTimeoutMillis)
        assertTrue(HUB_LIFECYCLE_TIMEOUT_MS > 300_000L, "at or under the hub's own cap would cut a clone short")
        assertEquals(HUB_CALL_TIMEOUT_MS, timeout?.socketTimeoutMillis, "the keep-alive, not a longer idle, holds the socket")
    }

    // ---- the work graph (M8) ----

    /**
     * `tools/list` is plain MCP on the ordinary mount. What it answers is what
     * the hub serves this token: names, and the `action` enum where the
     * schema has one. A free-string `action` has no entry — unknown, not
     * empty.
     */
    @Test
    fun the_tool_catalog_reads_names_and_action_enums() = runTest {
        val tools = """{"jsonrpc":"2.0","id":1,"result":{"tools":[""" +
            """{"name":"list_sessions","inputSchema":{"type":"object"}},""" +
            """{"name":"work","inputSchema":{"properties":{"action":{"type":"string","enum":["links","tickets","lookup"]}}}},""" +
            """{"name":"work_link","inputSchema":{"properties":{"action":{"type":"string"}}}}]}}"""
        val (hub, calls) = client { tools to HttpStatusCode.OK }

        val catalog = hub.toolCatalog()

        assertEquals(setOf("list_sessions", "work", "work_link"), catalog.names)
        assertEquals(mapOf("work" to setOf("links", "tickets", "lookup")), catalog.actions)
        assertEquals("/mcp/json", calls.path(0))
        val sent = Json.parseToJsonElement(calls.bodyText(0)).jsonObject
        assertEquals("tools/list", sent["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun the_work_reads_name_their_actions() = runTest {
        val sent = mutableListOf<JsonObject>()
        val client = clientAnswering { body ->
            assertEquals("work", body.tool())
            sent += body.args()
            when (body.args()["action"]!!.jsonPrimitive.content) {
                "tickets" -> """[{"id":70,"key":"PAY-7","title":"Refund","status_category":"todo","live_session_ids":[3]}]"""
                "trackers" -> """[{"id":1,"provider":"jira","name":"acme","state":"ok"}]"""
                "lookup" -> """{"id":70,"key":"PAY-7","title":"Refund","description":"<b>raw</b>"}"""
                else -> """{"key":"PAY-7","modes":[{"mode":"last","ok":true}],"hosts":["pine"]}"""
            }
        }

        assertEquals(listOf(3L), client.workTickets("mine").single().liveSessionIds)
        assertEquals("acme", client.workTrackers().single().name)
        assertEquals("<b>raw</b>", client.workLookup(" PAY-7 ").description)
        client.workLookup("https://acme.atlassian.net/browse/PAY-7")
        assertTrue(client.workResumePlan("PAY-7").canResumeLast)

        assertEquals("mine", sent[0]["view"]!!.jsonPrimitive.content)
        assertEquals("PAY-7", sent[2]["key"]!!.jsonPrimitive.content, "a key is trimmed and sent as key")
        assertEquals("https://acme.atlassian.net/browse/PAY-7", sent[3]["url"]!!.jsonPrimitive.content)
        assertFalse("key" in sent[3], "a pasted URL goes as url, not key")
        assertEquals("resume_plan", sent[4]["action"]!!.jsonPrimitive.content)
    }

    /** The M9 reads and the org list: one `work` action each, the hub's recorded payloads decoded whole. */
    @Test
    fun today_card_and_orgs_send_their_action_and_decode_the_hub_shape() = runTest {
        val sent = mutableListOf<JsonObject>()
        val client = clientAnswering { body ->
            assertEquals("work", body.tool())
            val args = body.args()
            sent += args
            when (args["action"]!!.jsonPrimitive.content) {
                "today" -> HubWorkJson.TODAY_EVERY_SECTION
                "card" -> HubWorkJson.CARD_WITH_AC
                else -> HubWorkJson.ORGS
            }
        }

        val today = client.workToday(since = 1_790_294_400)
        val card = client.workCard("PAY-7")
        val orgs = client.workOrgs()

        assertEquals(listOf("today", "card", "orgs"), sent.map { it["action"]!!.jsonPrimitive.content })
        assertEquals(1_790_294_400L, sent[0]["since"]!!.jsonPrimitive.content.toLong())
        assertEquals("PAY-7", sent[1]["key"]!!.jsonPrimitive.content)
        assertEquals(setOf("action"), sent[2].keys, "the org list takes no arguments")

        assertEquals(4, today.groups.size)
        assertNull(today.groups[2].key, "the no-work group has no key on the wire")
        assertEquals("https://github.com/acme/pay/pull/9", today.groups[1].sessions.single().prUrl)
        assertEquals(listOf("PAY-3", "ENG-2"), today.shipped.map { it.key })
        assertTrue(card.cached)
        assertEquals(listOf("Refund issued within 24 h", "Email sent to https://evil.example/phish", "<b>not bold</b>"), card.acceptance)
        assertEquals(listOf(1L, 2L), orgs.map { it.id })
        assertEquals(listOf(7L), orgs[0].trackers.map { it.id })
        assertNull(orgs[1].color)
    }

    @Test
    fun the_work_link_decisions_send_the_session_and_the_link() = runTest {
        val sent = mutableListOf<JsonObject>()
        val client = clientAnswering { body ->
            assertEquals("work_link", body.tool())
            sent += body.args()
            """{"id":5,"tmux_name":"t","host_alias":"h"}"""
        }

        client.confirmWork(5, 11)
        client.rejectWork(5, 12)
        client.unlinkWork(5, 13)
        client.linkWork(5, itemId = 70)
        client.linkWork(5, key = "billing migration")

        assertEquals(listOf("confirm", "reject", "unlink", "link", "link"), sent.map { it["action"]!!.jsonPrimitive.content })
        assertEquals(listOf(11, 12, 13), sent.take(3).map { it["link_id"]!!.jsonPrimitive.int })
        assertTrue(sent.all { it["session_id"]!!.jsonPrimitive.int == 5 })
        assertEquals(70, sent[3]["item_id"]!!.jsonPrimitive.int)
        assertFalse("key" in sent[3])
        assertEquals("billing migration", sent[4]["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun start_and_resume_send_their_arguments_and_leave_out_what_was_not_chosen() = runTest {
        val sent = mutableListOf<JsonObject>()
        val client = clientAnswering { body -> sent += body.args(); """{"id":8,"tmux_name":"t","host_alias":"pine"}""" }

        client.startWork("PAY-9", hostAlias = "pine", projectId = 3)
        client.startWork("PAY-9", hostAlias = "pine")
        client.resumeWork("PAY-9", hostAlias = "hetzner")
        client.resumeWork("PAY-9")

        val (start, startDefault, resume, resumeDefault) = sent
        assertEquals("start", start["action"]!!.jsonPrimitive.content)
        assertEquals(3, start["project_id"]!!.jsonPrimitive.int)
        assertFalse("project_id" in startDefault, "no project: the hub picks the one that last worked on PAY-*")
        for (never in listOf("brief", "with_brief")) assertFalse(never in start, "the phone never sends a brief")
        assertEquals("last", resume["mode"]!!.jsonPrimitive.content)
        assertEquals("hetzner", resume["host_alias"]!!.jsonPrimitive.content)
        assertFalse("host_alias" in resumeDefault)
    }

    /** Starting work creates a session — a worktree, perhaps a clone — so it rides the lifecycle mount. */
    @Test
    fun work_link_rides_the_framed_mount_under_the_lifecycle_deadline() = runTest {
        val calls = Calls()
        val engine = MockEngine { request ->
            calls.requests += request
            val payload = if (request.url.encodedPath == "/mcp") """{"id":1,"tmux_name":"t","host_alias":"h"}""" else "[]"
            respond(sse(okResult(payload)), HttpStatusCode.OK, sseHeaders)
        }
        val hub = HubClient(HttpClient(engine).withHubTimeouts(), BASE, "tok-phone")

        hub.startWork("PAY-9", hostAlias = "h")
        hub.workTickets("mine") // an ordinary read, for contrast

        assertEquals("/mcp", calls.path(0))
        assertEquals(HUB_LIFECYCLE_TIMEOUT_MS, calls.requests[0].getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
        assertEquals("/mcp/json", calls.path(1))
    }

    /** `E_EXISTS` names the live session so the phone can jump to it — and its details are scrubbed like the message. */
    @Test
    fun a_refusal_keeps_its_details_with_the_token_scrubbed() = runTest {
        val rpc = """{"jsonrpc":"2.0","id":1,"result":{"isError":true,""" +
            """"content":[{"type":"text","text":"E_EXISTS: PAY-9 already has a live session"}],""" +
            """"structuredContent":{"code":"E_EXISTS","message":"PAY-9 already has a live session; jump to it",""" +
            """"details":{"session_id":41,"host_alias":"pine","tmux_name":"leak tok-phone here"}}}}"""
        val (hub, _) = client { sse(rpc) to HttpStatusCode.OK }

        val e = assertFailsWith<HubError.Tool> { hub.startWork("PAY-9", hostAlias = "pine") }

        assertEquals(41L, e.existingSessionId())
        assertFalse("tok-phone" in e.toString(), e.toString())
        assertNull(HubError.Tool("E_INVALID", "x").existingSessionId())
    }

    @Test
    fun an_unknown_action_refusal_is_recognised_and_nothing_else_is() {
        assertTrue(HubError.Tool("E_INVALID", "unknown work_link action \"confirm\"; one of link, reject, unlink").isUnknownAction())
        assertTrue(HubError.Tool("E_INVALID", "unknown work action \"lookup\"; one of links, context").isUnknownAction())
        assertFalse(HubError.Tool("E_INVALID", "confirm needs link_id").isUnknownAction())
        assertFalse(HubError.Tool("E_NOTFOUND", "unknown action").isUnknownAction())
    }

    @Test
    fun capabilities_gate_on_the_tool_then_the_enum_then_what_the_hub_refused() {
        val old = HubCapabilities()
        assertFalse(old.work)
        assertFalse(old.has("work_link", "confirm"), "nothing discovered: nothing offered")

        val readonly = HubCapabilities.of(ToolCatalog(setOf("work")))
        assertTrue(readonly.work)
        assertFalse(readonly.workLink, "the hub hides work_link from a readonly token")
        assertFalse(readonly.has("work_link", "confirm"))

        val free = HubCapabilities.of(ToolCatalog(setOf("work", "work_link")))
        assertTrue(free.has("work_link", "confirm"), "a free-string action is present until refused")
        val refused = free.forgetting("work_link", "confirm")
        assertFalse(refused.has("work_link", "confirm"))
        assertTrue(refused.has("work_link", "reject"))

        val enumerated = HubCapabilities.of(ToolCatalog(setOf("work", "work_link"), mapOf("work_link" to setOf("link", "unlink"))))
        assertTrue(enumerated.has("work_link", "unlink"))
        assertFalse(enumerated.has("work_link", "confirm"), "a hub before M4 enumerates no confirm")
    }

    @Test
    fun upload_attachment_posts_raw_bytes_and_returns_the_staged_path() = runTest {
        // `client(...)` is the file's own MockEngine builder; `Calls` already
        // records every request, so nothing new is needed to inspect one.
        val (hub, calls) = client { _ ->
            """{"path":"/w/proj/.claude-fleet-attachments/a.png"}""" to HttpStatusCode.OK
        }
        val path = hub.uploadAttachment(7, dev.claudefleet.mobile.model.PickedFile("a.png", 3, byteArrayOf(1, 2, 3)))
        assertEquals("/w/proj/.claude-fleet-attachments/a.png", path)

        val req = calls.requests.single()
        val url = req.url.toString()
        assertTrue(url.startsWith("$BASE/attachment"), url)
        assertTrue(url.contains("session_id=7"), url)
        assertTrue(url.contains("name=a.png"), url)
        assertEquals("Bearer tok-phone", req.headers["Authorization"])
        // Raw, not base64 and not JSON: the whole reason this is a route and not
        // a tool. A TextContent body here means it went out as a string.
        assertEquals(byteArrayOf(1, 2, 3).toList(), (req.body as ByteArrayContent).bytes().toList())
    }

    @Test
    fun a_refused_upload_raises_rather_than_returning_a_path() = runTest {
        val (hub, _) = client { _ -> "attaching needs a full token" to HttpStatusCode.Forbidden }
        val e = assertFailsWith<HubError> {
            hub.uploadAttachment(7, dev.claudefleet.mobile.model.PickedFile("a.png", 1, byteArrayOf(1)))
        }
        assertTrue(e.message!!.contains("full token"), e.message!!)
    }
}
