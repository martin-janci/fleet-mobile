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
val UI_KINDS = listOf("report", "steps", "guide", "callout", "facts", "choices", "form", "progress", "results", "error", "setting")

val PROGRESS_STATES = listOf("running", "waiting", "done", "failed")
val PROGRESS_STEP_STATES = listOf("pending", "running", "done", "failed", "skipped")

/** A value's type in a results card: the desktop's page column types, drawn by [formatResultCell]. */
val RESULT_TYPES = listOf("text", "int", "tokens", "usd_micros", "day", "time")
val RESULT_CHARTS = listOf("line", "bar", "sparkline")
val RESULT_ITEM_TYPES = listOf("stat", "chart", "table")

/** A progress `id`, an error `code` and a guide's `page`: a key, never prose. */
private val KEY_RE = Regex("^[A-Za-z0-9_.:-]+$")
private const val KEY_MAX = 64

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
    /** select, multiselect: an option's detail line by its value, from a `{value, label, detail}` option (contract 15). */
    val optionDetails: Map<String, String> = emptyMap(),
    /** select: "Another…", free text beside the options (contract 15). */
    val other: Boolean = false,
    /** Shown but not answerable, with this reason under it (contract 15); never required, never in the answers. */
    val disabledReason: String? = null,
    /** The default was drafted by an AI: "Drafted by [draftedBy] from [draftedFrom]" (contract 15). */
    val draftedBy: String? = null,
    val draftedFrom: String? = null,
    /** secret: where the value goes, shown under the field (contract 15). */
    val secretNote: String? = null,
)

data class ReplyStep(
    val title: String,
    val intro: String?,
    val whenCond: JsonElement?,
    val fields: List<ReplyField>,
    /** The step's short name for its chip (contract 15); the title when null. */
    val name: String? = null,
    /** `kind: "review"` (contract 15): no fields of its own, a summary of the answers so far ([reviewLines]). */
    val review: Boolean = false,
)

data class ReplyForm(
    val title: String,
    val intro: String?,
    val submit: String?,
    val steps: List<ReplyStep>,
    /** "Save and finish later" (contract 15). The phone keeps no draft, so it is read and not offered. */
    val saveLater: Boolean = false,
)

data class ProgressStep(val title: String, val state: String)

/** One axis of a chart, or one column of a table: its label and, optionally, its type. */
data class ResultAxis(val label: String, val ty: String? = null)

sealed class ResultItem {
    /** [value] is a number ([Double]) or text. */
    data class Stat(val label: String, val value: Any, val ty: String?, val hint: String?) : ResultItem()
    /** Each point's x is text or a number ([Double]). */
    data class Chart(val chart: String, val title: String, val x: ResultAxis, val y: ResultAxis, val points: List<Pair<Any, Double>>) : ResultItem()
    /** A cell is text, a number, a bool or null, as the block wrote it. */
    data class Table(val title: String?, val columns: List<ResultAxis>, val rows: List<List<JsonElement>>) : ResultItem()
}

