package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.SessionEvent

/**
 * A session's event timeline, sorted the way the desktop's `timeline.ts`
 * sorts it, so the same event lands in the same filter on both: turns,
 * prompts, errors, ops, and everything else.
 */
enum class EventCategory(val label: String) {
    Turns("Turns"),
    Prompts("Prompts"),
    Errors("Errors"),
    Ops("Ops"),
    Other("Other"),
}

/** The filter chips, in the desktop's order; [EventCategory.Other] has none — it shows under no filter. */
val FILTER_CATEGORIES: List<EventCategory> =
    listOf(EventCategory.Turns, EventCategory.Prompts, EventCategory.Errors, EventCategory.Ops)

private val OPS_KINDS = setOf(
    "killed", "recreated", "gc_killed", "playbook_applied", "mcp_call", "message_sent", "message_undeliverable",
)

private val TURN_KINDS = setOf(
    "conversation_started", "conversation_ended", "compact_started", "compact_done", "turn_done",
    "stop_blocked_for_message", "work_classify_nudge",
)

private val ERROR_KINDS = setOf("stop_block_cap_reached")

internal fun eventCategory(event: SessionEvent): EventCategory {
    val k = event.kind
    if (k == "stuck" || k.endsWith("_failed") || "error" in k || k in ERROR_KINDS) return EventCategory.Errors
    if (k == "status_change") {
        val d = event.detail.orEmpty().lowercase()
        return if (Regex("\\b(failed|blocked)\\b").containsMatchIn(d)) EventCategory.Errors else EventCategory.Turns
    }
    if (k in TURN_KINDS) return EventCategory.Turns
    if (k.startsWith("prompt") || k == "keys_sent") return EventCategory.Prompts
    if (k in OPS_KINDS || k.startsWith("repair") || k.startsWith("workspace") ||
        k.startsWith("safe_kill") || k.startsWith("task_")
    ) {
        return EventCategory.Ops
    }
    return EventCategory.Other
}

/** `turn_done` → `turn done`. */
internal fun kindLabel(kind: String): String = kind.replace('_', ' ')

/** The detail on one line, cut to [max] characters with an ellipsis. */
internal fun shortDetail(detail: String?, max: Int = 120): String {
    if (detail == null) return ""
    val one = detail.replace(Regex("\\s+"), " ").trim()
    return if (one.length > max) one.take(max - 1) + "…" else one
}

/** No filter chosen shows everything; otherwise the events in any chosen category. */
internal fun filterEvents(events: List<SessionEvent>, active: Set<EventCategory>): List<SessionEvent> =
    if (active.isEmpty()) events else events.filter { eventCategory(it) in active }
