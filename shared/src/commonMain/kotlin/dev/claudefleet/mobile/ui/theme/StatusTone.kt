package dev.claudefleet.mobile.ui.theme

/**
 * The colour a session's state is drawn in. The vocabulary is the hub's
 * (`claude_status`, `stuck_kind` in `pane_intel.rs`); the ranking is the
 * operator's: being stuck outranks any status because it is the thing a
 * person has to go and clear, and amber (BLOCKED) versus red (STUCK, FAILED)
 * is the whole triage — answer a question, or go fix the terminal.
 */
enum class StatusTone(val outlined: Boolean = false, val dotted: Boolean = false) {
    WORKING, IDLE, BLOCKED, STUCK, FAILED(outlined = true), COMPLETED, STOPPED(outlined = true), UNKNOWN(outlined = true, dotted = true);

    companion object {
        fun of(claudeStatus: String?, stuckKind: String?): StatusTone = when {
            !stuckKind.isNullOrBlank() -> STUCK
            else -> when (claudeStatus) {
                "working" -> WORKING
                "idle" -> IDLE
                "blocked" -> BLOCKED
                "failed" -> FAILED
                "completed" -> COMPLETED
                "stopped" -> STOPPED
                else -> UNKNOWN
            }
        }
    }
}

/** The word on the chip: the stuck kind, the status, or a dash when the hub has said nothing. */
fun statusLabel(claudeStatus: String?, stuckKind: String?): String = when {
    !stuckKind.isNullOrBlank() -> stuckKind.replace('_', ' ')
    claudeStatus.isNullOrBlank() -> "—"
    else -> claudeStatus
}
