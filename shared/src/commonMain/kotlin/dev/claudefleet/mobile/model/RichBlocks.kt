package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.MAX_JSON_DEPTH
import dev.claudefleet.mobile.net.nestsWithin
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/*
 * Rich blocks in a reply: the parts of an assistant's text the conversation
 * draws as a card instead of as Markdown. A port of claude-fleet's
 * `src/lib/rich_blocks.ts` (format: claude-fleet `docs/chat-blocks.md`); the
 * problem strings are the desktop's, word for word, so a person sees the same
 * reason on either screen.
 *
 * - A task report: a `FLEET_TASK_DONE_<nonce>` line followed by one JSON
 *   object (fenced or bare), normalised as the hub's `report_from_value`
 *   (`service/work/report.rs`) does.
 * - A `fleet.ui/1` block: a fenced ```fleet-ui block, or a ```json block whose
 *   object says `"spec": "fleet.ui/1"`.
 *
 * Everything here is untrusted transcript text. Nothing is interpreted as
 * markup beyond MiniMarkdown, and a block that does not check out falls back
 * to the code it was written as, with what is wrong under it.
 */

const val UI_SPEC = "fleet.ui/1"
const val UI_FENCE = "fleet-ui"
/** Largest block JSON drawn as a card (bytes of the fence body). */
const val UI_MAX_BYTES = 32 * 1024

// Mirrors the hub's store/task_report.rs and work/report.rs.
val REPORT_OUTCOMES = listOf("done", "partial", "blocked", "failed")
private const val REPORT_LIST_MAX = 20
private const val REPORT_ENTRY_MAX_CHARS = 500
private const val SUMMARY_MAX_CHARS = 4000
private const val REPORT_SCAN_LINES = 400

data class TaskReport(
    val summary: String,
    val outcome: String,
    val testsRun: List<String>,
    val warnings: List<String>,
    val blockers: List<String>,
    val followups: List<String>,
    val confidence: String?,
)

val UI_TONES = listOf("info", "tip", "success", "warning", "danger")
val UI_KINDS = listOf("report", "steps", "guide", "callout", "facts", "choices", "form")

data class UiStep(val title: String, val body: String?, val code: String?, val lang: String?)
data class UiSection(val title: String, val body: String)
data class UiChoice(val label: String, val prompt: String, val hint: String?)

/** One field of a reply form: the subset of fleet.form/1 the phone draws. */
data class ReplyField(
    val name: String,
    val type: String,
    val label: String,
    val help: String?,
    val required: Boolean,
    val value: JsonElement?,
    val whenCond: JsonElement?,
    val placeholder: String?,
    val options: List<Pair<String, String>>,
    val min: Double?,
    val max: Double?,
    val integer: Boolean,
)

data class ReplyStep(val title: String, val intro: String?, val whenCond: JsonElement?, val fields: List<ReplyField>)

data class ReplyForm(val title: String, val intro: String?, val submit: String?, val steps: List<ReplyStep>)

sealed class UiBlock {
    data class Report(val title: String?, val report: TaskReport) : UiBlock()
    data class Steps(val title: String, val intro: String?, val steps: List<UiStep>) : UiBlock()
    data class Guide(val title: String, val intro: String?, val sections: List<UiSection>) : UiBlock()
    data class Callout(val tone: String, val title: String?, val body: String) : UiBlock()
    data class Facts(val title: String?, val items: List<Pair<String, String>>) : UiBlock()
    data class Choices(val title: String?, val question: String?, val options: List<UiChoice>) : UiBlock()
    data class Form(val form: ReplyForm) : UiBlock()
}

sealed class RichSegment {
    data class Md(val source: String) : RichSegment()
    data class Report(val marker: String, val report: TaskReport, val raw: String) : RichSegment()
    data class Ui(val block: UiBlock, val raw: String) : RichSegment()
    data class Invalid(val lang: String, val raw: String, val problems: List<String>) : RichSegment()
}

// ── Reports ────────────────────────────────────────────────────────────────