sealed class UiBlock {
    data class Report(val title: String?, val report: TaskReport) : UiBlock()
    data class Steps(val title: String, val intro: String?, val steps: List<UiStep>) : UiBlock()
    data class Guide(val title: String, val intro: String?, val sections: List<UiSection>) : UiBlock()
    /** A guide fleet already has, by its page id (step 10.4): Settings › Guides draws it. */
    data class GuidePage(val page: String) : UiBlock()
    data class Callout(val tone: String, val title: String?, val body: String) : UiBlock()
    data class Facts(val title: String?, val items: List<Pair<String, String>>) : UiBlock()
    data class Choices(val title: String?, val question: String?, val options: List<UiChoice>) : UiBlock()
    data class Form(val form: ReplyForm) : UiBlock()
    /** A long job's state. Blocks with the same [id] in one conversation are one card ([progressBoard]). */
    data class Progress(
        val id: String,
        val title: String,
        val state: String,
        val done: Long?,
        val total: Long?,
        val unit: String?,
        val steps: List<ProgressStep>?,
        val note: String?,
    ) : UiBlock()
    data class Results(val title: String?, val summary: String?, val items: List<ResultItem>) : UiBlock()
    data class Error(val code: String, val title: String, val body: String?, val detail: String?, val next: List<UiChoice>) : UiBlock()
    /** A settings change an agent proposed; the card reads the key and values from the proposal, never from here. */
    data class Setting(val proposal: Long, val note: String?) : UiBlock()
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

/** An optional bool: absent or null is false. */
private fun optBool(o: JsonObject, key: String, p: Problems, where: String): Boolean {
    val v = o[key]
    if (v == null || v is JsonNull) return false
    val b = (v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    if (b == null) p.add(where, "`$key` must be true or false")
    return b == true
}

private fun number(o: JsonObject, key: String, p: Problems, where: String): Double? {
    val v = o[key] ?: return null
    val d = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    if (d == null || !d.isFinite()) p.add(where, "`$key` must be a number")
    return d
}

/** A whole number of at least [min], as the desktop's `num`; absent is null. */
private fun whole(o: JsonObject, key: String, p: Problems, where: String, min: Long): Long? {
    val v = o[key]
    if (v == null || v is JsonNull) return null
    val d = (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    if (d == null || !d.isFinite()) {
        p.add(where, "`$key` must be a number")
        return null
    }
    if (d != kotlin.math.floor(d)) {
        p.add(where, "`$key` must be a whole number")
    } else if (d < min) {
        p.add(where, "`$key` must be at least $min")
    }
    return d.toLong()
}

private fun oneOf(o: JsonObject, key: String, p: Problems, where: String, allowed: List<String>, fallback: String? = null): String? {
    val raw = o[key]?.takeIf { it !is JsonNull }
    if (raw == null && fallback == null) {
        p.add(where, "`$key` is required")
        return null
    }
    val v = if (raw == null) fallback else raw.stringOrNull()
    if (v == null || v !in allowed) {
        p.add(where, "`$key` must be one of ${allowed.joinToString(", ")}")
        return null
    }
    return v
}

private fun key(o: JsonObject, name: String, p: Problems, where: String): String {
    val v = str(o, name, p, where, KEY_MAX, required = true) ?: ""
    if (v.isNotEmpty() && !KEY_RE.matches(v)) p.add(where, "`$name` must be letters, digits and . _ : -")
    return v
}

private fun optArr(o: JsonObject, k: String, p: Problems, where: String, min: Int, max: Int): List<JsonElement>? =
    if (o[k] == null || o[k] is JsonNull) null else arr(o, k, p, where, min, max)

private fun axis(v: JsonElement?, p: Problems, where: String): ResultAxis {
    if (v !is JsonObject) {
        p.add(where, "must be an object")
        return ResultAxis("")
    }
    val ty = if (v["ty"] == null || v["ty"] is JsonNull) null else oneOf(v, "ty", p, where, RESULT_TYPES)
    return ResultAxis(str(v, "label", p, where, 80, required = true) ?: "", ty)
}

/** A JSON number, finite; text, a bool and null are not. */
private fun finiteNumber(e: JsonElement?): Double? =
    (e as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }?.doubleOrNull?.takeIf { it.isFinite() }

private fun isCell(c: JsonElement): Boolean =
    c is JsonNull || (c is JsonPrimitive && (c.isString || c.booleanOrNull != null || finiteNumber(c) != null))

private fun choice(o: JsonObject, p: Problems, at: String) = UiChoice(
    label = str(o, "label", p, at, 80, required = true) ?: "",
    prompt = str(o, "prompt", p, at, 4000, required = true) ?: "",
    hint = str(o, "hint", p, at, 200),
)

private fun resultItem(o: JsonObject, p: Problems, at: String): ResultItem? = when (o["type"].stringOrNull()) {
    "stat" -> {
        val label = str(o, "label", p, at, 80, required = true) ?: ""
        val v = o["value"]
        val n = finiteNumber(v)
        val text = v.stringOrNull()
        val value: Any = when {
            n != null -> n
            text != null && codePoints(text) <= 80 -> text
            else -> {
                p.add(at, "`value` must be a number or text of at most 80 characters")
                ""
            }
        }
        val ty = if (o["ty"] == null || o["ty"] is JsonNull) null else oneOf(o, "ty", p, at, RESULT_TYPES)
        ResultItem.Stat(label, value, ty, str(o, "hint", p, at, 200))
    }
    "chart" -> {
        val chart = oneOf(o, "chart", p, at, RESULT_CHARTS) ?: "bar"
        val title = str(o, "title", p, at, 120, required = true) ?: ""
        val x = axis(o["x"], p, "$at › x")
        val y = axis(o["y"], p, "$at › y")
        val points = arr(o, "points", p, at, 1, 200).mapIndexedNotNull { k, pt ->
            val pair = (pt as? JsonArray)?.takeIf { it.size == 2 }
            val px: Any? = pair?.get(0)?.let { it.stringOrNull() ?: finiteNumber(it) }
            val py = pair?.get(1)?.let(::finiteNumber)
            if (px == null || py == null) {
                p.add("$at › point ${k + 1}", "must be [x, number]")
                null
            } else {
                px to py
            }
        }
        ResultItem.Chart(chart, title, x, y, points)
    }
    "table" -> {
        val title = str(o, "title", p, at, 120)
        val columns = arr(o, "columns", p, at, 1, 12).mapIndexed { k, c -> axis(c, p, "$at › column ${k + 1}") }
        val rows = arr(o, "rows", p, at, 0, 200).mapIndexedNotNull { k, r ->
            val rat = "$at › row ${k + 1}"
            if (r !is JsonArray) {
                p.add(rat, "must be a list")
                null
            } else {
                if (r.size != columns.size) p.add(rat, "has ${r.size} cells for ${columns.size} columns")
                r.forEachIndexed { j, c -> if (!isCell(c)) p.add(rat, "cell ${j + 1} must be text, a number, a bool or null") }
                r.toList()
            }
        }
        ResultItem.Table(title, columns, rows)
    }
    else -> {
        p.add(at, "`type` must be one of ${RESULT_ITEM_TYPES.joinToString(", ")}")
        null
    }
}

/** Enough of fleet.form/1 to draw it safely; a secret is refused, its answer
 *  would land in the transcript. Mirrors `checkForm` in rich_blocks.ts.
 *  [ask]: a form an agent's `ask` opened ([readAskForm]), which may hold a
 *  secret, since its answers go to the hub and never into the transcript. */
private fun checkForm(v: JsonElement?, p: Problems, ask: Boolean = false): ReplyForm? {
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
    val saveLater = optBool(v, "save_later", p, where)
    val steps = each(arr(v, "steps", p, where, 1, 12), p, "form › step") { s, at ->
        val stitle = str(s, "title", p, at, 120, required = true) ?: ""
        val sintro = str(s, "intro", p, at, 500)
        val sname = str(s, "name", p, at, 24)
        val kind = str(s, "kind", p, at, 20)
        if (kind != null && kind != "fields" && kind != "review") p.add(at, "`kind` must be fields or review")
        // A review step (contract 15) has no fields: it summarises the steps before it.
        val review = kind == "review"
        val rawFields = if (review && (s["fields"] == null || s["fields"] is JsonNull)) emptyList<JsonElement>() else arr(s, "fields", p, at, if (review) 0 else 1, 40)
        if (review && rawFields.isNotEmpty()) p.add(at, "a review step has no fields")
        val fs = each(rawFields, p, "$at › field") { f, fat ->
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
            if (type == "secret" && ask) {
                // The hub writes it to a file on the session's host.
            } else if (type == "secret") {
                p.add(fat, "a secret field is only for `ask`: its answer would land in the transcript")
            } else if (type == null || type !in FIELD_TYPES_SHOWN) {
                p.add(fat, "type ${f["type"] ?: "undefined"} is not a field type")
            }
            val details = mutableMapOf<String, String>()
            val options = if (type == "select" || type == "multiselect") {
                arr(f, "options", p, fat, 1, 50).mapIndexedNotNull { k, o ->
                    // `[value, label]`, or `{value, label, detail?, proposed?}` (contract 15).
                    val pair = (o as? JsonArray)?.takeIf { it.size == 2 }
                    val obj = o as? JsonObject
                    val a = if (obj != null) obj["value"].stringOrNull() else pair?.get(0).stringOrNull()
                    val b = if (obj != null) obj["label"].stringOrNull() else pair?.get(1).stringOrNull()
                    if (a == null || b == null) {
                        p.add("$fat › option ${k + 1}", "must be [value, label]")
                        null
                    } else {
                        if (obj != null) str(obj, "detail", p, "$fat › option ${k + 1}", 200)?.let { details[a] = it }
                        a to b
                    }
                }
            } else {
                emptyList()
            }
            val drafted = f["drafted"] as? JsonObject
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
                optionDetails = details,
                other = optBool(f, "other", p, fat),
                disabledReason = str(f, "disabled_reason", p, fat, 500),
                draftedBy = drafted?.let { str(it, "by", p, "$fat › drafted", 200) },
                draftedFrom = drafted?.let { str(it, "from", p, "$fat › drafted", 200) },
                secretNote = str(f, "secret_note", p, fat, 500),
            )
        }
        ReplyStep(stitle, sintro, s["when"], fs, name = sname, review = review)
    }
    if (fields > 40) p.add(where, "has more than 40 fields")
    return if (p.list.isEmpty()) ReplyForm(title, intro, submit, steps, saveLater = saveLater) else null
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
        "guide" -> if (v["page"] != null && v["page"] !is JsonNull) {
            UiBlock.GuidePage(key(v, "page", p, ""))
        } else {
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
            val options = each(arr(v, "options", p, "", 1, 8), p, "option") { o, at -> choice(o, p, at) }
            UiBlock.Choices(str(v, "title", p, "", 120), str(v, "question", p, "", 500), options)
        }
        "form" -> checkForm(v["form"], p)?.let { UiBlock.Form(it) }
        "progress" -> {
            val id = key(v, "id", p, "")
            val title = str(v, "title", p, "", 120, required = true) ?: ""
            val state = oneOf(v, "state", p, "", PROGRESS_STATES, "running") ?: "running"
            val done = whole(v, "done", p, "", 0)
            val total = whole(v, "total", p, "", 1)
            if (done != null && total != null && done > total) p.add("", "`done` is more than `total`")
            val steps = optArr(v, "steps", p, "", 1, 20)?.let { list ->
                each(list, p, "step") { s, at ->
                    ProgressStep(
                        str(s, "title", p, at, 200, required = true) ?: "",
                        oneOf(s, "state", p, at, PROGRESS_STEP_STATES, "pending") ?: "pending",
                    )
                }
            }
            UiBlock.Progress(id, title, state, done, total, str(v, "unit", p, "", 20), steps, str(v, "note", p, "", 2000))
        }
        "results" -> {
            val title = str(v, "title", p, "", 120)
            val summary = str(v, "summary", p, "", 2000)
            val items = each(arr(v, "items", p, "", 1, 12), p, "item") { o, at -> resultItem(o, p, at) }.filterNotNull()
            UiBlock.Results(title, summary, items)
        }
        "error" -> {
            val code = key(v, "code", p, "")
            val title = str(v, "title", p, "", 120, required = true) ?: ""
            val next = each(optArr(v, "next", p, "", 1, 4) ?: emptyList(), p, "next") { o, at -> choice(o, p, at) }
            UiBlock.Error(code, title, str(v, "body", p, "", 4000), str(v, "detail", p, "", 8000), next)
        }
        else -> {
            var proposal: Long? = null
            if (v["proposal"] == null || v["proposal"] is JsonNull) p.add("", "`proposal` is required") else proposal = whole(v, "proposal", p, "", 1)
            UiBlock.Setting(proposal ?: 0, str(v, "note", p, "", 500))
        }
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
            // A disabled field (contract 15) is shown with its reason, never answered.
            if (f.disabledReason != null) continue
            values[f.name]?.let { shown[f.name] = it }
        }
        out += step to fs
    }
    return out
}

