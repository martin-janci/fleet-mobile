package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Jev's host for a project's new session (claude-fleet N5 `host_placement`,
 * redesign 4.11): `propose_host_placement { project_id }`, mirroring the
 * hub's `host_placement::SuggestedHost`. The hub answers null with its
 * defaults, for one candidate or none, and whenever the model is unsure.
 *
 * [reason] is the hub's own words when it gives them ("last 4 sessions on
 * fleet-mobile ran here"); an older answer has none and the line says only
 * who proposed it.
 */
@Serializable
data class SuggestedHost(
    @SerialName("host_alias") val hostAlias: String,
    @SerialName("confidence_pct") val confidencePct: Int? = null,
    @SerialName("run_id") val runId: Long? = null,
    val reason: String? = null,
)

/**
 * PURE: the proposal in `propose_host_placement`'s answer — the object
 * itself, or under `suggestion` — or null for a null answer, one without a
 * host, or anything else.
 */
fun suggestedHostOf(element: JsonElement): SuggestedHost? {
    if (element !is JsonObject) return null
    val body = element["suggestion"] as? JsonObject ?: element
    val alias = (body["host_alias"] as? JsonPrimitive)?.contentOrNull
    if (alias.isNullOrBlank()) return null
    return json.decodeFromJsonElement(SuggestedHost.serializer(), body)
}

/** The line under the pre-selected host, as a chat form's proposed choice says it: "Proposed by Jev · last 4 sessions … ran here". */
fun hostProposalLine(s: SuggestedHost): String =
    pickLine(FormPick(field = "host", value = s.hostAlias, by = "jev", reason = s.reason?.trim()?.takeIf { it.isNotEmpty() }))
