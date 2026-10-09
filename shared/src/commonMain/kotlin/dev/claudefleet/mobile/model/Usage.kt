package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Tokens and the estimated cost they come to (micro-USD, from the hub's price table — not a bill). */
@Serializable
data class UsageTotals(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("cost_micros") val costMicros: Long = 0,
) {
    val tokens: Long get() = inputTokens + outputTokens + cacheWriteTokens + cacheReadTokens
}

/** One UTC day of the durable roll-up. The hub flattens the totals into the row. */
@Serializable
data class DayUsage(
    val day: String,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("cost_micros") val costMicros: Long = 0,
    /** History a session's first read booked, apart from that day's live cost. */
    @SerialName("backfill_cost_micros") val backfillCostMicros: Long = 0,
)

/** One session's lifetime usage, the totals flattened in. */
@Serializable
data class SessionUsage(
    @SerialName("session_id") val sessionId: Long,
    @SerialName("host_alias") val hostAlias: String = "",
    @SerialName("tmux_name") val tmuxName: String = "",
    @SerialName("friendly_name") val friendlyName: String? = null,
    val model: String? = null,
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0,
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("cost_micros") val costMicros: Long = 0,
) {
    val name: String get() = friendlyName?.takeIf { it.isNotBlank() } ?: tmuxName
}

/** `usage_report`: ESTIMATED usage per host, per day and per session. */
@Serializable
data class UsageReport(
    val note: String = "",
    val since: Long? = null,
    val total: UsageTotals = UsageTotals(),
    @SerialName("by_host") val byHost: Map<String, UsageTotals> = emptyMap(),
    @SerialName("by_day") val byDay: List<DayUsage> = emptyList(),
    val sessions: List<SessionUsage> = emptyList(),
    @SerialName("sessions_truncated") val sessionsTruncated: Boolean = false,
)

/** A Claude account seen on the fleet's hosts (`list_accounts`). */
@Serializable
data class AccountRow(
    val uuid: String,
    val email: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("organization_name") val organizationName: String? = null,
    @SerialName("seat_tier") val seatTier: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: Long? = null,
    val nickname: String? = null,
    @SerialName("has_extra_usage") val hasExtraUsage: Boolean = false,
) {
    val label: String get() = nickname?.takeIf { it.isNotBlank() } ?: displayName?.takeIf { it.isNotBlank() } ?: email ?: uuid.take(8)
}

/** One usage window of an account. `utilization` is percent USED, 0..100. */
@Serializable
data class UsageWindow(
    val utilization: Double = 0.0,
    /** Unix seconds; null when the hub does not know. */
    @SerialName("resets_at") val resetsAt: Long? = null,
)

/** The windows an account's usage reading has; any may be absent. */
@Serializable
data class AccountUsageWindows(
    @SerialName("five_hour") val fiveHour: UsageWindow? = null,
    @SerialName("seven_day") val sevenDay: UsageWindow? = null,
    @SerialName("seven_day_opus") val sevenDayOpus: UsageWindow? = null,
    @SerialName("seven_day_sonnet") val sevenDaySonnet: UsageWindow? = null,
)

/**
 * The last usage reading of one account (`account_usage`, claude-fleet's
 * `AccountUsageSnapshot`). What the paused row (redesign step 4.10) and
 * the Accounts and usage meters read; the rest of the hub's snapshot is
 * ignored.
 */
@Serializable
data class AccountUsageSnapshot(
    @SerialName("account_uuid") val accountUuid: String,
    /** The last good reading, kept even when [status] says the last try failed. */
    val usage: AccountUsageWindows? = null,
    /** `ok`, `rate_limited`, `login_expired`, … : how the latest try went. */
    val status: String? = null,
    /** When [usage] was read, unix seconds. */
    @SerialName("fetched_at") val fetchedAt: Long? = null,
)

/** An account at a usage limit: which window, and when it resets. */
data class AccountLimit(val weekly: Boolean, val resetsAt: Long?)

/**
 * The limit this account is at, at [now] (unix seconds), or null. The hub's
 * own rule (`attention::Facts::from_fleet`): a window is at its limit when it
 * is fully used and has not reset yet, and the weekly one wins, because it is
 * the longer wait.
 */
fun AccountUsageSnapshot.limitAt(now: Long): AccountLimit? {
    val u = usage ?: return null
    fun UsageWindow.atLimit() = utilization >= 100.0 && (resetsAt == null || resetsAt > now)
    u.sevenDay?.takeIf { it.atLimit() }?.let { return AccountLimit(weekly = true, resetsAt = it.resetsAt) }
    u.fiveHour?.takeIf { it.atLimit() }?.let { return AccountLimit(weekly = false, resetsAt = it.resetsAt) }
    return null
}
