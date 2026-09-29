package dev.claudefleet.mobile.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * What one `tools/list` answered: tool names, the `action` enum of each tool
 * whose schema has one, and the argument names each tool's schema lists.
 */
data class ToolCatalog(
    val names: Set<String> = emptySet(),
    val actions: Map<String, Set<String>> = emptyMap(),
    val params: Map<String, Set<String>> = emptyMap(),
)

/**
 * What this hub lets *this token* do, beyond the tools every hub has — the
 * gate the work graph's screens hang on (review C19: gate on the hub's tools,
 * not on the contract revision).
 *
 * Built from [ToolCatalog] on every `ready`, so it is per connection:
 *  - [work] — the hub has the work graph at all: chips, grouping, tickets.
 *  - [workLink] — this token may change links and start or resume work. The
 *    hub hides the tool from a readonly token, so that case needs no rule of
 *    its own here; the UI still also checks `canWrite`.
 *  - [has] — one action of a tool. A schema `enum` answers it outright; a
 *    free-string `action` (a hub before M8.0) is taken as present until the
 *    hub refuses it with "unknown … action", which [forgetting] records for
 *    the rest of the connection.
 *  - [accepts] — one argument of a tool, for a feature that is a new
 *    argument of an old action (multi-start's `project_ids`). Absent from the
 *    schema means absent: an older hub would ignore the argument, not refuse
 *    it, so there is no refusal to learn from.
 *
 * The default is the old hub: nothing discovered, every work feature hidden.
 */
data class HubCapabilities(
    val tools: Set<String> = emptySet(),
    val actions: Map<String, Set<String>> = emptyMap(),
    val missing: Map<String, Set<String>> = emptyMap(),
    val params: Map<String, Set<String>> = emptyMap(),
) {
    val work: Boolean get() = WORK in tools
    val workLink: Boolean get() = WORK_LINK in tools

    /** The hub's agent (`ensure_operator`) — the desktop's ✦, on the phone. */
    val agent: Boolean get() = ENSURE_OPERATOR in tools

    /**
     * The hub keeps the composer's chip row (`quick_replies`). Absent on a hub
     * older than that tool — the app then draws its cached chips and never
     * tries to sync them — and absent for a `readonly` token, which the hub
     * does not show a tool that can write.
     */
    val quickReplies: Boolean get() = QUICK_REPLIES in tools

    /**
     * A tool call's row can be expanded to its input and result
     * (`session_tool_detail`, readonly on the hub). Absent on an older hub,
     * whose rows still show their verb and target and simply do not open.
     */
    val toolDetail: Boolean get() = SESSION_TOOL_DETAIL in tools

    /**
     * The fleet's settings pages (claude-fleet declarative pages P6): the hub
     * serves this token the page specs and the settings. It does to a
     * person's own device — a client bound to no org, of either mode — and
     * to nothing else, and an older hub has neither tool.
     */
    val fleetSettings: Boolean get() = LIST_PAGES in tools && GET_SETTINGS in tools

    fun has(tool: String, action: String): Boolean =
        tool in tools &&
            actions[tool]?.contains(action) != false &&
            action !in missing[tool].orEmpty()

    /**
     * [has], and the hub's schema *names* [action] in [tool]'s enum — not
     * merely a free-string `action` that might take it. For a feature whose
     * whole screen hangs on an action only a newer hub has (the Work tab on
     * `work { tree }`): every hub that serves it enumerates its actions, so a
     * hub that does not is one that cannot, and no tab is drawn to be refused.
     */
    fun lists(tool: String, action: String): Boolean =
        has(tool, action) && actions[tool]?.contains(action) == true

    fun accepts(tool: String, param: String): Boolean = tool in tools && params[tool]?.contains(param) == true

    /** This connection learned [action] is not one [tool] has. */
    fun forgetting(tool: String, action: String): HubCapabilities =
        copy(missing = missing + (tool to (missing[tool].orEmpty() + action)))

    companion object {
        const val WORK = "work"
        const val WORK_LINK = "work_link"
        const val ENSURE_OPERATOR = "ensure_operator"
        const val QUICK_REPLIES = "quick_replies"
        const val SESSION_TOOL_DETAIL = "session_tool_detail"
        const val LIST_PAGES = "list_pages"
        const val GET_SETTINGS = "get_settings"

        fun of(catalog: ToolCatalog) = HubCapabilities(catalog.names, catalog.actions, params = catalog.params)
    }
}

/**
 * The hub's refusal of an `action` it does not know: `E_INVALID` with
 * "unknown work_link action \"confirm\"; one of …". What a hub whose schema
 * does not enumerate its actions says instead of hiding them.
 */
fun HubError.Tool.isUnknownAction(): Boolean =
    code == "E_INVALID" && message.contains("unknown", ignoreCase = true) &&
        message.contains("action", ignoreCase = true)

/**
 * The session an `E_EXISTS` refusal names — "that work is live there; jump to
 * it" — or null. Also null when the refusal names an [orphanSessionId]: the
 * call made a session of its own before it lost, and a jump past it would
 * leave that one running unlinked with nobody told.
 */
fun HubError.Tool.existingSessionId(): Long? =
    if (code != "E_EXISTS" || orphanSessionId() != null) null
    else ((details as? JsonObject)?.get("session_id") as? JsonPrimitive)?.longOrNull

/**
 * The session a `start` or `resume` made and could not link — it lost a race
 * to another client, or the link failed — which the hub names so nobody has
 * to find it. Its message names it too, which is what the screen shows.
 */
fun HubError.Tool.orphanSessionId(): Long? =
    ((details as? JsonObject)?.get("orphan_session_id") as? JsonPrimitive)?.longOrNull
