// `@JsonIgnoreUnknownKeys` is still experimental in kotlinx.serialization.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import kotlinx.serialization.json.JsonObject

/**
 * A wizard left half-way, kept on the hub so the person's other device
 * carries on where they stopped (`wizard_state`, hub contract 17, gap plan
 * G7.2). [answers] is the wizard's own shape, the same on the desktop and
 * here, and never holds a secret: the hub refuses one.
 */
@Serializable
@JsonIgnoreUnknownKeys
data class WizardState(
    /** `add_host`, `add_project`, `add_account`, `new_session`, `link_peer` or `form`. */
    val kind: String = "",
    val key: String = "",
    /** What the resume line names ("acme/api"). */
    val label: String? = null,
    /** The step it was left on, from 1. */
    val step: Int = 1,
    val answers: JsonObject = JsonObject(emptyMap()),
    /** The device that saved it last; null is the hub's own desktop. */
    val device: String? = null,
    @SerialName("updated_at") val updatedAt: Long = 0,
)

/** The Add a project wizard's kind, shared with the desktop's dialog. */
const val WIZARD_ADD_PROJECT = "add_project"

/** "on Ada's Mac 5 min ago", or "on the desktop just now" for the hub's own app. */
fun startedWhere(w: WizardState, nowSeconds: Long): String {
    val where = w.device?.takeIf { it.isNotBlank() }?.let { "on $it" } ?: "on the desktop"
    return listOfNotNull(where, relativeAgo(w.updatedAt.takeIf { it > 0 }, nowSeconds)).joinToString(" ")
}
