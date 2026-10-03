package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.net.MAX_JSON_DEPTH
import dev.claudefleet.mobile.net.nestsWithin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The pure half of a conversation's tool rows: what a call is called, what it
 * was about, how consecutive calls fold, and how an expanded call's detail is
 * read (a line diff, `cat -n` output, a todo list). Everything here is plain
 * data in and out so `ToolCallsTest` covers it on every host; the composables
 * that draw it are in `ConversationTools.kt`.
 *
 * The verb table and the short-target rule port the desktop's
 * `src/lib/conversation.ts` (`toolVerb`, `shortTarget`, `toolGroupLabel`), so
 * the phone and the desktop say the same thing about the same call.
 */

/** Which detail layout, and which icon, a tool call gets. */
internal enum class ToolKind { Edit, Bash, Read, Search, Todo, Other }

/**
 * The call's tool name: the hub's `name`, else read out of a one-liner such as
 * `Bash(command=ls)` — what an older hub sends, with no `name` at all.
 */
internal fun ConvItem.Tool.toolName(): String = name.ifBlank { nameFromSummary(summary) }

internal fun nameFromSummary(summary: String): String {
    val paren = summary.indexOf('(')
    return if (paren > 0) summary.substring(0, paren).trim() else summary.trim()
}

/**
 * What the call was about. A current hub says so in `target` (possibly
 * nothing, for a TodoWrite); an older one only has the one-liner, whose
 * parenthesised part is the target with its argument name in front.
 */
internal fun ConvItem.Tool.toolTarget(): String? {
    if (name.isNotBlank()) return target?.takeIf { it.isNotBlank() }
    val open = summary.indexOf('(')
    val close = summary.lastIndexOf(')')
    if (open <= 0 || close <= open) return null
    val inner = summary.substring(open + 1, close).trim()
    return inner.replaceFirst(ARG_NAME, "").trim().takeIf { it.isNotBlank() }
}

private val ARG_NAME = Regex("""^[A-Za-z_][A-Za-z0-9_]*=""")

internal fun toolKind(name: String): ToolKind = when (name) {
    "Edit", "MultiEdit", "Write", "NotebookEdit" -> ToolKind.Edit
    "Bash" -> ToolKind.Bash
    "Read" -> ToolKind.Read
    "Grep", "Glob" -> ToolKind.Search
    "TodoWrite" -> ToolKind.Todo
    else -> ToolKind.Other
}

private val TOOL_VERBS = mapOf(
    "Read" to "Read",
    "Edit" to "Edit",
    "MultiEdit" to "Edit",
    "Write" to "Write",
    "Bash" to "Run",
    "Grep" to "Search",
    "Glob" to "Find",
    "WebFetch" to "Fetch",
    "WebSearch" to "Search web",
    "TodoWrite" to "Update todos",
)

/** The short verb a tool row leads with; `mcp__srv__tool` → `srv · tool`. */
internal fun toolVerb(name: String): String {
    TOOL_VERBS[name]?.let { return it }
    if (name.startsWith("mcp__")) {
        val parts = name.removePrefix("mcp__").split("__")
        val server = parts.first()
        if (server.isNotEmpty() && parts.size > 1) return "$server · ${parts.drop(1).joinToString("__")}"
    }
    return name.ifBlank { "Tool" }
}

private val URL_SCHEME = Regex("""^[a-z][a-z0-9+.-]*://""", RegexOption.IGNORE_CASE)
private val WHITESPACE = Regex("""\s""")

/** A file path, as opposed to a command, a pattern with spaces or a URL. */
internal fun looksLikePath(target: String): Boolean =
    '/' in target && !WHITESPACE.containsMatchIn(target) && !URL_SCHEME.containsMatchIn(target)

/**
 * A path cut to its last two segments (`…/lib/poll.ts`); anything that is not
 * a path is returned unchanged. The desktop's `shortTarget`, without its cwd
 * hint (a phone row has none to hand).
 */
