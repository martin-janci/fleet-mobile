package dev.claudefleet.mobile.net

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `tools/list` as claude-fleet serves it after M8.0 — trimmed to the keys
 * this reads, with `work` / `work_link` carrying their `action` enums.
 */
private val M8_HUB = """
{"tools":[
  {"name":"list_sessions","inputSchema":{"type":"object","properties":{"view":{"type":"string"}}}},
  {"name":"work","inputSchema":{"type":"object","properties":{"action":{"type":"string","description":"Default links.",
    "enum":["links","context","resume_plan","purge_impact","tickets","lookup","trackers"]}}}},
  {"name":"work_link","inputSchema":{"type":"object","required":["action"],"properties":{"action":{"type":"string",
    "enum":["link","reject","unlink","confirm","trust_project","resume","start"]}}}}
]}
""".trimIndent()

/** A hub from before M8.0: the tools are there, `action` is a free string. */
private val M4_HUB = """
{"tools":[
  {"name":"work","inputSchema":{"properties":{"action":{"type":"string"}}}},
  {"name":"work_link","inputSchema":{"properties":{"action":{"type":"string"}}}}
]}
""".trimIndent()

class HubCapabilitiesTest {

    @Test
    fun an_m8_hub_names_its_tools_and_their_actions() {
        val caps = HubCapabilities.fromToolsList(Json.parseToJsonElement(M8_HUB))
        assertTrue(caps.known)
        assertTrue(caps.work)
        assertTrue(caps.workLink)
        assertTrue(caps.has("work_link", "confirm"))
        assertFalse(caps.has("work_link", "teleport"), "an action the enum does not list is absent")
        assertTrue(caps.has("work", "tickets"))
        assertEquals(null, caps.actions["list_sessions"], "no enum, no entry")
    }

    /** What a readonly token is served: the hub hides `work_link`, so every write button goes. */
    @Test
    fun a_readonly_token_without_work_link_has_no_write_actions() {
        val ro = HubCapabilities.fromToolsList(
            Json.parseToJsonElement("""{"tools":[{"name":"work","inputSchema":{}}]}"""),
        )
        assertTrue(ro.work)
        assertFalse(ro.workLink)
        assertFalse(ro.has("work_link", "confirm"))
    }

    @Test
    fun without_an_enum_every_action_is_assumed_until_the_hub_refuses_one() {
        val caps = HubCapabilities.fromToolsList(Json.parseToJsonElement(M4_HUB))
        assertTrue(caps.has("work_link", "confirm"))
        val after = caps.withoutAction("work_link", "confirm")
        assertFalse(after.has("work_link", "confirm"))
        assertTrue(after.has("work_link", "reject"), "one refusal hides one action")
        assertTrue(after.has("work", "tickets"))
    }

    @Test
    fun an_unknown_hub_offers_nothing() {
        val none = HubCapabilities()
        assertFalse(none.known)
        assertFalse(none.work)
        assertFalse(none.has("work", "links"))
        // A reply without a tool list is an answer, just an empty one.
        assertTrue(HubCapabilities.fromToolsList(Json.parseToJsonElement("[]")).known)
    }

    @Test
    fun a_malformed_entry_is_skipped_not_fatal() {
        val caps = HubCapabilities.fromToolsList(
            Json.parseToJsonElement(
                """{"tools":[{"nope":1},{"name":7},{"name":"work","inputSchema":{"properties":{"action":{"enum":[1,"links"]}}}}]}""",
            ),
        )
        assertEquals(setOf("work"), caps.tools)
        assertEquals(setOf("links"), caps.actions["work"])
    }

    /** The message is the hub's own (`service/work/mod.rs` `parse_action`). */
    @Test
    fun the_hubs_unknown_action_refusal_is_recognised() {
        assertTrue(
            HubError.Tool("E_INVALID", """unknown work_link action "confirm"; one of link, reject, unlink""").isUnknownAction(),
        )
        assertTrue(HubError.Tool("E_INVALID", """unknown work action "tickets"; one of links""").isUnknownAction())
        assertFalse(HubError.Tool("E_INVALID", "confirm needs link_id").isUnknownAction())
        assertFalse(HubError.Tool("E_NOTFOUND", "unknown work action").isUnknownAction())
    }
}
