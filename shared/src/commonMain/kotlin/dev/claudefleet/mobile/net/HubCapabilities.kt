package dev.claudefleet.mobile.net

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** What one `tools/list` answered: tool names, and the `action` enum of each tool whose schema has one. */
data class ToolCatalog(
    val names: Set<String> = emptySet(),
    val actions: Map<String, Set<String>> = emptyMap(),
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
 *
 * The default is the old hub: nothing discovered, every work feature hidden.
 */
data class HubCapabilities(
    val tools: Set<String> = emptySet(),
    val actions: Map<String, Set<String>> = emptyMap(),
    val missing: Map<String, Set<String>> = emptyMap(),
) {
    val work: Boolean get() = WORK in tools
    val workLink: Boolean get() = WORK_LINK in tools

    /** The hub's agent (`ensure_operator`) — the desktop's ✦, on the phone. */
    val agent: Boolean get() = ENSURE_OPERATOR in tools

    fun has(tool: String, action: String): Boolean =
        tool in tools &&
            actions[tool]?.contains(action) != false &&
            action !in missing[tool].orEmpty()

    /** This connection learned [action] is not one [tool] has. */
    fun forgetting(tool: String, action: String): HubCapabilities =
        copy(missing = missing + (tool to (missing[tool].orEmpty() + action)))

    companion object {
        const val WORK = "work"
        const val WORK_LINK = "work_link"
        const val ENSURE_OPERATOR = "ensure_operator"

        fun of(catalog: ToolCatalog) = HubCapabilities(catalog.names, catalog.actions)
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

/** The session an `E_EXISTS` refusal names — "that work is live there; jump to it" — or null. */
fun HubError.Tool.existingSessionId(): Long? =
    if (code != "E_EXISTS") null
    else ((details as? JsonObject)?.get("session_id") as? JsonPrimitive)?.longOrNull