internal fun shortTarget(target: String): String {
    if (!looksLikePath(target)) return target
    val segments = target.split('/').filter { it.isNotEmpty() }
    if (segments.size <= 2) return target
    return "…/" + segments.takeLast(2).joinToString("/")
}

/** What a tool row prints, worked out once. */
internal data class ToolLine(
    val verb: String,
    /** Already shortened; null when there is no target to show. */
    val target: String?,
    /** A path wants its middle ellipsized (keep the file name); a command its end. */
    val pathLike: Boolean,
    val kind: ToolKind,
)

internal fun ConvItem.Tool.line(): ToolLine {
    val name = toolName()
    val target = toolTarget()
    return ToolLine(
        verb = toolVerb(name),
        target = target?.let(::shortTarget),
        pathLike = target != null && looksLikePath(target),
        kind = toolKind(name),
    )
}

/**
 * The word a block leads with: the subagent type the call named, else the
 * tool's own name when the block is not a subagent at all, else `subagent`.
 *
 * The hub sends a `Workflow` call as a `subagent` item on purpose (`BLOCK_TOOLS`
 * in `service/transcript.rs`): a workflow is a whole piece of work that reports
 * once at the end, which is what this block draws, and sharing the wire kind is
 * what put it on phones that shipped before the change instead of
 * "(unsupported item: workflow)". It carries no `agent_type`, though, and
 * "subagent" is the one word that would be wrong above it.
 *
 * Ports the desktop's `blockTypeLabel` (`src/lib/conversation.ts`), lower-cased
 * the same way, so the phone and the desktop head the same block alike.
 */
internal fun ConvItem.Subagent.typeLabel(): String {
    agentType?.takeIf { it.isNotBlank() }?.let { return it }
    val own = name.trim()
    return if (own.isEmpty() || own == "Task" || own == "Agent") "subagent" else own.lowercase()
}

// ─── Grouping ───────────────────────────────────────────────────────────────

/** The fewest consecutive tool calls that fold into one "N tool calls" row. */
internal const val TOOL_GROUP_MIN: Int = 3

/** A turn's items as they are drawn: one at a time, or a run of tools folded. */
internal sealed interface ItemRun {
    data class One(val item: ConvItem) : ItemRun

    data class Tools(
        val tools: List<ConvItem.Tool>,
        /** A group holding a failure, or the live end of a running turn, opens by itself. */
        val startExpanded: Boolean,
    ) : ItemRun {
        val failed: Int get() = tools.count { it.error }
    }
}

/**
 * Fold every run of [TOOL_GROUP_MIN] or more consecutive tool calls into one
 * [ItemRun.Tools]; shorter runs, and everything that is not a tool call, stay
 * [ItemRun.One]. [running] says the turn is still going, which opens its last
 * group: that is where the work is happening.
 */
internal fun groupToolRuns(items: List<ConvItem>, running: Boolean): List<ItemRun> {
    val out = mutableListOf<ItemRun>()
    val pending = mutableListOf<ConvItem.Tool>()
    fun flush() {
        if (pending.size >= TOOL_GROUP_MIN) {
            out += ItemRun.Tools(pending.toList(), startExpanded = pending.any { it.error })
        } else {
            pending.forEach { out += ItemRun.One(it) }
        }
        pending.clear()
    }
    for (item in items) {
        if (item is ConvItem.Tool) pending += item else {
            flush()
            out += ItemRun.One(item)
        }
    }
    flush()
    if (running) {
        val last = out.indexOfLast { it is ItemRun.Tools }
        if (last >= 0) out[last] = (out[last] as ItemRun.Tools).copy(startExpanded = true)
    }
    return out
}

/** `"7 tool calls"`. */
internal fun toolGroupTitle(tools: List<ConvItem.Tool>): String =
    if (tools.size == 1) "1 tool call" else "${tools.size} tool calls"

/** `"Read, Grep, Edit +3"`: the distinct tools, first three named. */
internal fun toolGroupNames(tools: List<ConvItem.Tool>): String {
    val names = tools.map { it.toolName() }.filter { it.isNotBlank() }.distinct()
    val shown = names.take(3).joinToString(", ")
    return if (names.size > 3) "$shown +${names.size - 3}" else shown
}