/**
 * Each field's default, as the desktop's wizard starts from, less what AI
 * never decides (review r09 B17, R15): a required bool is a consent box and
 * starts unticked, and a bool, choice or multi-choice option that approves,
 * allows or does something hard to undo ([riskyChoice]) is not pre-picked,
 * whatever default the agent wrote. A person ticks those.
 */
fun formDefaults(form: ReplyForm): Map<String, JsonElement> =
    form.steps.flatMap { it.fields }.filter { it.disabledReason == null }.mapNotNull { f ->
        f.value?.takeIf { it !is JsonNull }?.let { agentDefault(f, it) }?.let { f.name to it }
    }.toMap()

/** The words that make an option one AI never pre-picks: the desktop's
 *  `RISKY_WORDS` (src/lib/quick_answer.ts, J5), which the hub's
 *  `decide/quick_answer.rs` keeps too. */
internal val RISKY_WORDS = listOf(
    "push", "force", "delete", "remove", "drop", "destroy", "wipe", "reset", "overwrite", "rm", "kill",
    "deploy", "publish", "release", "merge", "rebase", "revert", "truncate", "purge", "production", "prod",
    "allow", "always", "bypass", "permission", "permissions", "sudo", "approve",
)

private val RISKY = Regex("""\b(?:${RISKY_WORDS.joinToString("|")})\b|don['’]?t ask again""", RegexOption.IGNORE_CASE)

