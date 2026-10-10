package dev.claudefleet.mobile.ui

/**
 * The composer's helpers, as the desktop's `conversation.ts` has them: the
 * slash-command hint list, and the model and effort pickers, which send
 * `/model <alias>` and `/effort <level>` like any typed command.
 */
data class SlashCommand(val name: String, val description: String, val args: Boolean = false)

/** Claude Code's built-in commands — a hint list, not a spec of the CLI; the desktop's, verbatim. */
val SLASH_COMMANDS: List<SlashCommand> = listOf(
    SlashCommand("clear", "Clear the conversation and start fresh"),
    SlashCommand("compact", "Summarise the context to free space (optional focus text)", args = true),
    SlashCommand("context", "Show what is using the context window"),
    SlashCommand("cost", "Show token usage and cost for this session"),
    SlashCommand("usage", "Show plan usage and rate limits"),
    SlashCommand("status", "Show version, model, account and working directory"),
    SlashCommand("model", "Switch the model", args = true),
    SlashCommand("effort", "Set the reasoning effort level", args = true),
    SlashCommand("rc", "Remote Control: drive this session from claude.ai"),
    SlashCommand("resume", "Resume an earlier conversation"),
    SlashCommand("rewind", "Rewind the conversation and files to a checkpoint"),
    SlashCommand("review", "Review the current changes"),
    SlashCommand("memory", "Edit the memory files loaded into context"),
    SlashCommand("config", "Open settings"),
    SlashCommand("permissions", "Manage tool permissions"),
    SlashCommand("mcp", "Manage MCP servers"),
    SlashCommand("agents", "Manage subagent definitions"),
    SlashCommand("hooks", "Manage hooks"),
    SlashCommand("doctor", "Check the installation"),
    SlashCommand("init", "Write a CLAUDE.md for this project"),
    SlashCommand("export", "Export the conversation to a file"),
    SlashCommand("help", "List commands and shortcuts"),
    SlashCommand("exit", "Quit Claude Code (the tmux session stays)"),
)

/**
 * Commands whose name starts with the draft's slash token — empty unless the
 * whole draft is one token beginning with `/`: once an argument or a second
 * line is being typed, the suggestions get out of the way.
 */
fun matchSlashCommands(draft: String): List<SlashCommand> {
    if (!draft.startsWith("/") || draft.any { it.isWhitespace() }) return emptyList()
    val prefix = draft.drop(1).lowercase()
    return SLASH_COMMANDS.filter { it.name.startsWith(prefix) }
}

/** What accepting a suggestion puts in the box: a trailing space where an argument follows. */
fun completeSlashCommand(c: SlashCommand): String = if (c.args) "/${c.name} " else "/${c.name}"

/** One picker entry: [value] is the argument the command is sent with. */
data class PickerOption(val value: String, val label: String)

/** `/model` aliases — a hint list, the desktop's. */
val MODEL_OPTIONS: List<PickerOption> = listOf(
    PickerOption("default", "Default"),
    PickerOption("best", "Best available"),
    PickerOption("fable", "Fable"),
    PickerOption("opus", "Opus"),
    PickerOption("opus[1m]", "Opus (1M context)"),
    PickerOption("sonnet", "Sonnet"),
    PickerOption("sonnet[1m]", "Sonnet (1M context)"),
    PickerOption("haiku", "Haiku"),
    PickerOption("opusplan", "Opus plan / Sonnet"),
)

/** `/effort` levels. */
val EFFORT_OPTIONS: List<PickerOption> = listOf(
    PickerOption("auto", "Auto"),
    PickerOption("low", "Low"),
    PickerOption("medium", "Medium"),
    PickerOption("high", "High"),
    PickerOption("xhigh", "Extra high"),
    PickerOption("max", "Max"),
)

/** The line a picker sends — `/model opus` — or null for a blank or multi-word value. */
fun pickerCommand(command: String, value: String): String? {
    val v = value.trim()
    if (v.isEmpty() || v.any { it.isWhitespace() }) return null
    return "/$command $v"
}

/**
 * What the mic's words do to the box: added after what is typed, a space
 * between, never over it — dictating the second half of a sentence must not
 * wipe the first. Words that heard nothing leave the draft as it was.
 */
fun withDictation(draft: String, heard: String): String {
    val words = heard.trim()
    if (words.isEmpty()) return draft
    val typed = draft.trimEnd()
    return if (typed.isEmpty()) words else "$typed $words"
}