private fun clip(s: String, max: Int): String {
    val t = s.trim()
    // By code point, as the desktop's `[...s]` and the hub's `chars()` count.
    var count = 0
    var i = 0
    while (i < t.length && count < max) {
        i += if (t[i].isHighSurrogate() && i + 1 < t.length && t[i + 1].isLowSurrogate()) 2 else 1
        count++
    }
    return t.substring(0, i)
}

private fun codePoints(s: String): Int {
    var n = 0
    var i = 0
    while (i < s.length) {
        i += if (s[i].isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
        n++
    }
    return n
}

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun reportList(v: JsonElement?): List<String> {
    if (v == null || v is JsonNull) return emptyList()
    val items = if (v is JsonArray) v.toList() else listOf(v)
    return items
        .filter { it !is JsonNull }
        .map { clip(it.stringOrNull() ?: it.toString(), REPORT_ENTRY_MAX_CHARS) }
        .filter { it.isNotEmpty() }
        .take(REPORT_LIST_MAX)
}

/** A report from the JSON a worker printed, capped as the hub caps it. */
fun reportFromValue(v: JsonElement): TaskReport? {
    if (v !is JsonObject) return null
    val raw = v["outcome"].stringOrNull()?.trim()?.lowercase() ?: ""
    val outcome = if (raw in REPORT_OUTCOMES) raw else "partial"
    val c = v["confidence"]
    val confidence = when {
        c is JsonPrimitive && c.isString && c.content.isNotBlank() -> clip(c.content, 20)
        c is JsonPrimitive && !c.isString && c.doubleOrNull != null -> clip(c.content, 20)
        else -> null
    }
    return TaskReport(
        summary = v["summary"].stringOrNull()?.let { clip(it, SUMMARY_MAX_CHARS) } ?: "",
        outcome = outcome,
        testsRun = reportList(v["tests_run"]),
        warnings = reportList(v["warnings"]),
        blockers = reportList(v["blockers"]),
        followups = reportList(v["followups"]),
        confidence = confidence,
    )
}

// ── Lines and fences ───────────────────────────────────────────────────────

private val MARKER_RE = Regex("^FLEET_TASK_DONE_[A-Za-z0-9_]+$")

private fun isWordChar(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_'

/** The done marker on this line, or null. Leading and trailing punctuation
 *  (`⏺`, `**`) is allowed, as the hub's `after_marker` allows it. */
fun markerOf(line: String): String? {
    val bare = line.trim().dropWhile { !isWordChar(it) }.dropLastWhile { !isWordChar(it) }
    return if (MARKER_RE.matches(bare)) bare else null
}

private fun unchrome(line: String): String {
    val t = line.trimStart()
    val s = if (t.startsWith("⏺") || t.startsWith("│")) t.substring(1) else t
    return s.trimStart()
}

private class Fence(val close: Int, val lang: String, val body: String)

/** A ``` or ~~~ fence opening on line [i], with MiniMarkdown's rules: any
 *  indentation, and a backtick fence's info string holds no backtick. */
private fun fenceAt(lines: List<String>, i: Int): Fence? {
    val t = lines[i].trimStart()
    if (t.length < 3) return null
    val ch = t[0]
    if (ch != '`' && ch != '~') return null
    var k = 0
    while (k < t.length && t[k] == ch) k++
    if (k < 3) return null
    val info = t.substring(k).trim()
    if (ch == '`' && '`' in info) return null
    val lang = info.takeWhile { !it.isWhitespace() }.lowercase()
    var close = lines.size
    for (j in i + 1 until lines.size) {
        val b = lines[j].trim()
        if (b.length >= k && b.all { it == ch }) {
            close = j
            break
        }
    }
    val end = minOf(close, lines.size)
    return Fence(close, lang, lines.subList(i + 1, end).joinToString("\n"))
}

private val json = Json { isLenient = false }

/** [s] parsed, or null when it is not JSON or nests past [MAX_JSON_DEPTH]
 *  (checked first: the parser recurses, see `JsonDepth.kt`). */
private fun parseJson(s: String): JsonElement? {
    if (!nestsWithin(s, MAX_JSON_DEPTH)) return null
    return try {
        json.parseToJsonElement(s)
    } catch (e: Exception) {
        null
    }
}

private class Found(val report: TaskReport, val raw: String, val end: Int)

private fun reportAfter(lines: List<String>, i: Int): Found? {
    var k = i + 1
    while (k < lines.size && lines[k].isBlank()) k++
    if (k >= lines.size) return null
    val f = fenceAt(lines, k)
    if (f != null) {
        if (f.close >= lines.size) return null // still streaming
        val raw = f.body.trim()
        val report = parseJson(raw)?.let(::reportFromValue) ?: return null
        return Found(report, raw, f.close)
    }
    if (!unchrome(lines[k]).startsWith("{")) return null
    val last = minOf(lines.size, k + REPORT_SCAN_LINES)
    for (e in k until last) {
        if (!unchrome(lines[e]).trimEnd().endsWith("}")) continue
        val text = lines.subList(k, e + 1).joinToString("\n") { unchrome(it) }
        val v = parseJson(text) ?: continue
        val report = reportFromValue(v) ?: return null
        return Found(report, text, e)
    }
    return null
}

/**
 * Split an assistant text into Markdown runs and cards. A fence that never
 * closes (the reply is still being written) stays Markdown, so a block turns
 * into its card only once it is whole.
 */
fun splitRich(source: String): List<RichSegment> {
    if ("FLEET_TASK_DONE_" !in source && UI_FENCE !in source && UI_SPEC !in source) return listOf(RichSegment.Md(source))
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val out = mutableListOf<RichSegment>()
    val md = mutableListOf<String>()
    fun flush() {
        val s = md.joinToString("\n")
        if (s.isNotBlank()) out += RichSegment.Md(s)
        md.clear()
    }
    var i = 0
    while (i < lines.size) {
        val f = fenceAt(lines, i)
        if (f != null) {
            val closed = f.close < lines.size
            val end = minOf(f.close, lines.size - 1)
            val seg = if (closed) uiSegment(f) else null
            if (seg != null) {
                flush()
                out += seg
            } else {
                // Any other fence passes through whole, so a marker or a
                // fleet-ui fence quoted inside a code block stays code.
                md += lines.subList(i, end + 1)
            }
            i = end + 1
            continue
        }
        val marker = markerOf(lines[i])
        if (marker != null) {
            val r = reportAfter(lines, i)
            if (r != null) {
                flush()
                out += RichSegment.Report(marker, r.report, r.raw)
                i = r.end + 1
                continue
            }
        }
        md += lines[i]
        i++
    }
    flush()
    return out.ifEmpty { listOf(RichSegment.Md(source)) }
}

private fun uiSegment(f: Fence): RichSegment? {
    val raw = f.body.trim()
    if (f.lang != UI_FENCE) {
        if (f.lang != "json" || !raw.startsWith("{") || UI_SPEC !in raw) return null
        val v = parseJson(raw) as? JsonObject ?: return null
        if (v["spec"].stringOrNull() != UI_SPEC) return null
    }
    return when (val c = checkUiBlock(raw)) {
        is UiCheck.Ok -> RichSegment.Ui(c.block, raw)
        is UiCheck.Bad -> RichSegment.Invalid(f.lang, raw, c.problems)
    }
}

// ── fleet.ui/1 ─────────────────────────────────────────────────────────────

sealed class UiCheck {
    data class Ok(val block: UiBlock) : UiCheck()
    data class Bad(val problems: List<String>) : UiCheck()
}

private class Problems {
    val list = mutableListOf<String>()
    fun add(where: String, what: String) {
        if (list.size < 20) list += if (where.isNotEmpty()) "$where: $what" else what
    }
}

private fun str(o: JsonObject, key: String, p: Problems, where: String, max: Int, required: Boolean = false): String? {
    val v = o[key]
    if (v == null || v is JsonNull) {
        if (required) p.add(where, "`$key` is required")
        return null
    }
    val s = v.stringOrNull()
    if (s == null) {
        p.add(where, "`$key` must be text")
        return null
    }
    if (required && s.isBlank()) p.add(where, "`$key` is empty")
    if (codePoints(s) > max) p.add(where, "`$key` is longer than $max characters")
    return s
}

private fun arr(o: JsonObject, key: String, p: Problems, where: String, min: Int, max: Int): List<JsonElement> {
    val v = o[key]
    if (v !is JsonArray) {
        p.add(where, "`$key` must be a list")
        return emptyList()
    }
    if (v.size < min) p.add(where, "`$key` needs at least $min ${if (min == 1) "entry" else "entries"}")
    if (v.size > max) p.add(where, "`$key` has more than $max entries")
    return v.take(max)
}

private fun <T> each(items: List<JsonElement>, p: Problems, where: String, f: (JsonObject, String) -> T): List<T> =
    items.mapIndexedNotNull { k, x ->
        val at = "$where ${k + 1}"
        if (x !is JsonObject) {
            p.add(at, "must be an object")
            null
        } else {
            f(x, at)
        }
    }

private val FIELD_TYPES_SHOWN = setOf("text", "textarea", "number", "bool", "select", "multiselect")
private val NAME_RE = Regex("^[a-z][a-z0-9_]{0,39}$")

private fun number(o: JsonObject, key: String, p: Problems, where: String): Double? {
    val v = o[key] ?: return null
    val d = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    if (d == null || !d.isFinite()) p.add(where, "`$key` must be a number")
    return d
}

/** Enough of fleet.form/1 to draw it safely; a secret is refused, its answer
 *  would land in the transcript. Mirrors `checkForm` in rich_blocks.ts. */
private fun checkForm(v: JsonElement?, p: Problems): ReplyForm? {
    val where = "form"
    if (v !is JsonObject) {
        p.add(where, "must be a fleet.form/1 object")
        return null
    }
    if (v["spec"].stringOrNull() != "fleet.form/1") p.add(where, "`spec` must be \"fleet.form/1\"")
    val title = str(v, "title", p, where, 120, required = true) ?: ""
    val intro = str(v, "intro", p, where, 500)
    val submit = str(v, "submit", p, where, 40)
    val names = mutableSetOf<String>()
    var fields = 0
    val steps = each(arr(v, "steps", p, where, 1, 12), p, "form › step") { s, at ->
        val stitle = str(s, "title", p, at, 120, required = true) ?: ""
        val sintro = str(s, "intro", p, at, 500)
        val fs = each(arr(s, "fields", p, at, 1, 40), p, "$at › field") { f, fat ->
            fields++
            val name = str(f, "name", p, fat, 40, required = true)
            if (name != null) {
                if (!NAME_RE.matches(name)) p.add(fat, "name \"$name\" must be lowercase letters, digits and _")
                if (name in names) p.add(fat, "name \"$name\" appears twice")
                names += name
            }
            val label = str(f, "label", p, fat, 200, required = true) ?: ""
            val help = str(f, "help", p, fat, 500)
            val placeholder = str(f, "placeholder", p, fat, 200)
            val type = f["type"].stringOrNull()
            if (type == "secret") {
                p.add(fat, "a secret field is only for `ask`: its answer would land in the transcript")
            } else if (type == null || type !in FIELD_TYPES_SHOWN) {
                p.add(fat, "type ${f["type"] ?: "undefined"} is not a field type")
            }
            val options = if (type == "select" || type == "multiselect") {
                arr(f, "options", p, fat, 1, 50).mapIndexedNotNull { k, o ->
                    val pair = (o as? JsonArray)?.takeIf { it.size == 2 }
                    val a = pair?.get(0).stringOrNull()
                    val b = pair?.get(1).stringOrNull()
                    if (a == null || b == null) {
                        p.add("$fat › option ${k + 1}", "must be [value, label]")
                        null
                    } else {
                        a to b
                    }
                }
            } else {
                emptyList()
            }
            val min = number(f, "min", p, fat)
            val max = number(f, "max", p, fat)
            number(f, "max_len", p, fat)
            ReplyField(
                name = name ?: "",
                type = type ?: "",
                label = label,
                help = help,
                required = (f["required"] as? JsonPrimitive)?.booleanOrNull == true,
                value = f["value"],
                whenCond = f["when"],
                placeholder = placeholder,
                options = options,
                min = min,
                max = max,
                integer = (f["integer"] as? JsonPrimitive)?.booleanOrNull == true,
            )
        }
        ReplyStep(stitle, sintro, s["when"], fs)
    }
    if (fields > 40) p.add(where, "has more than 40 fields")
    return if (p.list.isEmpty()) ReplyForm(title, intro, submit, steps) else null
}

/** A fleet.ui/1 block from its JSON text, or every problem with it. */
fun checkUiBlock(raw: String): UiCheck {
    val p = Problems()
    if (raw.encodeToByteArray().size > UI_MAX_BYTES) return UiCheck.Bad(listOf("is larger than ${UI_MAX_BYTES / 1024} KiB"))
    val v = parseJson(raw) ?: return UiCheck.Bad(listOf("is not valid JSON"))
    if (v !is JsonObject) return UiCheck.Bad(listOf("must be a JSON object"))
    if (v["spec"].stringOrNull() != UI_SPEC) p.add("", "`spec` must be \"$UI_SPEC\"")
    val kind = v["kind"].stringOrNull()
    if (kind == null || kind !in UI_KINDS) {
        p.add("", "`kind` must be one of ${UI_KINDS.joinToString(", ")}")
        return UiCheck.Bad(p.list)
    }
    val block: UiBlock? = when (kind) {
        "report" -> {
            val title = str(v, "title", p, "", 120)
            reportFromValue(v)?.let { UiBlock.Report(title, it) }
        }
        "steps" -> {
            val title = str(v, "title", p, "", 120, required = true) ?: ""
            val intro = str(v, "intro", p, "", 2000)
            val steps = each(arr(v, "steps", p, "", 1, 30), p, "step") { s, at ->
                UiStep(
                    title = str(s, "title", p, at, 200, required = true) ?: "",
                    body = str(s, "body", p, at, 4000),
                    code = str(s, "code", p, at, 8000),
                    lang = str(s, "lang", p, at, 20),
                )
            }
            UiBlock.Steps(title, intro, steps)
        }
        "guide" -> {
            val title = str(v, "title", p, "", 120, required = true) ?: ""
            val intro = str(v, "intro", p, "", 2000)
            val sections = each(arr(v, "sections", p, "", 1, 20), p, "section") { s, at ->
                UiSection(str(s, "title", p, at, 200, required = true) ?: "", str(s, "body", p, at, 8000, required = true) ?: "")
            }
            UiBlock.Guide(title, intro, sections)
        }
        "callout" -> {
            val toneEl = v["tone"]
            val tone = if (toneEl == null) "info" else toneEl.stringOrNull()
            if (tone == null || tone !in UI_TONES) p.add("", "`tone` must be one of ${UI_TONES.joinToString(", ")}")
            UiBlock.Callout(tone ?: "info", str(v, "title", p, "", 120), str(v, "body", p, "", 4000, required = true) ?: "")
        }
        "facts" -> {
            val items = arr(v, "items", p, "", 1, 40).mapIndexedNotNull { k, x ->
                val pair = (x as? JsonArray)?.takeIf { it.size == 2 }
                val label = pair?.get(0).stringOrNull()
                val value = (pair?.get(1) as? JsonPrimitive)?.takeIf { it !is JsonNull }
                if (label == null || value == null || (!value.isString && value.doubleOrNull == null && value.booleanOrNull == null)) {
                    p.add("item ${k + 1}", "must be [label, value]")
                    null
                } else {
                    clip(label, 120) to clip(value.content, 1000)
                }
            }
            UiBlock.Facts(str(v, "title", p, "", 120), items)
        }
        "choices" -> {
            val options = each(arr(v, "options", p, "", 1, 8), p, "option") { o, at ->
                UiChoice(
                    label = str(o, "label", p, at, 80, required = true) ?: "",
                    prompt = str(o, "prompt", p, at, 4000, required = true) ?: "",
                    hint = str(o, "hint", p, at, 200),
                )
            }
            UiBlock.Choices(str(v, "title", p, "", 120), str(v, "question", p, "", 500), options)
        }
        else -> checkForm(v["form"], p)?.let { UiBlock.Form(it) }
    }
    return if (p.list.isEmpty() && block != null) UiCheck.Ok(block) else UiCheck.Bad(p.list.ifEmpty { listOf("is not a block") })
}

// ── Reply forms ────────────────────────────────────────────────────────────

/** Whether condition [c] holds for the values shown so far. A port of
 *  `holds` in the desktop's form_model.ts. Its depth is bounded by
 *  [MAX_JSON_DEPTH], checked when the block was parsed. */
fun fieldHolds(c: JsonElement?, shown: Map<String, JsonElement>): Boolean {
    if (c !is JsonObject) return true
    (c["all"] as? JsonArray)?.let { all -> return all.all { fieldHolds(it, shown) } }
    (c["any"] as? JsonArray)?.let { any -> return any.any { fieldHolds(it, shown) } }
    c["not"]?.let { return !fieldHolds(it, shown) }
    val field = c["field"].stringOrNull() ?: return true
    val got = shown[field]
    fun matches(want: JsonElement): Boolean =
        if (got is JsonArray) got.any { it == want } else got != null && got == want
    c["eq"]?.let { return matches(it) }
    (c["in"] as? JsonArray)?.let { list -> return list.any { matches(it) } }
    (c["truthy"] as? JsonPrimitive)?.booleanOrNull?.let { want ->
        return ((got as? JsonPrimitive)?.booleanOrNull == true) == want
    }
    return true
}

/** The steps asked, given the answers so far; a field's own `when` filters
 *  its step's fields. Values only count for fields still shown. */
fun visibleFields(form: ReplyForm, values: Map<String, JsonElement>): List<Pair<ReplyStep, List<ReplyField>>> {
    val shown = mutableMapOf<String, JsonElement>()
    val out = mutableListOf<Pair<ReplyStep, List<ReplyField>>>()
    for (step in form.steps) {
        if (!fieldHolds(step.whenCond, shown)) continue
        val fs = mutableListOf<ReplyField>()
        for (f in step.fields) {
            if (!fieldHolds(f.whenCond, shown)) continue
            fs += f
            values[f.name]?.let { shown[f.name] = it }
        }
        out += step to fs
    }
    return out
}

/** Each field's default, as the desktop's wizard starts from. */
fun formDefaults(form: ReplyForm): Map<String, JsonElement> =
    form.steps.flatMap { it.fields }.mapNotNull { f -> f.value?.takeIf { it !is JsonNull }?.let { f.name to it } }.toMap()

/** Whether a shown field still needs an answer before the form can be sent. */
fun fieldMissing(f: ReplyField, v: JsonElement?): Boolean {
    if (!f.required) return false
    return when {
        v == null || v is JsonNull -> true
        f.type == "bool" -> (v as? JsonPrimitive)?.booleanOrNull != true
        v is JsonArray -> v.isEmpty()
        v is JsonPrimitive && v.isString -> v.content.isBlank()
        else -> false
    }
}

/** The prompt a submitted reply form fills the composer with: the title and
 *  the answers of the shown fields, in form order, as one JSON block. The
 *  desktop's `formAnswerPrompt` in shape. */
fun formAnswerPrompt(form: ReplyForm, values: Map<String, JsonElement>): String {
    val answers = JsonObject(
        visibleFields(form, values).flatMap { it.second }.mapNotNull { f -> values[f.name]?.let { f.name to it } }.toMap(),
    )
    return "Answers to the form \"${form.title}\":\n\n```json\n${pretty.encodeToString(JsonElement.serializer(), answers)}\n```"
}

/** Two-space indent, as the desktop's `JSON.stringify(values, null, 2)`. */
@OptIn(ExperimentalSerializationApi::class)
private val pretty = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** [raw] as a fenced code block whose fence no backtick run inside can close. */
fun fenced(lang: String, raw: String): String {
    var longest = 2
    var run = 0
    for (c in raw) {
        run = if (c == '`') run + 1 else 0
        if (run > longest) longest = run
    }
    val bar = "`".repeat(longest + 1)
    return "$bar$lang\n$raw\n$bar"
}
