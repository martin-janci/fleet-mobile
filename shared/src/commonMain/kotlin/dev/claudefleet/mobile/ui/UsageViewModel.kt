package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.UsageActions
import dev.claudefleet.mobile.model.AccountRow
import dev.claudefleet.mobile.model.AccountUsageSnapshot
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

data class UsageUiState(
    val available: Boolean = false,
    /** The hub has said what it serves; before that nothing reads as "not offered" (r13 P20). */
    val capsKnown: Boolean = false,
    val accountsAvailable: Boolean = false,
    val window: UsageWindow = UsageWindow.Week,
    val report: UsageReport? = null,
    val accounts: List<AccountRow> = emptyList(),
    /** Whether the hub serves the 5-hour and weekly limits (`account_usage`). */
    val limitsAvailable: Boolean = false,
    /** Each account's limits, by its uuid. */
    val limits: Map<String, AccountUsageSnapshot> = emptyMap(),
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
) {
    private data class Local(
        val window: UsageWindow = UsageWindow.Week,
        val report: UsageReport? = null,
        val accounts: List<AccountRow> = emptyList(),
        val loading: Boolean = false,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<UsageUiState> = combine(local, fleet.capabilities, fleet.accountUsage) { l, caps, limits ->
        UsageUiState(
            available = caps.usage,
            capsKnown = caps.known,
            accountsAvailable = caps.accounts,
            window = l.window,
            report = l.report,
            accounts = l.accounts,
            limitsAvailable = caps.accountUsage,
            limits = limits,
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
                .map { it.usage to it.accounts }
                .distinctUntilChanged()
                .collect { (usage, accounts) -> if (usage || accounts) read(withAccounts = true) }
        }
    }

    /** The screen is gone: stop following. What was read stays for the next visit. */
    fun detach() {
        follow?.cancel()
        follow = null
    }

    fun select(window: UsageWindow): Job = scope.launch {
        if (local.value.window == window) return@launch
        local.update { it.copy(window = window) }
        read(withAccounts = false)
    }

    fun refresh(): Job = scope.launch { read(withAccounts = true) }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    private suspend fun read(withAccounts: Boolean) {
        val caps = fleet.capabilities.value
        local.update { it.copy(loading = true, error = null) }
        try {
            if (caps.usage) {
                val window = local.value.window
                val report = actions.report(window.seconds)
                local.update { if (it.window == window) it.copy(report = report) else it }
            }
            if (withAccounts && caps.accounts) {
                val accounts = actions.accounts()
                local.update { it.copy(accounts = accounts) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
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
