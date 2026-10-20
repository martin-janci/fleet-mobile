package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.AutofixActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.model.AutofixFinding
import dev.claudefleet.mobile.model.AutofixFindings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AutofixUiState(
    /** The hub serves `autofix` to this token. */
    val available: Boolean = false,
    /** Fix and Later may be pressed here: the hub says so, and this token writes. */
    val canApply: Boolean = false,
    val open: Boolean = false,
    val loading: Boolean = false,
    /** Read at least once: the More row's line waits for it. */
    val loaded: Boolean = false,
    /** Live findings, oldest first: open, verifying, apply_failed and dismissed. */
    val findings: List<AutofixFinding> = emptyList(),
    /** Cleared in the last day after a fix ran, newest first. */
    val fixed: List<AutofixFinding> = emptyList(),
    /** What is on the wire: `scan`, or `<id>:apply` / `<id>:dismiss`. */
    val busy: String? = null,
    val notice: String? = null,
    val error: Friendly? = null,
) {
    /** What a person is asked about: everything live but the hidden. */
    val problems: List<AutofixFinding> get() = findings.filter { it.state != DISMISSED }

    /** Hidden with Later, until the hub shows them again. */
    val hidden: List<AutofixFinding> get() = findings.filter { it.state == DISMISSED }
}

/**
 * Problems & fixes on the phone (claude-fleet `autofix`): what the hub found
 * broken in the fleet, each with a plain-language explanation, what fleet
 * saw, and its fix; the fixes run in the last day; and, where the hub lets
 * this device decide, Fix and Later. The desktop's Control panel shows the
 * same list.
 */
