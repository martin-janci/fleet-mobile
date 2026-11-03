package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.UsageActions
import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.model.AutoSwitchView
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.UsageReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The windows the Usage screen offers, as `usage_report`'s `since_secs`. */
enum class UsageWindow(val label: String, val seconds: Long) {
    Day("24 h", 86_400),
    Week("7 days", 7 * 86_400),
    Month("30 days", 30 * 86_400),
}

/** What an account's live sessions are doing, for its Accounts row (review 2026-11-03 F11). */
data class AccountSessions(val live: Int = 0, val paused: Int = 0)

/**
 * Each account's live sessions, by its uuid: neither lost nor a ghost, and
 * not a shell or an external pane (those carry no account). [AccountSessions.paused]
 * counts the ones paused on their login: at the account's limit or signed out.
 */
fun sessionsByAccount(rows: List<SessionRow>): Map<String, AccountSessions> =
    rows.asSequence()
        .filter { it.lostAt == null && it.status != "ghost" && it.kind != "shell" && it.kind != "external" }
        .mapNotNull { r -> r.accountUuid?.let { it to r } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, rs) ->
            AccountSessions(
                live = rs.size,
                paused = rs.count { it.attention?.reason == "account_limit" || it.attention?.reason == "no_credentials" },
            )
        }

/** "2 sessions · 1 paused", or null for an account with none running. */
fun accountSessionsLine(s: AccountSessions?): String? {
    if (s == null || s.live == 0) return null
    val n = if (s.live == 1) "1 session" else "${s.live} sessions"
    return if (s.paused > 0) "$n · ${s.paused} paused" else n
}

data class UsageUiState(
    val available: Boolean = false,
    /** The hub has said what it serves; before that nothing reads as "not offered" (r13 P20). */
    val capsKnown: Boolean = false,
    val accountsAvailable: Boolean = false,
    /** This pairing may name an account (`set_account_nickname`, a full token). */
    val canRename: Boolean = false,
    val window: UsageWindow = UsageWindow.Week,
    val report: UsageReport? = null,
    val accounts: List<AccountRow> = emptyList(),
    /** Whether the hub serves the 5-hour and weekly limits (`account_usage`). */
    val limitsAvailable: Boolean = false,
    /** Each account's limits, by its uuid. */
    val limits: Map<String, AccountUsageSnapshot> = emptyMap(),
    /** Each account's live sessions, by its uuid ([sessionsByAccount]). */
    val sessions: Map<String, AccountSessions> = emptyMap(),
    /** The person's answer to automatic switching; null until read or where the hub has no such tool. */
    val autoSwitch: AutoSwitchView? = null,
    /** The hub takes "continue after a switch" too. */
    val autoSwitchContinueAvailable: Boolean = false,
    /** This pairing may change it (a full token). */
    val canChangeAutoSwitch: Boolean = false,
    val loading: Boolean = false,
    val error: Friendly? = null,
)

/**
 * The fleet's ESTIMATED usage — the desktop's Usage page as far as the hub
 * serves it to a client: totals, by host, by day and the costliest sessions
 * over a chosen window, and the Claude accounts seen on the hosts with
 * their 5-hour and weekly limits where the hub serves them (`account_usage`,
 * read and kept fresh by the fleet connection, [FleetState.accountUsage]).
 */