/** A turn is still running while any of its calls waits for a result. */
internal fun anyPending(items: List<ConvItem>): Boolean = items.any {
    (it is ConvItem.Tool && !it.done) || (it is ConvItem.Subagent && !it.done)
}

// ─── Diff ───────────────────────────────────────────────────────────────────

internal enum class DiffKind { Context, Add, Del }

/**
 * One line of a diff. [oldNo] / [newNo] are 1-based within the two texts
 * compared (an Edit's snippet, a Write's whole file), null on the side a line
 * is not in.
 */
internal data class DiffLine(val kind: DiffKind, val text: String, val oldNo: Int?, val newNo: Int?)

/**
 * The most LCS cells one diff may fill. An Edit's texts are capped at
 * [HUB_TEXT_MAX] characters by the hub, so this is only reached by a long run
 * of very short lines; past it the changed middle is shown as all deletions
 * then all additions — the ROWS are less tidy, every changed line drawn twice.
 *
 * The `+N −M` beside them is not taken from those rows: it would read
 * `added = n, removed = m`, which is not the number of lines that changed.
 * [diffStat] computes it from the texts, in one two-row pass, so the figure an
 * audit screen reads as exact is exact either way.
 */
internal const val DIFF_MAX_CELLS: Long = 250_000

/**
 * The hub's cap on every text field of a tool detail
 * (`TOOL_DETAIL_MAX_CHARS`). A field it CUT is this many characters plus the
 * `…` it appends — see [cutByHub].
 */
internal const val HUB_TEXT_MAX = 8_000

/**
 * Whether [text] is a field the hub CUT at [HUB_TEXT_MAX].
 *
 * The hub appends one `…` when it cuts, and only then, so the marker is the
 * length plus that character. Counted in CODE POINTS, as the hub counts
 * `chars()`: a field full of emoji has more UTF-16 units than characters.
 *
 * It matters because the client diffs and paginates these fields: a cut that
 * nothing notices turns into changes that did not happen at the cut, and into
 * "the last 30 lines" that are from the middle.
 */
internal fun cutByHub(text: String?): Boolean {
    if (text == null || !text.endsWith("…")) return false
    // Code points: a surrogate pair carries exactly one low surrogate.
    return text.count { !it.isLowSurrogate() } == HUB_TEXT_MAX + 1
}

/** [text] without the `…` the hub appended, and without the partial last line. */
internal fun withoutHubCut(text: String): String =
    text.removeSuffix("…").substringBeforeLast('\n', missingDelimiterValue = "")

/**
 * [text] as lines, WITHOUT the empty segment a trailing newline produces.
 *
 * `"a\n".split('\n')` is `["a", ""]`, and that phantom line was a real
 * defect twice over: every `Write` of a newline-terminated file — which is
 * every well-formed source file — reported one addition too many and drew a
 * blank green row under the diff, and the same file read back through
 * `parseNumberedLines` had one line fewer, so the two views of one file
 * disagreed about its length.
 *
 * The cost is that `"a"` and `"a\n"` now diff as equal. That is git's own
 * reading (it reports the difference as a note, not a changed line), and it is
 * the lesser of the two wrongs: a missing final newline is a fact about the
 * file's last byte, not a changed line of code.
 */
private fun linesOf(text: String): List<String> =
    if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')

/**
 * A line diff of [old] against [new]: the common prefix and suffix are
 * trimmed, and what is left between is aligned by longest common subsequence,
 * deletions before additions within each change.
 */
