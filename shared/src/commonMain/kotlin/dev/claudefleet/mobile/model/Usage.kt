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

/** One limit window of a subscription: how much of it is used (0–100) and when it starts over. */
@Serializable
data class LimitWindow(
    val utilization: Double = 0.0,
    @SerialName("resets_at") val resetsAt: Long? = null,
)

/** A subscription's limit windows, each absent where the account has none. */
@Serializable
data class AccountLimits(
    @SerialName("five_hour") val fiveHour: LimitWindow? = null,
    @SerialName("seven_day") val sevenDay: LimitWindow? = null,
    @SerialName("seven_day_opus") val sevenDayOpus: LimitWindow? = null,
    @SerialName("seven_day_sonnet") val sevenDaySonnet: LimitWindow? = null,
)

/**
 * `account_usage`: an account's last read of its 5-hour and weekly limits,
 * as the hub polls them on a host. [status] is the outcome of the latest
 * try (`ok`, `rate_limited`, `login_expired`, …); [usage] is the last good
 * read and may be older than a failed try.
 */
@Serializable
data class AccountUsageSnapshot(
    @SerialName("account_uuid") val accountUuid: String,
    val usage: AccountLimits? = null,
    val subscription: String? = null,
    @SerialName("fetched_at") val fetchedAt: Long? = null,
    @SerialName("source_host") val sourceHost: String? = null,
    val status: String = "never_fetched",
    val detail: String? = null,
    @SerialName("next_try_at") val nextTryAt: Long = 0,
)
