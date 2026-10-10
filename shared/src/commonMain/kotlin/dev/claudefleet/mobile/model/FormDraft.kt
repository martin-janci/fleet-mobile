package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.MAX_JSON_DEPTH
import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.net.nestsWithin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/*
 * A chat form while its agent still writes it (claude-fleet PR #779, the
 * MobileChatForms board's Building): `ask { draft }` stores the fleet.form/1
 * JSON written so far (migration 153, at most 16 KiB, not yet valid JSON) and
 * the session's row carries it as `form_draft` until `ask { form }` opens the
 * form, `ask { draft: "" }` drops it, or the hub's tick drops it 10 minutes
 * after the last write. The phone draws it in: the title, step bars, the
 * first step's fields as soon as each is whole (fillable), a skeleton for the
 * rest, and what the agent reads. What the person fills in goes to the form
 * once it opens ([carryDraftAnswers]); nothing is sent from a draft.
 *
 * [partialForm] is a port of the desktop's `src/lib/forms/partial_spec.ts`.
 */

/** The form a session's agent is still writing, as its row carries it. */
@Serializable
data class FormDraft(
    val draft: String = "",
    /** The agent's one sentence: what it reads while it writes ("the Jira epic PD-3100"). */
    val why: String? = null,
    @SerialName("updated_at") val updatedAt: Long = 0,
)

/** A draft not written to for this long has been abandoned: the hub's tick drops it then; this covers the time until it does. */
const val FORM_DRAFT_TTL_SECS: Long = 10 * 60

/**
 * The draft [row]'s chat draws at [now] (unix seconds), or null: none, an
 * empty one, one the open form has replaced, or one abandoned
 * [FORM_DRAFT_TTL_SECS] ago. The desktop's `ChatWizards.svelte` rule.
 */
fun liveFormDraft(row: SessionRow?, now: Long): FormDraft? {
    val d = row?.formDraft ?: return null
    if (row.pendingForm != null || d.draft.isBlank()) return null
    return d.takeIf { it.updatedAt >= now - FORM_DRAFT_TTL_SECS }
}

/**
 * One field of a draft, drawn once its name, type and label are whole.
 * [field] is set when the person may fill it in already: its object is
 * closed (another field follows it, or the text is whole), the phone can
 * draw it, and it is not a secret (typed into the open form only, never held
 * across the wait). Otherwise it is a skeleton under its label.
 */
data class DraftField(val name: String, val type: String, val label: String, val field: ReplyField?)

data class DraftStep(val title: String, val intro: String?, val fields: List<DraftField>)

/** What of a fleet.form/1 spec is written so far. */
data class PartialForm(
    val title: String?,
    val intro: String?,
    /** The steps written so far (each once it has a title), with their whole fields. */
    val steps: List<DraftStep>,
    /** The text is the whole spec: it parses as written. */
    val complete: Boolean,
) {
    /** The fields the person may fill in now: the first step's settled ones. */
    val fillable: List<ReplyField> get() = steps.firstOrNull()?.fields.orEmpty().mapNotNull { it.field }

    companion object {
        val EMPTY = PartialForm(null, null, emptyList(), complete = false)
    }
}

private val DRAFT_TYPES = setOf("text", "textarea", "number", "bool", "select", "multiselect", "secret")

/**
 * Where the JSON prefix may be cut and closed: at each point a value may
 * end, latest first, as (end of the kept text, the brackets that close it).
 * Kept as offsets rather than strings: a 16 KiB draft has thousands of them,
 * and the first one or two usually parse.
 */
private fun cuts(text: String): List<Pair<Int, String>> {
    val out = ArrayList<Pair<Int, String>>()
    val stack = StringBuilder()
    var inString = false
    var escaped = false
    fun close() = stack.reversed().toString()
    for (i in text.indices) {
        val c = text[i]
        if (inString) {
            when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            continue
        }
        when (c) {
            '"' -> inString = true
            '{' -> {
                stack.append('}')
                out += (i + 1) to close()
            }
            '[' -> {
                stack.append(']')
                out += (i + 1) to close()
            }
            '}', ']' -> {
                if (stack.isNotEmpty()) stack.setLength(stack.length - 1)
                out += (i + 1) to close()
            }
            ',' -> out += i to close()
        }
    }
    if (!inString) out += text.length to close()
    out.reverse()
    return out
}