internal fun lineDiff(old: String, new: String, maxCells: Long = DIFF_MAX_CELLS): List<DiffLine> {
    val a = linesOf(old)
    val b = linesOf(new)
    var pre = 0
    while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
    var suf = 0
    while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++

    val out = ArrayList<DiffLine>(a.size + b.size)
    for (i in 0 until pre) out += DiffLine(DiffKind.Context, a[i], i + 1, i + 1)

    val aEnd = a.size - suf
    val bEnd = b.size - suf
    val m = aEnd - pre
    val n = bEnd - pre
    if (m.toLong() * n.toLong() > maxCells) {
        for (i in pre until aEnd) out += DiffLine(DiffKind.Del, a[i], i + 1, null)
        for (j in pre until bEnd) out += DiffLine(DiffKind.Add, b[j], null, j + 1)
    } else {
        // dp[i][j] = LCS length of a[pre+i until aEnd] and b[pre+j until bEnd].
        val w = n + 1
        val dp = IntArray((m + 1) * w)
        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                dp[i * w + j] = if (a[pre + i] == b[pre + j]) {
                    dp[(i + 1) * w + j + 1] + 1
                } else {
                    maxOf(dp[(i + 1) * w + j], dp[i * w + j + 1])
                }
            }
        }
        var i = 0
        var j = 0
        while (i < m || j < n) {
            when {
                i < m && j < n && a[pre + i] == b[pre + j] -> {
                    out += DiffLine(DiffKind.Context, a[pre + i], pre + i + 1, pre + j + 1)
                    i++; j++
                }
                j >= n || (i < m && dp[(i + 1) * w + j] >= dp[i * w + j + 1]) -> {
                    out += DiffLine(DiffKind.Del, a[pre + i], pre + i + 1, null)
                    i++
                }
                else -> {
                    out += DiffLine(DiffKind.Add, b[pre + j], null, pre + j + 1)
                    j++
                }
            }
        }
    }
    for (k in 0 until suf) out += DiffLine(DiffKind.Context, a[aEnd + k], aEnd + k + 1, bEnd + k + 1)
    return out
}

/** A diff as drawn: lines, with long unchanged runs folded into a count. */
internal sealed interface DiffRow {
    data class Line(val line: DiffLine) : DiffRow
    data class Gap(val count: Int) : DiffRow

    /**
     * The boundary between two of a MultiEdit's edits — "edit 2 of 3".
     *
     * A MultiEdit arrives as one pair of strings with its edits joined by
     * [MULTI_EDIT_SEP], and diffing that pair whole made the separator itself
     * a diff line and let one edit's lines align against another's. Each edit
     * is its own diff; this is what sits between them.
     */
    data class Edit(val ordinal: Int, val total: Int) : DiffRow
}

/**
 * What the hub joins a MultiEdit's edits with, in both halves
 * (`service/transcript.rs`'s `edit_detail`). The two halves have the same
 * number of segments — one per edit — unless the hub's 8 000-character cap
 * fell inside one of them.
 */
internal const val MULTI_EDIT_SEP = "\n…\n"

/** Context lines kept on each side of a change. */
internal const val DIFF_CONTEXT: Int = 2

/**
 * Keep [context] unchanged lines either side of every change and fold the
 * rest of each unchanged run into one [DiffRow.Gap]. A run that would hide a
 * single line shows it instead: "⋯ 1 unchanged line" is no shorter.
 */
internal fun collapseContext(lines: List<DiffLine>, context: Int = DIFF_CONTEXT): List<DiffRow> {
    val out = mutableListOf<DiffRow>()
    var i = 0
    while (i < lines.size) {
        if (lines[i].kind != DiffKind.Context) {
            out += DiffRow.Line(lines[i]); i++; continue
        }
        var end = i
        while (end < lines.size && lines[end].kind == DiffKind.Context) end++
        val head = if (i > 0) context else 0
        val tail = if (end < lines.size) context else 0
        val hidden = (end - i) - head - tail
        if (hidden <= 1) {
            for (k in i until end) out += DiffRow.Line(lines[k])
        } else {
            for (k in i until i + head) out += DiffRow.Line(lines[k])
            out += DiffRow.Gap(hidden)
            for (k in end - tail until end) out += DiffRow.Line(lines[k])
        }
        i = end
    }
    return out
}