/** An option AI never picks for a person: a push, a permission, an approval, a step hard to undo. The desktop's `risky`. */
fun riskyChoice(label: String): Boolean = RISKY.containsMatchIn(label)

/** [v], the agent's default for [f], unless it would decide something for the person. */
private fun agentDefault(f: ReplyField, v: JsonElement): JsonElement? {
    fun risky(value: String): Boolean = riskyChoice(value) || f.options.any { it.first == value && riskyChoice(it.second) }
    return when (f.type) {
        "bool" -> v.takeUnless { (it as? JsonPrimitive)?.booleanOrNull == true && (f.required || riskyChoice(f.label)) }
        "select" -> v.takeUnless { it is JsonPrimitive && it.isString && risky(it.content) }
        "multiselect" -> if (v is JsonArray) {
            JsonArray(v.filterNot { it is JsonPrimitive && it.isString && risky(it.content) }).takeIf { it.isNotEmpty() }
        } else {
            v
        }
        else -> v
    }
}

/** Whether a shown field still needs an answer before the form can be sent. */
fun fieldMissing(f: ReplyField, v: JsonElement?): Boolean {
    if (!f.required || f.disabledReason != null) return false
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
    val answers = JsonObject(answerable(form, values).mapNotNull { f -> values[f.name]?.let { f.name to it } }.toMap())
    return "Answers to the form \"${form.title}\":\n\n```json\n${pretty.encodeToString(JsonElement.serializer(), answers)}\n```"
}

