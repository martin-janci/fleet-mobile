package dev.claudefleet.mobile.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What this hub will let this token do, as its `tools/list` says — the gate
 * for every additive feature the wire contract does not move for (work graph
 * M8, review C19).
 *
 * The hub already filters `tools/list` per caller (`present::visible_to`), so
 * a `readonly` token is simply not served `work_link`; reading the list gates
 * a readonly phone for free, on top of `Credentials.canWrite`. Gating on the
 * contract revision instead would lock every phone out of a hub that merely
 * gained a tool.
 *
 * **Actions.** Where a tool's `action` parameter is a schema `enum` (claude-
 * fleet M8.0 onwards), [actions] holds it and a button for an action the hub
 * does not list is not drawn. Where it is not (an older hub), every action is
 * assumed until the hub refuses one with `E_INVALID` "unknown … action";
 * [withoutAction] records that for the rest of the connection.
 *
 * [known] is false until a `tools/list` has answered — a hub too old to answer
 * one keeps the version-string gate (`HUB_VERSION_KEYS`) as its only gate.
 */
data class HubCapabilities(
    val known: Boolean = false,
    val tools: Set<String> = emptySet(),
    /** Tool → the `action` enum its schema declares. Absent when the schema has none. */
    val actions: Map<String, Set<String>> = emptyMap(),
    /** Tool → actions the hub refused as unknown on this connection. */
    val refused: Map<String, Set<String>> = emptyMap(),
) {
    /** Work chips and grouping. */
    val work: Boolean get() = WORK in tools

    /** Confirm / Not this / Start / Resume. Never true for a readonly token. */
    val workLink: Boolean get() = WORK_LINK in tools

    /** Whether [tool] is served and, as far as this connection knows, takes [action]. */
    fun has(tool: String, action: String): Boolean {
        if (tool !in tools) return false
        if (action in refused[tool].orEmpty()) return false
        return actions[tool]?.contains(action) ?: true
    }

    /** The same, with [action] marked absent — after the hub refused it as unknown. */
    fun withoutAction(tool: String, action: String): HubCapabilities =
        copy(refused = refused + (tool to (refused[tool].orEmpty() + action)))

    companion object {
        const val WORK = "work"
        const val WORK_LINK = "work_link"

        /**
         * Read a `tools/list` result. Tolerant by design: a tool without a
         * name is skipped, and an `action` that is not a string enum reads as
         * "no enum" rather than "no actions".
         */
        fun fromToolsList(result: JsonElement): HubCapabilities {
            val list = (result as? JsonObject)?.get("tools") as? JsonArray ?: return HubCapabilities(known = true)
            val tools = mutableSetOf<String>()
            val actions = mutableMapOf<String, Set<String>>()
            for (entry in list) {
                val tool = entry as? JsonObject ?: continue
                val name = (tool["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                tools += name
                val enum = (tool["inputSchema"] as? JsonObject)
                    ?.let { it["properties"] as? JsonObject }
                    ?.let { it["action"] as? JsonObject }
                    ?.let { it["enum"] as? JsonArray }
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (!enum.isNullOrEmpty()) actions[name] = enum.toSet()
            }
            return HubCapabilities(known = true, tools = tools, actions = actions)
        }
    }
}

/**
 * Whether [error] is the hub saying it does not know an action — the fallback
 * gate for a hub whose `action` is a free string. Its message reads
 * `unknown work_link action "confirm"; one of …`.
 */
fun HubError.isUnknownAction(): Boolean =
    this is HubError.Tool && code == "E_INVALID" && Regex("""unknown \w+ action""").containsMatchIn(message)
