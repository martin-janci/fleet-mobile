package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
    /** An answered form's answers, never a secret's value. */
    val answers: JsonObject? = null,
    /** An answered form's secrets: field name to the file on the session's host. */
    val secrets: Map<String, String>? = null,
    /** What Jev proposes for the form's first choice while it waits (J5, assist mode); a person still answers. */
    val proposal: FormProposal? = null,
)

/** The hub's `FormProposal`: Jev's likely value for one select of a waiting form. */
@Serializable
data class FormProposal(
    val field: String,
    val value: String,
    val source: String = "jev",
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

/** One select's proposed option, as the card draws it: chosen while the field is empty, "Proposed by Jev · why · Change". */
data class FormPick(val field: String, val value: String, val by: String, val reason: String?)

/**
 * Each select's proposal, as the desktop's `FormWizard` picks it: Jev's
 * (`FormView.proposal`) first, else the option the spec marks `proposed`.
 * Never for a disabled field, one the person already changed ([dismissed]),
 * a value the field does not offer, or an option AI never picks ([riskyChoice]).
 */
fun formPicks(form: ReplyForm, proposal: FormProposal?, dismissed: Set<String> = emptySet()): Map<String, FormPick> =
    form.steps.flatMap { it.fields }
        .filter { it.type == "select" && it.disabledReason == null && it.name !in dismissed }
        .mapNotNull { f ->
            fun safe(value: String): Boolean {
                val label = f.options.firstOrNull { it.first == value }?.second ?: return false
                return !riskyChoice(label) && !riskyChoice(value)
            }
            val jev = proposal?.takeIf { it.field == f.name && safe(it.value) }
            val spec = f.proposed?.takeIf { safe(it.value) }
            when {
                jev != null -> FormPick(f.name, jev.value, jev.source, null)
                spec != null -> FormPick(f.name, spec.value, spec.by, spec.reason)
                else -> null
            }
        }
        .associateBy { it.field }

/** Who proposed it, in words: "Jev", "a rule", "AI". */
fun proposerWord(by: String): String = when (by) {
    "jev" -> "Jev"
    "rule" -> "a rule"
    "llm" -> "AI"
    else -> by
}

/** The line under a proposed choice: "Proposed by Jev · you used it for the last three deploys". */
fun pickLine(pick: FormPick): String = "Proposed by ${proposerWord(pick.by)}" + pick.reason?.let { " · $it" }.orEmpty()

/** A select's options in the order shown: the proposed one first, the rest as the form wrote them. */
fun orderedOptions(f: ReplyField, pick: FormPick?): List<Pair<String, String>> {
    val first = pick?.value ?: return f.options
    return f.options.filter { it.first == first } + f.options.filter { it.first != first }
}

/**
 * An answered form's answers, label over value, in form order: an option's
 * label, Yes or No, a list joined, a secret as "Saved on the host". The
 * card's View.
 */
fun answerLines(form: ReplyForm, view: FormView): List<Pair<String, String>> {
    val answers: Map<String, JsonElement> = view.answers.orEmpty()
    val secrets = view.secrets.orEmpty()
    return visibleFields(form, answers).flatMap { it.second }.filter { it.disabledReason == null }.mapNotNull { f ->
        when {
            f.type == "secret" -> if (f.name in secrets) f.label to "Saved on the host" else null
            else -> answers[f.name]?.takeIf { it !is JsonNull }?.let { f.label to answerWords(f, it) }
        }
    }
}

/** How many answers the folded line names before "+N more". */
private const val SUMMARY_PARTS = 3

/**
 * An answered form in a few words, as the board folds it:
 * "mercury · 2 tickets · token saved". A choice by its label, a list by its
 * count, a yes by the field's name, a secret as saved; a no says nothing.
 */
fun answerSummary(form: ReplyForm, view: FormView): String? {
    val answers: Map<String, JsonElement> = view.answers.orEmpty()
    val secrets = view.secrets.orEmpty()
    val parts = visibleFields(form, answers).flatMap { it.second }.filter { it.disabledReason == null }.mapNotNull { f ->
        if (f.type == "secret") return@mapNotNull if (f.name in secrets) "${f.label.lowercase()} saved" else null
        val v = answers[f.name]?.takeIf { it !is JsonNull } ?: return@mapNotNull null
        when {
            f.type == "bool" -> f.label.lowercase().takeIf { (v as? JsonPrimitive)?.booleanOrNull == true }
            v is JsonArray -> when (v.size) {
                0 -> null
                1 -> answerWords(f, v)
                else -> "${v.size} ${f.label.lowercase()}"
            }
            else -> answerWords(f, v).let { if (it.length > 28) it.take(27) + "…" else it }.takeIf { it.isNotBlank() }
        }
    }
    if (parts.isEmpty()) return null
    val more = parts.size - SUMMARY_PARTS
    return parts.take(SUMMARY_PARTS).joinToString(" · ") + if (more > 0) " · +$more more" else ""
}

/** The origins a session started by an agent's own call carries (hub contract 11): Control's, or a per-host token's. */
private val AGENT_STARTS = setOf("operator", "token")

/**
 * The session an answered form's answer started, for the line under it
 * (MobileChatForms: "the work it started follows"). The hub links no form to
 * a session, so this reads the rows: the first session the asking session
 * started (`origin` operator or token, `origin_ref` its id, which is what
 * the hub stamps on an agent's `new_session`) at or after the answer. Null
 * for a form not answered, or before such a row arrives.
 */
fun startedByForm(form: FormView, rows: List<SessionRow>): SessionRow? {
    if (form.state != "answered" || form.sessionId == 0L) return null
    val decided = form.decidedAt ?: return null
    val asker = form.sessionId.toString()
    return rows
        .filter { it.id != form.sessionId && it.origin in AGENT_STARTS && it.originRef == asker }
        .filter { (it.startedAt ?: it.createdAt ?: 0L) >= decided }
        .minByOrNull { it.startedAt ?: it.createdAt ?: 0L }
}

/** The started session's line: "starting on mercury" until its agent says anything, then "on mercury". */
fun startedWorkLine(row: SessionRow): String {
    val on = row.hostAlias.takeIf { it.isNotBlank() }?.let { " on $it" }.orEmpty()
    return if (row.claudeStatus.isNullOrBlank()) "starting$on" else on.trimStart()
}

/**
 * The hub's `SuggestedHost` (`propose_host_placement`, Jev N5
 * `host_placement`): the host Jev would start a project's next session on.
 * The hub answers null when no host is clearly the one, outside `assist`, or
 * with one host or none left after the limits.
 */
@Serializable
data class SuggestedHost(
    @SerialName("host_alias") val hostAlias: String,
    /** The model's confidence, in whole percent. */
    @SerialName("confidence_pct") val confidencePct: Int? = null,
    @SerialName("run_id") val runId: Long? = null,
)

/** The field names a form asks for a host with, and those naming the project it is for. */
private val HOST_FIELDS = setOf("host", "host_alias")
private val PROJECT_FIELDS = setOf("project_id", "project")

/** A form's host question, and the project to ask Jev about for it. */
data class HostAsk(val field: String, val projectId: Long)

/**
 * Whether to ask Jev for a host (MobileControl's plan: "Proposed by Jev
 * mercury for the probe … · Change"): a shown host choice (a select named
 * `host` or `host_alias` with two or more hosts) that nothing has chosen or
 * proposed yet, on a form that names its project by id (`project_id` or
 * `project`). Null otherwise: the hub's question is about one project.
 */
fun hostPlacementAsk(form: ReplyForm, values: Map<String, JsonElement>, picks: Map<String, FormPick>): HostAsk? {
    val shown = visibleFields(form, values).flatMap { it.second }
    val host = shown.firstOrNull { it.type == "select" && it.name in HOST_FIELDS && it.options.size >= 2 && it.disabledReason == null } ?: return null
    if (host.name in picks || values[host.name] != null) return null
    val project = form.steps.flatMap { it.fields }.firstOrNull { it.name in PROJECT_FIELDS } ?: return null
    val id = (values[project.name] as? JsonPrimitive)?.content?.trim()?.toLongOrNull() ?: return null
    return HostAsk(host.name, id)
}

/** Jev's host as the card's pick, "Proposed by Jev · 82% sure": only a host the field offers. */
fun hostPick(form: ReplyForm, ask: HostAsk, suggested: SuggestedHost): FormPick? {
    val f = form.steps.flatMap { it.fields }.firstOrNull { it.name == ask.field } ?: return null
    if (f.options.none { it.first == suggested.hostAlias }) return null
    return FormPick(ask.field, suggested.hostAlias, "jev", suggested.confidencePct?.let { "$it% sure" })
}
