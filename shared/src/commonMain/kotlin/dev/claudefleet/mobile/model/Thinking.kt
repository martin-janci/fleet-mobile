package dev.claudefleet.mobile.model

/*
 * The line under a working session's newest turn (MobileSession: "a small
 * Atom says what Claude reads"): "Thinking", and when the newest thing in the
 * turn is a read or a search, what it is reading — "Thinking · reading
 * HostsViewModel.kt". Read off the conversation the phone already holds, so
 * it costs no call and says nothing the turn does not.
 */

const val THINKING = "Thinking"

/** The tools whose target is something Claude is reading, and the word for it. */
private val READING_VERBS = mapOf(
    "Read" to "reading",
    "NotebookRead" to "reading",
    "Grep" to "searching",
    "Glob" to "searching",
)

/** Past this a pattern or a name is cut, so the line stays one line. */
private const val MAX_TARGET = 40

/**
 * The thinking line for [turn], the session's newest turn while it works.
 * Only the turn's last item counts: once Claude has written text or run
 * something else after a read, the read is no longer what it is doing.
 */
fun thinkingLine(turn: ConvTurn?): String {
    val tool = turn?.items?.lastOrNull() as? ConvItem.Tool ?: return THINKING
    // An older hub sends only the one-liner, `Read(file_path=/a/b.kt)`.
    val name = tool.name.ifBlank { tool.summary.substringBefore('(').trim() }
    val verb = READING_VERBS[name] ?: return THINKING
    val target = (if (tool.name.isNotBlank()) tool.target else targetFromSummary(tool.summary))
        ?.trim()?.takeIf { it.isNotEmpty() } ?: return THINKING
    return "$THINKING · $verb ${shortReadTarget(name, target)}"
}

/** The parenthesised part of an older hub's one-liner, its argument name dropped. */
private fun targetFromSummary(summary: String): String? {
    val open = summary.indexOf('(')
    val close = summary.lastIndexOf(')')
    if (open <= 0 || close <= open) return null
    return summary.substring(open + 1, close).trim().replaceFirst(Regex("""^[A-Za-z_][A-Za-z0-9_]*="""), "")
}

/** A read's file by its name alone (the board's "reading HostsViewModel.kt"); a search's pattern as it is. */
private fun shortReadTarget(name: String, target: String): String {
    val short = if (name == "Read" || name == "NotebookRead") {
        target.trimEnd('/').substringAfterLast('/').ifEmpty { target }
    } else {
        target
    }
    return if (short.length > MAX_TARGET) short.take(MAX_TARGET - 1) + "…" else short
}
