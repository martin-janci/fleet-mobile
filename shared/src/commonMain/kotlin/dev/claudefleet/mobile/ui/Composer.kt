package dev.claudefleet.mobile.ui

/**
 * The composer's helpers, as the desktop's `conversation.ts` has them: the
 * slash-command hint list, and the model and effort pickers, which send
 * `/model <alias>` and `/effort <level>` like any typed command — per agent:
 * a Codex session gets Codex's commands and models, and its pickers relaunch
 * it (see [switchesByRelaunch]).
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
fun matchSlashCommands(draft: String, agent: String? = null): List<SlashCommand> {
    if (!draft.startsWith("/") || draft.any { it.isWhitespace() }) return emptyList()
    val prefix = draft.drop(1).lowercase()
    return slashCommandsFor(agent).filter { it.name.startsWith(prefix) }
}

/** The hub's `agent` value for OpenAI's Codex CLI. */
const val AGENT_CODEX = "codex"

/**
 * Codex's built-in commands worth offering — the desktop's
 * `CODEX_SLASH_COMMANDS`, from fleet's Codex adapter. Codex's `/model` takes
 * no argument: it opens Codex's own picker.
 */
val CODEX_SLASH_COMMANDS: List<SlashCommand> = listOf(
    SlashCommand("model", "Choose the model and reasoning effort"),
    SlashCommand("permissions", "Choose what Codex is allowed to do"),
    SlashCommand("new", "Start a new chat"),
    SlashCommand("compact", "Summarise the conversation to free context"),
    SlashCommand("status", "Show the configuration and token usage"),
    SlashCommand("diff", "Show the git diff, untracked files included"),
    SlashCommand("review", "Review the current changes"),
    SlashCommand("plan", "Switch to Plan mode"),
    SlashCommand("mention", "Mention a file", args = true),
    SlashCommand("resume", "Resume a saved chat"),
    SlashCommand("fork", "Fork the current chat"),
    SlashCommand("rename", "Rename the current thread", args = true),
    SlashCommand("init", "Write an AGENTS.md for this project"),
    SlashCommand("skills", "Use skills"),
    SlashCommand("mcp", "List MCP tools"),
    SlashCommand("hooks", "View and manage lifecycle hooks"),
    SlashCommand("memories", "Configure memory use and generation"),
    SlashCommand("copy", "Copy the last response"),
    SlashCommand("export", "Export the conversation as markdown"),
    SlashCommand("exit", "Quit Codex (the tmux session stays)"),
)

/** The built-in commands a session of [agent] (the row's `agent`) offers. */
fun slashCommandsFor(agent: String?): List<SlashCommand> =
    if (agent == AGENT_CODEX) CODEX_SLASH_COMMANDS else SLASH_COMMANDS

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

/** The models Codex lists (`codex -m`), newest first — the desktop's `CODEX_MODEL_OPTIONS`. */
val CODEX_MODEL_OPTIONS: List<PickerOption> = listOf(
    PickerOption("gpt-6.1-sol", "GPT-6.1-Sol"),
    PickerOption("gpt-6-astra", "GPT-6-Astra"),
    PickerOption("gpt-6-sol", "GPT-6-Sol"),
    PickerOption("gpt-6-luna", "GPT-6-Luna"),
    PickerOption("gpt-5.6-sol", "GPT-5.6-Sol"),
    PickerOption("gpt-5.6-terra", "GPT-5.6-Terra"),
    PickerOption("gpt-5.6-luna", "GPT-5.6-Luna"),
    PickerOption("gpt-5.5", "GPT-5.5"),
)

/** Codex's reasoning levels a session stores; Codex has no `auto`. */
val CODEX_EFFORT_OPTIONS: List<PickerOption> = listOf(
    PickerOption("low", "Low"),
    PickerOption("medium", "Medium"),
    PickerOption("high", "High"),
    PickerOption("xhigh", "Extra high"),
    PickerOption("max", "Max"),
)

/** The model or effort ([command]) picker's entries for a session of [agent]. */
fun pickerOptionsFor(agent: String?, command: String): List<PickerOption> = when {
    agent == AGENT_CODEX && command == "model" -> CODEX_MODEL_OPTIONS
    agent == AGENT_CODEX -> CODEX_EFFORT_OPTIONS
    command == "model" -> MODEL_OPTIONS
    else -> EFFORT_OPTIONS
}

/**
 * Whether [agent] changes model or effort only by a relaunch: Codex's
 * `/model` takes no argument, so the hub restarts it on the same
 * conversation with the pick (`restart_session { model, effort }`).
 */
fun switchesByRelaunch(agent: String?): Boolean = agent == AGENT_CODEX

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
