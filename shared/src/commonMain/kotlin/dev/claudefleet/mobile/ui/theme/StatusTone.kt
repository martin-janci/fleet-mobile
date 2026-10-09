package dev.claudefleet.mobile.ui.theme

/**
 * The colour a session's state is drawn in. The vocabulary is the hub's
 * (`claude_status`, `stuck_kind` in `pane_intel.rs`); the ranking is the
 * operator's: being stuck outranks any status because it is the thing a
 * person has to go and clear, and amber (BLOCKED) versus red (STUCK, FAILED)
 * is the whole triage — answer a question, or go fix the terminal.
 */
enum class StatusTone(val outlined: Boolean = false, val dotted: Boolean = false) {
    WORKING, IDLE, BLOCKED, STUCK, FAILED(outlined = true), COMPLETED, STOPPED(outlined = true), UNKNOWN(outlined = true, dotted = true),

    /**
     * Paused by the fleet, not by the session: an account at its usage limit
     * (the hub's `account_limit`, contract 11, step 4.4's "Paused · limit").
     * The idle grey, as the manual files paused beside idle; its line says
     * why and when it resets, and the row offers Switch account and Wait.
     */
    PAUSED;

    companion object {
        /**
         * A row's tone, with the hub's verdict: an account at its limit is
         * [PAUSED] whatever its status says (a turn that failed at the limit
         * reads as the limit, as the hub's attention table has it).
         */
        fun of(row: dev.claudefleet.mobile.model.SessionRow): StatusTone =
            if (row.attention?.reason == "account_limit") PAUSED else of(row.claudeStatus, row.stuckKind)

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

/**
 * The word on the chip, in the app's one vocabulary — the same words as the
 * filter sheet, the session's strip, Today and the notifications: *waiting*
 * for a person, *stuck*, *failed*, *done*. A dash when the hub has said
 * nothing; an unknown status as the hub's own word, so it can still be read.
 */
fun statusLabel(claudeStatus: String?, stuckKind: String?): String = when {
    !stuckKind.isNullOrBlank() -> "stuck"
    claudeStatus.isNullOrBlank() -> "—"
    else -> statusWord(claudeStatus)
}

/** A `claude_status` as the app says it. */
fun statusWord(claudeStatus: String): String = when (claudeStatus) {
    "blocked" -> "waiting"
    "completed" -> "done"
    else -> claudeStatus
}