private fun parseOrNull(s: String): JsonElement? = try {
    json.parseToJsonElement(s)
} catch (e: Exception) {
    null
}

private fun JsonObject.text(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content

/** [f] as the phone draws it in an open form, or null when it could not: the same check `ask { get }`'s spec passes. */
private fun readOne(f: JsonObject): ReplyField? {
    val one = buildJsonObject {
        put("spec", "fleet.form/1")
        put("title", "draft")
        put(
            "steps",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("title", "draft")
                        put("fields", buildJsonArray { add(f) })
                    },
                )
            },
        )
    }
    return readAskForm(one)?.steps?.singleOrNull()?.fields?.singleOrNull()
}

private fun shape(doc: JsonElement, complete: Boolean): PartialForm {
    val d = doc as? JsonObject ?: return PartialForm.EMPTY.copy(complete = complete)
    val stepsArr = (d["steps"] as? JsonArray).orEmpty()
    val steps = stepsArr.mapIndexedNotNull { si, s ->
        val step = s as? JsonObject ?: return@mapIndexedNotNull null
        val title = step.text("title") ?: return@mapIndexedNotNull null
        val fieldsArr = (step["fields"] as? JsonArray).orEmpty()
        // Anything after an element in its array means the element was closed.
        val laterStep = si < stepsArr.size - 1
        val fields = fieldsArr.mapIndexedNotNull { fi, e ->
            val f = e as? JsonObject ?: return@mapIndexedNotNull null
            val name = f.text("name") ?: return@mapIndexedNotNull null
            val label = f.text("label") ?: return@mapIndexedNotNull null
            val type = f.text("type")?.takeIf { it in DRAFT_TYPES } ?: return@mapIndexedNotNull null
            // The last field written may still be open (its options cut
            // short): it is settled once something follows it, or the text
            // is whole.
            val settled = complete || laterStep || fi < fieldsArr.size - 1
            DraftField(name, type, label, if (settled && type != "secret") readOne(f) else null)
        }
        DraftStep(title, step.text("intro"), fields)
    }
    return PartialForm(d.text("title"), d.text("intro"), steps, complete)
}

/** What of the spec in [text] is written so far. */
fun partialForm(text: String): PartialForm {
    if (text.isBlank() || !nestsWithin(text, MAX_JSON_DEPTH)) return PartialForm.EMPTY
    parseOrNull(text)?.let { return shape(it, complete = true) }
    // Still being written: read the longest prefix that parses.
    for ((end, closer) in cuts(text)) {
        parseOrNull(text.substring(0, end) + closer)?.let { return shape(it, complete = false) }
    }
    return PartialForm.EMPTY
}

/**
 * What the person filled in while the form was written, kept for the form
 * `ask { form }` opened: only answers to a field of the same name the form
 * still holds that fit it (an option it still offers, a number for a
 * number), never a secret. The hub checks the answer again on send.
 */
fun carryDraftAnswers(form: ReplyForm, typed: Map<String, JsonElement>): Map<String, JsonElement> {
    if (typed.isEmpty()) return emptyMap()
    val out = mutableMapOf<String, JsonElement>()
    for (f in form.steps.flatMap { it.fields }) {
        val v = typed[f.name] ?: continue
        val p = v as? JsonPrimitive
        val options = f.options.map { it.first }.toSet()
        val kept: JsonElement? = when (f.type) {
            "text", "textarea" -> v.takeIf { p != null && p.isString }
            "number" -> v.takeIf { p != null && !p.isString && p.doubleOrNull != null }
            "bool" -> v.takeIf { p != null && !p.isString && p.booleanOrNull != null }
            "select" -> v.takeIf { p != null && p.isString && p.content in options }
            "multiselect" -> (v as? JsonArray)
                ?.filter { it is JsonPrimitive && it.isString && it.content in options }
                ?.takeIf { it.isNotEmpty() }
                ?.let { JsonArray(it) }
            else -> null
        }
        if (kept != null) out[f.name] = kept
    }
    return out
}