/**
 * A MultiEdit's rows: each edit diffed against its OWN counterpart, with a
 * [DiffRow.Edit] between them.
 *
 * The hub sends one pair of strings with the edits joined by
 * [MULTI_EDIT_SEP], and diffing that pair as one file was wrong twice: the
 * `…` separator line is in both halves, so it became a context line of the
 * diff and appeared on screen as part of the file; and the LCS is free to
 * align the first edit's old lines against the third edit's new ones, so the
 * hunks drifted out of the edits they belong to.
 *
 * When the two halves do NOT split into the same number of segments the join
 * cannot be undone — the hub's character cap fell inside a segment, or an
 * edit's own text contains the separator — so this falls back to the single
 * diff, which is wrong in the old way rather than wrong in a new one.
 */
internal fun multiEditRows(old: String, new: String, context: Int = DIFF_CONTEXT): List<DiffRow> {
    val pairs = multiEditPairs(old, new) ?: return collapseContext(lineDiff(old, new), context)
    val out = mutableListOf<DiffRow>()
    for ((index, pair) in pairs.withIndex()) {
        out += DiffRow.Edit(index + 1, pairs.size)
        out += collapseContext(lineDiff(pair.first, pair.second), context)
    }
    return out
}

/**
 * The same `+N −M` as [diffStat], summed per edit.
 *
 * Over the joined halves the separator lines cancel, so the total is nearly
 * right — but only nearly: the LCS may pair one edit's line with another's
 * and count neither. Per edit it is the figure each edit actually made.
 */
internal fun multiEditStat(old: String, new: String): DiffStat {
    val pairs = multiEditPairs(old, new) ?: return diffStat(old, new)
    var added = 0
    var removed = 0
    for ((a, b) in pairs) {
        val stat = diffStat(a, b)
        added += stat.added
        removed += stat.removed
    }
    return DiffStat(added, removed)
}

/**
 * [old] and [new] split on [MULTI_EDIT_SEP], or null when the join cannot be
 * undone: a single segment (a plain Edit or Write, nothing to split) or an
 * unequal count on the two sides.
 */
private fun multiEditPairs(old: String, new: String): List<Pair<String, String>>? {
    val a = old.split(MULTI_EDIT_SEP)
    val b = new.split(MULTI_EDIT_SEP)
    if (a.size < 2 || a.size != b.size) return null
    return a.zip(b)
}

/** `+N −M` counts of a diff. */
internal data class DiffStat(val added: Int, val removed: Int)

/**
 * The counts as the DRAWN rows hold them. Exact for a diff that was aligned;
 * past [DIFF_MAX_CELLS] every changed line is drawn as both a deletion and an
 * addition, so prefer [diffStat] over the texts there.
 */
internal fun diffStat(lines: List<DiffLine>): DiffStat =
    DiffStat(lines.count { it.kind == DiffKind.Add }, lines.count { it.kind == DiffKind.Del })

/**
 * The exact `+N −M` for [old] against [new], whichever way [lineDiff] drew it.
 *
 * `added = n − L` and `removed = m − L` for the LCS length `L` of the changed
 * middle, computed in two rows — so no cell budget applies and the figure is
 * the same one the aligned rows would have shown.
 */
internal fun diffStat(old: String, new: String): DiffStat {
    val a = linesOf(old)
    val b = linesOf(new)
    var pre = 0
    while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
    var suf = 0
    while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
    val m = a.size - suf - pre
    val n = b.size - suf - pre
    if (m == 0 || n == 0) return DiffStat(added = n, removed = m)
    var prev = IntArray(n + 1)
    var cur = IntArray(n + 1)
    for (i in 1..m) {
        for (j in 1..n) {
            cur[j] = if (a[pre + i - 1] == b[pre + j - 1]) {
                prev[j - 1] + 1
            } else {
                maxOf(prev[j], cur[j - 1])
            }
        }
        val swap = prev
        prev = cur
        cur = swap
        cur.fill(0)
    }
    val lcs = prev[n]
    return DiffStat(added = n - lcs, removed = m - lcs)
}

/** `"/repo/src/lib/poll.ts"` → `"poll.ts"` to `"/repo/src/lib/"`. */
internal fun splitPath(path: String): Pair<String, String> {
    val slash = path.trimEnd('/').lastIndexOf('/')
    return if (slash < 0) path to "" else path.substring(slash + 1) to path.substring(0, slash + 1)
}