/** The shown fields a person answers, in form order: never a disabled one (contract 15). */
fun answerable(form: ReplyForm, values: Map<String, JsonElement>): List<ReplyField> =
    visibleFields(form, values).flatMap { it.second }.filter { it.disabledReason == null }

/**
 * What a review step (contract 15) shows: each answered field of the shown
 * steps before [reviewStep], as label and value in words — an option's
 * label, "Yes"/"No", a list joined, a secret hidden. The desktop's review
 * step, without its Edit links (the phone's Back walks there).
 */
fun reviewLines(form: ReplyForm, values: Map<String, JsonElement>, reviewStep: ReplyStep): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    for ((step, fields) in visibleFields(form, values)) {
        if (step === reviewStep) break
        for (f in fields) {
            if (f.disabledReason != null) continue
            val v = values[f.name] ?: continue
            if (v is JsonNull) continue
            out += f.label to answerWords(f, v)
        }
    }
    return out
}

private fun answerWords(f: ReplyField, v: JsonElement): String {
    fun option(x: String) = f.options.firstOrNull { it.first == x }?.second ?: x
    return when {
        f.type == "secret" -> "••••••"
        v is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.let(::option) }.joinToString(", ")
        f.type == "bool" -> if ((v as? JsonPrimitive)?.booleanOrNull == true) "Yes" else "No"
        v is JsonPrimitive -> option(v.content)
        else -> v.toString()
    }
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

/**
 * The form an agent's `ask` opened, from the spec `ask { get }` answered. The
 * hub checked it in full before it stored it (`pages/forms.rs`), so this is
 * only the phone's reading of it; null when the phone cannot draw it.
 */
fun readAskForm(spec: JsonElement?): ReplyForm? = checkForm(spec, Problems(), ask = true)

// ── Progress that updates in place ─────────────────────────────────────────

