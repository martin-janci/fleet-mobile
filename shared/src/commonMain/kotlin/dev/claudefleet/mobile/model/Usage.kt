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
    /**
     * The desktop's rule (`accountLabel` in claude-fleet's `src/lib/accounts.ts`):
     * the nickname, else the email, else the uuid's first 8 characters. Not
     * the display name: two accounts of one person share it, and the chip
     * and "limit on X" must tell them apart.
     */
    val label: String get() = nickname?.trim()?.ifEmpty { null } ?: email?.trim()?.ifEmpty { null } ?: uuid.take(8)
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

/** One login on a host (`check_account_headroom`, claude-fleet's `account_limits::HostLogin`). */
@Serializable
data class HostLogin(
    /** Null = the host's own login; else the credential profile's name. */
    val profile: String? = null,
    @SerialName("account_uuid") val accountUuid: String,
    /** Percent used of the account's tighter live window; null without a reading. */
    @SerialName("used_pct") val usedPct: Double? = null,
)

/** `check_account_headroom` (hub contract 14): which login on a host has room left. */
@Serializable
data class Headroom(
    @SerialName("pause_at_pct") val pauseAtPct: Double = 100.0,
    val chosen: HostLogin? = null,
    val over: Boolean = false,
    val suggestion: HostLogin? = null,
    val logins: List<HostLogin> = emptyList(),
)

/**
 * The headroom answer for the account a session actually bills — the
 * desktop's `headroomForAccount` (`account_limits.ts`, review r05). The hub
 * reads the login by profile, but a row's `account_uuid` is what it runs on
 * (a profile can be logged into another account since the start), so when
 * the row names an account the host has a login for, `over` and the
 * suggestion are read for that account: the login under the line with the
 * least used, on another account.
 */
fun Headroom.forAccount(accountUuid: String?): Headroom {
    val chosen = accountUuid?.let { a -> logins.firstOrNull { it.accountUuid == a } } ?: return this
    val used = chosen.usedPct
    val over = used != null && used >= pauseAtPct
    var suggestion: HostLogin? = null
    if (over) {
        for (l in logins) {
            val pct = l.usedPct ?: continue
            if (l.accountUuid == chosen.accountUuid || pct >= pauseAtPct) continue
            if (suggestion == null || pct < (suggestion.usedPct ?: Double.MAX_VALUE)) suggestion = l
        }
    }
    return copy(chosen = chosen, over = over, suggestion = suggestion)
}

/** "work (m.janci@…)" or the account alone: how a login reads in the switch question. */
fun loginLabel(l: HostLogin, accountName: (String) -> String?): String {
    val who = accountName(l.accountUuid) ?: l.accountUuid.take(8)
    return l.profile?.let { "$it ($who)" } ?: who
}

/** "95% used" or "no reading". */
fun usedText(l: HostLogin): String = l.usedPct?.let { "${kotlin.math.round(it).toInt()}% used" } ?: "no reading"
