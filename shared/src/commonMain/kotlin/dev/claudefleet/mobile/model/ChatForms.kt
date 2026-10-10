package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Chat forms (claude-fleet contract revision 9, `docs/forms.md`): an agent's
 * `ask { form }` opens a fleet.form/1 form in its session's chat and waits;
 * a person with drive on the session answers or declines it, on the desktop
 * or here. The row says a form waits (`pending_form`); `ask { get }` reads it,
 * `ask { answer }` and `ask { decline }` decide it. The hub checks every
 * answer again, so nothing here is the gate.
 */

/** A session's open form, as its row carries it. */
@Serializable
data class PendingForm(
    @SerialName("form_id") val formId: String,
    val title: String = "",
)

/** One form, as `ask { get | answer | decline }` answers it (the hub's `FormView`). */
@Serializable
data class FormView(
    @SerialName("form_id") val formId: String,
    @SerialName("session_id") val sessionId: Long = 0,
    @SerialName("host_alias") val hostAlias: String = "",
    val title: String = "",
    /** The fleet.form/1 spec; read with [readAskForm]. */
    val spec: JsonElement? = null,
    val why: String? = null,
    /** pending | answered | declined | cancelled | expired. */
    val state: String = "pending",
    @SerialName("answered_by") val answeredBy: String? = null,
    val note: String? = null,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("decided_at") val decidedAt: Long? = null,
)

/** One answer the hub refused, from an `E_INVALID`'s `details.problems`. */
data class FieldProblem(val field: String, val problem: String)

/** The problems an answer's refusal names, or empty when it names none. */
fun fieldProblems(details: JsonElement?): List<FieldProblem> =
    ((details as? JsonObject)?.get("problems") as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val field = (o["field"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val problem = (o["problem"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        FieldProblem(field, problem)
    }

private val OUTCOME = mapOf(
    "answered" to "answered",
    "declined" to "declined",
    "cancelled" to "withdrawn by the agent",
    "expired" to "expired unanswered",
    "pending" to "still waiting",
)

/** How a form ended, in the desktop's words: "Deploy: answered by Martin". */
fun formOutcome(form: FormView): String {
    val by = form.answeredBy?.takeIf { form.state == "answered" || form.state == "declined" }
    return "${form.title}: ${OUTCOME[form.state] ?: form.state}" + (by?.let { " by $it" } ?: "")
}

/** The answers to send: the shown fields' values, in form order, never a disabled field's. */
fun formAnswers(form: ReplyForm, values: Map<String, JsonElement>): Map<String, JsonElement> =
    answerable(form, values).mapNotNull { f -> values[f.name]?.let { f.name to it } }.toMap()
