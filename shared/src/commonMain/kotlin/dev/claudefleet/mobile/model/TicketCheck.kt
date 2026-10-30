package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The AI check on done (claude-fleet docs/work-graph.md, *AI check on done*):
 * a headless tester's verdict on a ticket's finished work, `check` on
 * `work { task }`. Mirrors `service::work::ticket_check::TicketCheckView`.
 */
@Serializable
data class TicketCheck(
    /** `ready` | `running` | `passed` | `failed` | `error` | `accepted`. */
    val state: String = "",
    val setup: CheckSetup? = null,
    @SerialName("task_id") val taskId: Long? = null,
    val at: Long? = null,
    val summary: String? = null,
    /** What must change, in the tester's words. */
    val blockers: List<String> = emptyList(),
    /** The commands the tester says it ran. */
    @SerialName("tests_run") val testsRun: List<String> = emptyList(),
    /** Why the check could not run. */
    val error: String? = null,
    /** A person's note when they accepted it anyway. */
    val note: String? = null,
    /** Test now / Rerun may start a check. */
    @SerialName("can_run") val canRun: Boolean = false,
    @SerialName("why_not") val whyNot: String? = null,
) {
    /** **Accept anyway** is offered: the tester judged it not ready, or could not run. */
    val acceptable: Boolean get() = state == "failed" || state == "error"

    /** The run button's words. */
    val runLabel: String get() = if (state == "ready") "Test now" else "Rerun"
}

/** A project's switch: `off`, `ask` (on request) or `auto` (when done). */
@Serializable
data class CheckSetup(
    @SerialName("project_id") val projectId: Long = 0,
    /** `owner/repo`. */
    val project: String = "",
    val mode: String = "off",
    val command: String? = null,
)

/** The modes in the order the switch offers them, with their words. */
val CHECK_MODES: List<Pair<String, String>> = listOf("off" to "Off", "ask" to "On request", "auto" to "When done")

/** The line **Accept anyway** verifies (`ticket_check::CHECK_LINE`). */
const val CHECK_LINE = "ai check"