// ─── Read / search / todos / output ─────────────────────────────────────────

/** One line of a Read result, with the number `cat -n` put in front of it. */
internal data class NumberedLine(val number: Int, val text: String)

/**
 * `"     1\tcode"`, or `"     1→code"` from a newer Claude Code.
 *
 * [RegexOption.DOT_MATCHES_ALL] and no trailing `$`: `.` excludes the line
 * terminators `\r`, U+0085, U+2028 and U+2029, and the input is already one
 * `\n`-free line, so without it a single Read line holding any of them failed
 * to match — and the `break` written for the note that FOLLOWS a numbered
 * block then discarded the whole rest of the file. `matchEntire` anchors both
 * ends on its own, which is why the `$` is gone with it.
 */
private val NUMBERED = Regex("""^\s*(\d+)(?:\t|→)(.*)""", RegexOption.DOT_MATCHES_ALL)

/**
 * A Read result's lines split into number and code, as far as they keep the
 * `cat -n` shape — whatever follows (a note the harness appended, the hub's
 * "…" cap) is dropped. Null when the result does not start that way at all
 * (an error, an image, an empty file), so the caller shows it as plain text.
 */
internal fun parseNumberedLines(result: String): List<NumberedLine>? {
    val out = mutableListOf<NumberedLine>()
    for (raw in result.split('\n')) {
        val match = NUMBERED.matchEntire(raw.removeSuffix("\r"))
        if (match == null) {
            if (out.isEmpty() && raw.isBlank()) continue
            break
        }
        val number = match.groupValues[1].toIntOrNull() ?: break
        out += NumberedLine(number, match.groupValues[2])
    }
    return out.takeIf { it.isNotEmpty() }
}

internal enum class TodoStatus { Pending, InProgress, Completed }

internal data class TodoEntry(val content: String, val status: TodoStatus)

/** A TodoWrite input's `todos`, or null when the input is not that shape. */
internal fun parseTodos(input: String): List<TodoEntry>? {
    val root = parseObject(input) ?: return null
    val todos = root["todos"] as? JsonArray ?: return null
    return todos.mapNotNull { element ->
        val todo = element as? JsonObject ?: return@mapNotNull null
        val content = todo.string("content") ?: return@mapNotNull null
        val status = when (todo.string("status")) {
            "completed" -> TodoStatus.Completed
            "in_progress" -> TodoStatus.InProgress
            else -> TodoStatus.Pending
        }
        TodoEntry(content, status)
    }
}

/** One string argument of a call's input JSON (`file_path`, `pattern` …). */
internal fun inputString(input: String, key: String): String? = parseObject(input)?.string(key)

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * The input as an object. Bounded before parsing: the recursion would blow
 * the stack on Kotlin/Native (`SIGBUS`, not catchable) for a deep enough
 * document, and this text is the hub's rendering of whatever the agent sent.
 */
private fun parseObject(input: String): JsonObject? {
    if (input.isBlank() || !nestsWithin(input, MAX_JSON_DEPTH)) return null
    return try {
        Json.parseToJsonElement(input) as? JsonObject
    } catch (e: IllegalArgumentException) {
        null
    }
}

private val FOUND_HEADER = Regex("""^Found \d+ (files?|lines?|matches?|occurrences?)\b.*$""")

/** A Grep / Glob result as its non-blank lines, without a "Found N files" header. */
internal fun searchResultLines(result: String): List<String> =
    result.split('\n').map { it.removeSuffix("\r") }.filter { it.isNotBlank() }
        .let { lines -> if (lines.firstOrNull()?.let(FOUND_HEADER::matches) == true) lines.drop(1) else lines }

/** The last [n] lines of [text] and how many came before them. */
internal fun tailLines(text: String, n: Int): Pair<Int, List<String>> {
    val lines = text.trimEnd('\n').split('\n')
    return if (lines.size <= n) 0 to lines else (lines.size - n) to lines.takeLast(n)
}