class AutofixViewModel(
    private val fleet: FleetState,
    private val actions: AutofixActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val open: Boolean = false,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val list: AutofixFindings = AutofixFindings(),
        val busy: String? = null,
        val notice: String? = null,
        val error: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<AutofixUiState> = combine(local, fleet.capabilities) { l, caps ->
        AutofixUiState(
            available = caps.autofix,
            canApply = caps.autofix && canWrite && l.list.canApply,
            open = l.open,
            loading = l.loading,
            loaded = l.loaded,
            findings = l.list.findings,
            fixed = l.list.fixed,
            busy = l.busy,
            notice = l.notice,
            error = l.error,
        )
    }.stateIn(scope, SharingStarted.Eagerly, AutofixUiState())

    fun open(): Job = scope.launch {
        local.update { it.copy(open = true, notice = null, error = null) }
        read()
    }

    fun close() {
        local.update { it.copy(open = false, notice = null) }
    }

    /** The More row's line wants the list without the sheet open. */
    fun refresh(): Job = scope.launch { read() }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /** Check the fleet now; the answer is the list as it then stands. */
    fun scan(): Job = scope.launch {
        if (!state.value.available || local.value.busy != null) return@launch
        local.update { it.copy(busy = SCAN, error = null, notice = null) }
        try {
            val list = actions.scan()
            local.update { it.copy(busy = null, loaded = true, list = list, notice = if (list.findings.none { f -> f.state != DISMISSED }) "No problems found." else null) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
    }

    /** Fix: the hub runs the fix and re-checks; the row takes its answer. */
    fun apply(finding: AutofixFinding): Job = scope.launch {
        if (!state.value.canApply || local.value.busy != null || finding.fix == null) return@launch
        local.update { it.copy(busy = "${finding.id}:apply", error = null, notice = null) }
        try {
            val row = actions.apply(finding.id)
            val name = findingTitle(finding)
            keep(
                row,
                when (row.state) {
                    CLEARED -> "Fixed: $name."
                    APPLY_FAILED -> "The fix did not work: " + (row.lastError ?: name)
                    else -> "Fix applied to $name — checking that it worked."
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
    }

    /** Later: hidden for a day. */
    fun later(finding: AutofixFinding): Job = scope.launch {
        if (!state.value.canApply || local.value.busy != null || finding.state == DISMISSED) return@launch
        local.update { it.copy(busy = "${finding.id}:dismiss", error = null, notice = null) }
        try {
            keep(actions.dismiss(finding.id), "Hidden until tomorrow: ${findingTitle(finding)}.")
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
    }

    /** The row as the hub answered it: still live, or moved to the fixed list. */
    private fun keep(row: AutofixFinding, notice: String) {
        local.update { l ->
            val rest = l.list.findings.filter { it.id != row.id }
            val list = if (row.state == CLEARED) {
                l.list.copy(findings = rest, fixed = listOf(row) + l.list.fixed.filter { it.id != row.id })
            } else {
                l.list.copy(findings = (rest + row).sortedWith(compareBy({ it.firstSeenAt }, { it.id })))
            }
            l.copy(busy = null, list = list, notice = notice)
        }
    }

    private suspend fun read() {
        if (!state.value.available) return
        local.update { it.copy(loading = true) }
        try {
            val list = actions.list()
            local.update { it.copy(loading = false, loaded = true, list = list) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(loading = false, error = friendly(t)) }
        }
    }

    private companion object {
        const val SCAN = "scan"
    }
}

private const val DISMISSED = "dismissed"
private const val CLEARED = "cleared"
private const val APPLY_FAILED = "apply_failed"

/** The More row: "2 problems · 1 fixed in the last day", "No problems found". */
fun autofixLine(state: AutofixUiState): String {
    if (!state.loaded) return "What fleet found broken, and its fixes"
    val n = state.problems.size
    val fixed = state.fixed.size
    val problems = if (n == 0) "No problems found" else "$n problem${if (n == 1) "" else "s"}"
    return if (fixed == 0) problems else "$problems · $fixed fixed in the last day"
}

/** The finding's title, or its kind when the hub could not read the body. */
fun findingTitle(f: AutofixFinding): String = f.title?.takeIf { it.isNotBlank() } ?: f.kind

/** What the subject names, in words: "host mercury", "session #12". */
fun subjectWords(subject: String): String {
    val k = subject.substringBefore(':', "")
    val v = subject.substringAfter(':', "")
    return when (k) {
        "host" -> "host $v"
        "session" -> "session #$v"
        "token" -> "token $v"
        else -> subject
    }
}

/** The state of a finding in a few words, for the row's status line; null while it is simply open. */
fun findingStateWords(f: AutofixFinding): String? = when (f.state) {
    "verifying" -> "Fix applied — checking that it worked…"
    APPLY_FAILED -> f.lastError?.takeIf { it.isNotBlank() }?.let { "The fix did not work: $it" } ?: "The fix did not work."
    DISMISSED -> "Hidden until later."
    CLEARED -> "Resolved."
    else -> null
}

/**
 * For a safe automatic fix: whether fleet runs it by itself, and when, or
 * that it stopped (as the desktop's `autoWords`). Null for any other fix.
 */
fun autoWords(f: AutofixFinding, nowSeconds: Long): String? {
    val fix = f.fix ?: return null
    if (fix.safety != "safe" || !fix.auto) return null
    if (f.state != "open" && f.state != APPLY_FAILED) return null
    val runs = f.autoRuns ?: return "Safe: fleet runs this fix by itself unless automatic fixes are off."
    if (!runs) {
        if (f.attempts > 0) {
            val times = if (f.attempts == 1) "once" else "${f.attempts} times"
            return "Fleet tried this fix $times and stopped trying by itself; fix it here."
        }
        return "Fleet will not run this fix by itself (automatic fixes are off or paused); fix it here."
    }
    val mins = ((f.autoNextAt ?: nowSeconds) - nowSeconds + 59) / 60
    return if (mins <= 1) "Fleet runs this fix by itself on its next check." else "Fleet tries this fix again by itself in ~$mins min."
}

/** What a fixed finding's row says ran: the fix's summary, and how many tries it took. */
fun fixedLine(f: AutofixFinding): String {
    val what = f.fix?.summary?.takeIf { it.isNotBlank() } ?: "Fixed"
    return if (f.attempts > 1) "$what · ${f.attempts} tries" else what
}

/** A fix waiting on a person reads "Fix…"; one that failed reads "Try again…". */
fun fixLabel(f: AutofixFinding): String = if (f.state == APPLY_FAILED) "Try again…" else "Fix…"

/**
 * A fix that ran under a minute ago is still running on the hub: Fix stays
 * off until its re-check, as the hub refuses a second apply that soon.
 */
fun fixRunning(f: AutofixFinding, nowSeconds: Long): Boolean =
    f.state == "verifying" && f.lastAttemptAt != null && nowSeconds - f.lastAttemptAt < 60