/**
 * The `progress` blocks of one conversation by id, in document order, each
 * with the fence text it came from. An agent reports a long job by writing a
 * block with the same id again as the job moves on: the first card of an id
 * shows the newest state, and the later ones draw as one line pointing up
 * (the desktop's `rich/progress_board.ts`).
 */
class ProgressBoard(private val byId: Map<String, List<Pair<String, UiBlock.Progress>>>) {
    /** Whether the card drawn from [raw] is its id's first, the one that shows the newest state. */
    fun home(id: String, raw: String): Boolean = byId[id]?.firstOrNull()?.first?.let { it == raw } ?: true

    /** What the first card of [id] shows: the newest block of it. */
    fun newest(id: String): UiBlock.Progress? = byId[id]?.lastOrNull()?.second

    /** How many blocks came after the first. */
    fun updates(id: String): Int = ((byId[id]?.size ?: 1) - 1).coerceAtLeast(0)

    companion object {
        val EMPTY = ProgressBoard(emptyMap())
    }
}

/** The board of [texts], an assistant's texts in document order (oldest first). */
fun progressBoard(texts: List<String>): ProgressBoard {
    val out = linkedMapOf<String, MutableList<Pair<String, UiBlock.Progress>>>()
    for (t in texts) {
        if ("progress" !in t) continue
        for (seg in splitRich(t)) {
            val b = (seg as? RichSegment.Ui)?.block as? UiBlock.Progress ?: continue
            out.getOrPut(b.id) { mutableListOf() } += seg.raw to b
        }
    }
    return ProgressBoard(out)
}

// ── Results ────────────────────────────────────────────────────────────────

/**
 * A number as a results card draws it: the desktop's `formatCell` for the
 * page column types. [now] is unix seconds, for a `time` value.
 */
fun formatResultCell(ty: String, v: JsonElement?, now: Long): String {
    if (ty == "time" && (v == null || v is JsonNull)) return "never"
    if (v == null || v is JsonNull) return "—"
    val prim = v as? JsonPrimitive ?: return v.toString()
    val n = if (prim.isString) prim.content.toDoubleOrNull() else prim.doubleOrNull
    if (ty == "time" && n != null) {
        val d = maxOf(0L, now - n.toLong())
        return when {
            d < 60 -> "just now"
            d < 3600 -> "${d / 60} min ago"
            d < 86_400 -> "${d / 3600} h ago"
            else -> "${d / 86_400} d ago"
        }
    }
    return when {
        ty == "usd_micros" && n != null -> {
            val usd = n / 1_000_000
            "$" + if (usd >= 100) fixed(usd, 0) else fixed(usd, 2)
        }
        ty == "tokens" && n != null -> when {
            n >= 1_000_000 -> fixed(n / 1_000_000, 1) + "M"
            n >= 1_000 -> fixed(n / 1_000, 1) + "k"
            else -> plain(n)
        }
        ty == "int" && n != null -> grouped(n)
        prim.isString -> prim.content
        prim.booleanOrNull != null -> prim.content
        n != null -> plain(n)
        else -> prim.content
    }
}

/** [d] with [places] decimals, rounded half away from zero, as `toFixed` reads for these sizes. */
private fun fixed(d: Double, places: Int): String {
    var scale = 1L
    repeat(places) { scale *= 10 }
    val r = kotlin.math.round(kotlin.math.abs(d) * scale).toLong()
    val sign = if (d < 0 && r != 0L) "-" else ""
    val whole = r / scale
    if (places == 0) return "$sign$whole"
    return "$sign$whole." + (r % scale).toString().padStart(places, '0')
}

/** A whole number plainly, a fraction as JavaScript's `String(n)` would. */
private fun plain(d: Double): String =
    if (d == kotlin.math.floor(d) && kotlin.math.abs(d) < 1e15) d.toLong().toString() else d.toString()

/** `toLocaleString('en-US')`: thousands with commas, at most three decimals. */
private fun grouped(d: Double): String {
    val neg = d < 0
    val a = kotlin.math.abs(d)
    val s = fixed(a, 3).trimEnd('0').trimEnd('.')
    val whole = s.substringBefore('.')
    val frac = s.substringAfter('.', "")
    val withCommas = whole.reversed().chunked(3).joinToString(",").reversed()
    return (if (neg) "-" else "") + withCommas + if (frac.isNotEmpty()) ".$frac" else ""
}