class UsageViewModel(
    private val fleet: FleetState,
    private val actions: UsageActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean = true,
) {
    private data class Local(
        val window: UsageWindow = UsageWindow.Week,
        val report: UsageReport? = null,
        val accounts: List<AccountRow> = emptyList(),
        val autoSwitch: AutoSwitchView? = null,
        val loading: Boolean = false,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    private val accountSessions = fleet.sessions.map(::sessionsByAccount).distinctUntilChanged()

    val state: StateFlow<UsageUiState> = combine(local, fleet.capabilities, fleet.accountUsage, accountSessions) { l, caps, limits, sessions ->
        UsageUiState(
            available = caps.usage,
            capsKnown = caps.known,
            accountsAvailable = caps.accounts,
            canRename = canWrite && caps.accountNickname,
            window = l.window,
            report = l.report,
            accounts = l.accounts,
            limitsAvailable = caps.accountUsage,
            limits = limits,
            sessions = sessions,
            autoSwitch = l.autoSwitch.takeIf { caps.autoSwitch },
            autoSwitchContinueAvailable = caps.autoSwitchContinue,
            canChangeAutoSwitch = canWrite && caps.autoSwitch,
            loading = l.loading,
            error = l.error,
        )
    }.stateIn(scope, SharingStarted.Eagerly, UsageUiState())

    private var follow: Job? = null

    /**
     * The screen is showing: read now, and again whenever the hub's
     * capabilities change what it serves, until [detach]. Discovery lands a
     * beat after the connection is ready, so a Usage screen opened before it
     * would otherwise read nothing and never try again.
     */
    fun attach() {
        if (follow?.isActive == true) return
        follow = scope.launch {
            fleet.capabilities
                .map { Triple(it.usage, it.accounts, it.autoSwitch) }
                .distinctUntilChanged()
                .collect { (usage, accounts, autoSwitch) -> if (usage || accounts || autoSwitch) read(withAccounts = true) }
        }
    }

    /** The screen is gone: stop following. What was read stays for the next visit. */
    fun detach() {
        follow?.cancel()
        follow = null
    }

    fun select(window: UsageWindow): Job = scope.launch {
        if (local.value.window == window) return@launch
        // The old window's totals never stand under the new chip, even when
        // the new read fails (review M6).
        local.update { it.copy(window = window, report = null) }
        read(withAccounts = false)
    }

    fun refresh(): Job = scope.launch { read(withAccounts = true) }

    /** Name [uuid] (blank clears); the row takes the hub's answer. */
    fun rename(uuid: String, nickname: String): Job = scope.launch {
        if (!state.value.canRename) return@launch
        try {
            val row = actions.setNickname(uuid, nickname.trim().ifEmpty { null })
            local.update { l -> l.copy(accounts = l.accounts.map { if (it.uuid == row.uuid) row else it }) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        }
    }

    /** Change the person's automatic-switching answer; the screen takes the hub's. */
    fun setAutoSwitch(enabled: Boolean? = null, continueAfter: Boolean? = null): Job = scope.launch {
        if (!state.value.canChangeAutoSwitch) return@launch
        try {
            val view = actions.autoSwitch(enabled, continueAfter)
            local.update { it.copy(autoSwitch = view) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    private suspend fun read(withAccounts: Boolean) {
        val caps = fleet.capabilities.value
        local.update { it.copy(loading = true, error = null) }
        // Each read stands alone: a report that times out on a large fleet
        // still leaves the accounts and their meters (review w23-9). The
        // first failure is the banner's.
        var failure: Throwable? = null
        try {
            if (caps.usage) {
                val window = local.value.window
                try {
                    val report = actions.report(window.seconds)
                    local.update { if (it.window == window) it.copy(report = report) else it }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    failure = t
                }
            }
            if (withAccounts && caps.accounts) {
                try {
                    val accounts = actions.accounts()
                    local.update { it.copy(accounts = accounts) }
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    if (failure == null) failure = t
                }
            }
            if (withAccounts && caps.autoSwitch) {
                // Quiet on failure: a pairing that speaks for no person is
                // refused it, and the rest of the screen is still good.
                val view = try {
                    actions.autoSwitch()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    null
                }
                local.update { it.copy(autoSwitch = view) }
            }
            failure?.let { t -> local.update { it.copy(error = friendly(t)) } }
        } finally {
            local.update { it.copy(loading = false) }
        }
    }
}

/** 1234 → "1.2k", 2_500_000 → "2.5M". */
internal fun compactCount(n: Long): String = when {
    n >= 1_000_000_000 -> oneDecimal(n / 1_000_000_000.0) + "B"
    n >= 1_000_000 -> oneDecimal(n / 1_000_000.0) + "M"
    n >= 1_000 -> oneDecimal(n / 1_000.0) + "k"
    else -> n.toString()
}

private fun oneDecimal(x: Double): String {
    val tenths = kotlin.math.round(x * 10).toLong()
    return if (tenths % 10 == 0L) (tenths / 10).toString() else "${tenths / 10}.${tenths % 10}"
}
