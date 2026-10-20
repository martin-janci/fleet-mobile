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
    val problems: List<AutofixFinding> get() = findings.filterNot { it.dismissed }

    /** Hidden with Later, until the hub shows them again. */
    val hidden: List<AutofixFinding> get() = findings.filter { it.dismissed }
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
    fun scan(): Job = act(SCAN, state.value.available) {
        val list = actions.scan()
        local.update { it.copy(busy = null, loaded = true, list = list, notice = scanNotice(list.findings)) }
    }

    /** Fix: the hub runs the fix and re-checks; the row takes its answer. */
    fun apply(finding: AutofixFinding): Job = act("${finding.id}:apply", state.value.canApply && finding.fix != null) {
        val row = actions.apply(finding.id)
        val name = findingTitle(finding)
        keep(
            row,
            when {
                row.cleared -> "Fixed: $name."
                row.failed -> "The fix did not work: " + (row.lastError ?: name)
                else -> "Fix applied to $name — checking that it worked."
            },
        )
    }

    /** Later: hidden for a day. */
    fun later(finding: AutofixFinding): Job = act("${finding.id}:dismiss", state.value.canApply && !finding.dismissed) {
        keep(actions.dismiss(finding.id), "Hidden until tomorrow: ${findingTitle(finding)}.")
    }

    /** One press while nothing else is on the wire; [call] clears `busy` with its answer. */
    private fun act(busy: String, allowed: Boolean, call: suspend () -> Unit): Job = scope.launch {
        if (!allowed || local.value.busy != null) return@launch
        local.update { it.copy(busy = busy, error = null, notice = null) }
        try {
            call()
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
            val list = if (row.cleared) {
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

private const val APPLY_FAILED = "apply_failed"

/**
 * The More row: "2 problems · 1 fixed in the last day", "No problems found".
 * Findings hidden with Later still exist and come back, so a list holding
 * only those reads "No open problems · 2 hidden", not "No problems found".
 */
fun autofixLine(state: AutofixUiState): String {
    if (!state.loaded) return "What fleet found broken, and its fixes"
    val n = state.problems.size
    val fixed = state.fixed.size
    val hidden = state.hidden.size
    val problems = when {
        n > 0 -> "$n problem${if (n == 1) "" else "s"}"
        hidden > 0 -> "No open problems · $hidden hidden"
        else -> "No problems found"
    }
    return if (fixed == 0) problems else "$problems · $fixed fixed in the last day"
}

/** The sheet's line when nothing is open: it names the rows hidden with Later, if any. */
fun noOpenProblemsLine(state: AutofixUiState): String {
    val hidden = state.hidden.size
    return if (hidden == 0) "Nothing is broken that fleet knows of." else "No open problems · $hidden hidden until later."
}

/** What Check now says when it found nothing open; null while something is. */
internal fun scanNotice(findings: List<AutofixFinding>): String? {
    if (findings.any { !it.dismissed }) return null
    val hidden = findings.size
    return if (hidden == 0) "No problems found." else "No open problems · $hidden hidden until later."
}

/**
 * What a chat `fix` card says to do with its finding: the phone's own
 * More › Problems & fixes where the hub serves `autofix` here, and the
 * desktop only where it does not. [canApply] is the hub's answer
 * ([AutofixUiState.canApply]: a trusted, full-access device), not merely a
 * write token: an untrusted phone finds no Fix button there.
 */
fun fixCardNote(autofix: Boolean, canApply: Boolean): String = when {
    autofix && canApply -> "Review and apply its fix in More › Problems & fixes."
    autofix -> "Review it in More › Problems & fixes; the desktop or a trusted device applies its fix."
    else -> "Review and apply its fix in fleet on the desktop."
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
fun findingStateWords(f: AutofixFinding): String? = when {
    f.verifying -> "Fix applied — checking that it worked…"
    f.failed -> f.lastError?.takeIf { it.isNotBlank() }?.let { "The fix did not work: $it" } ?: "The fix did not work."
    f.dismissed -> "Hidden until later."
    f.cleared -> "Resolved."
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
fun fixLabel(f: AutofixFinding): String = if (f.failed) "Try again…" else "Fix…"

/**
 * A fix that ran under a minute ago is still running on the hub: Fix stays
 * off until its re-check, as the hub refuses a second apply that soon.
 */
fun fixRunning(f: AutofixFinding, nowSeconds: Long): Boolean =
    f.verifying && f.lastAttemptAt != null && nowSeconds - f.lastAttemptAt < 60
