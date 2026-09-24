package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.store.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything the fleet list draws. */
data class SessionsUiState(
    /** Host groups, in the live part of the screen. Empty while [results] is set. */
    val groups: List<HostGroup> = emptyList(),
    /** What has gone quiet for over a day, behind one collapsible heading. */
    val dormant: List<SessionRow> = emptyList(),
    val dormantExpanded: Boolean = false,
    /** A flat answer to [query], or null when nothing was asked. */
    val results: List<SessionRow>? = null,
    val query: String = "",
    val lens: Lens = Lens.All,
    /** Whether background agents and shell panes are being held back. */
    val hideNoise: Boolean = true,
    /** How many rows [hideNoise] is holding back right now. */
    val hiddenNoise: Int = 0,
    val status: ConnectionStatus = ConnectionStatus.Offline("not connected yet"),
    /** The host [groups] is narrowed to, or null for the whole fleet. */
    val hostFilter: String? = null,
    /** How many rows in the **whole** fleet want a person, filtered or not. */
    val attentionCount: Int = 0,
    val refreshing: Boolean = false,
    /** The last refresh's failure, in plain language with the hub's own words behind it. */
    val error: Friendly? = null,
    /** Unix seconds, refreshed every 30s by a ticker — what every row's age is computed against. */
    val nowSeconds: Long = 0,
) {
    /** Whether a question has been asked. Distinct from "the answer was empty". */
    val searching: Boolean get() = results != null

    /** Nothing to draw at all — whichever of the two shapes the screen is in. */
    val isEmpty: Boolean
        get() = if (searching) results.isNullOrEmpty() else groups.isEmpty() && dormant.isEmpty()

    /**
     * Kept so the rest of the app can keep asking the question it always asked.
     * The toggle this used to be is now the narrowest of four [Lens]es.
     */
    val needsAttentionOnly: Boolean get() = lens == Lens.NeedsYou
}

/**
 * The fleet list: sessions grouped by host and then by project, under a lens
 * that says how they are being used, with a search across everything the rows
 * carry.
 *
 * Plain Kotlin, not an `androidx.lifecycle.ViewModel` — the same class has to
 * run on iOS, and everything a lifecycle-aware base class would give is one
 * injected [CoroutineScope]. Whoever owns the screen owns the scope.
 *
 * It reads [FleetState] and never a `HubClient`: the rows arrive through the
 * event stream and are already in hand, so every one of the choices here —
 * the lens, the search, the noise switch, a collapsed host — is a fold over
 * rows this class already holds and costs the hub nothing. None of them talk
 * to it.
 *
 * The rules themselves are in `SessionsTriage.kt` as one pure function, and
 * that is where they are tested. This class holds the choices, persists the
 * ones worth persisting, and combines them with the fleet.
 */
class SessionsViewModel(
    private val fleet: FleetState,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    /**
     * The clock every age on this screen is measured against: the device's,
     * corrected by what the hub said its own time was on the last `ready`.
     * Uncorrected, a device a few minutes behind labels the whole fleet "just
     * now" and one running fast labels a working session as hours idle — both
     * silently, since every row is wrong by the same amount.
     */
    private val clock: () -> Long = { epochSeconds() + fleet.clockSkewSeconds.value },
) {
    /** The four fleet flows combined into one value, so a second `combine` can fold in [local] and [now]. */
    private data class FleetSnapshot(
        val sessions: List<SessionRow>,
        val hosts: List<HostRow>,
        val projects: List<ProjectRow>,
        val status: ConnectionStatus,
    )

    /**
     * The choices this screen holds.
     *
     * [query] is deliberately not persisted: a search is a moment, not a
     * setting, and an app that reopened three days later still filtered to
     * something typed on a train would look broken rather than helpful. The
     * other three are settings — they describe how this person wants the fleet
     * shown — and they are read back in [init] and written on every change.
     */
    private data class Local(
        val lens: Lens = Lens.All,
        val query: String = "",
        val hideNoise: Boolean = true,
        val collapsedHosts: Set<String> = emptySet(),
        val dormantExpanded: Boolean = false,
        val hostFilter: String? = null,
        val refreshing: Boolean = false,
        val error: Friendly? = null,
    )

    private val local: MutableStateFlow<Local>
    private val now = MutableStateFlow(clock())

    init {
        local = MutableStateFlow(
            Local(
                lens = prefs.lens(),
                hideNoise = prefs.flag(HIDE_NOISE_KEY, default = true),
                collapsedHosts = prefs.getStringList(COLLAPSED_KEY).toSet(),
                dormantExpanded = prefs.flag(DORMANT_KEY, default = false),
            ),
        )
        scope.launch {
            while (isActive) {
                delay(30_000)
                now.value = clock()
            }
        }
    }

    val state: StateFlow<SessionsUiState> = combine(
        combine(fleet.sessions, fleet.hosts, fleet.projects, fleet.status, ::FleetSnapshot),
        local,
        now,
    ) { snapshot, l, nowSeconds -> assemble(snapshot, l, nowSeconds) }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            // Computed rather than left blank: the flows are StateFlows, so the
            // first frame the screen draws can be the real one.
            assemble(
                FleetSnapshot(
                    fleet.sessions.value,
                    fleet.hosts.value,
                    fleet.projects.value,
                    fleet.status.value,
                ),
                local.value,
                now.value,
            ),
        )

    /**
     * Look at the fleet through one lens. Persisted, because it is how this
     * person wants the fleet shown rather than something they are doing now.
     *
     * Every mutator here is one `update {}` rather than a read of `local.value`
     * followed by a write — the rule [SessionViewModel]'s own KDoc states, and
     * the one a previous lost-update bug in this class was fixed for. Two taps
     * cannot then read the same value and both write the same answer, losing
     * one.
     */
    fun setLens(lens: Lens) {
        local.update { it.copy(lens = lens) }
        prefs.putStringList(LENS_KEY, listOf(lens.name))
    }

    /**
     * Ask a question of the whole fleet, or clear it with a blank string.
     *
     * Not persisted — see [Local].
     */
    fun setQuery(text: String) {
        local.update { it.copy(query = text) }
    }

    /** Hold back background agents and shell panes, or stop. Persisted. */
    fun toggleHideNoise() {
        var updated = false
        local.update {
            updated = !it.hideNoise
            it.copy(hideNoise = updated)
        }
        prefs.putFlag(HIDE_NOISE_KEY, updated)
    }

    /** Fold one host's rows away, or unfold them. Persisted. */
    fun toggleHost(alias: String) {
        var updated: Set<String> = emptySet()
        local.update {
            updated = if (alias in it.collapsedHosts) it.collapsedHosts - alias else it.collapsedHosts + alias
            it.copy(collapsedHosts = updated)
        }
        prefs.putStringList(COLLAPSED_KEY, updated.sorted())
    }

    /** Show or hide the dormant tail. Persisted. */
    fun toggleDormant() {
        var updated = false
        local.update {
            updated = !it.dormantExpanded
            it.copy(dormantExpanded = updated)
        }
        prefs.putFlag(DORMANT_KEY, updated)
    }

    /**
     * Show only one host's groups, or all of them again with `null`.
     *
     * Not persisted, and not a setting: it is where the person navigated to
     * (`Navigator.showSessionsFor`), and `Screen.Sessions.hostAlias` is its one
     * source of truth. [SessionsUiState.attentionCount] stays fleet-wide
     * regardless of what this narrows the groups to.
     */
    fun setHostFilter(alias: String?) {
        local.update { it.copy(hostFilter = alias) }
    }

    /**
     * Clear the banner.
     *
     * An error a person has read and cannot put away teaches them to stop
     * reading the banner, which is the one thing it must not do — and on a hub
     * that is down, the next successful refresh that would have cleared it
     * never comes.
     */
    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /**
     * Re-list the fleet. The rows on screen stay put if it fails — the last
     * snapshot is still the best picture there is — and the failure is shown.
     *
     * A [ConnectionStatus.Refused] hub is not re-listed at all. The
     * repository already refuses to apply that hub's row events, so calling
     * its list tools would decode the very shape this build has said it
     * cannot read — and a spinner over three calls that end in the same
     * refusal is worse than a pull that does nothing behind a banner already
     * saying why.
     */
    fun refresh(): Job = scope.launch {
        if (fleet.status.value is ConnectionStatus.Refused) return@launch
        local.update { it.copy(refreshing = true, error = null) }
        try {
            fleet.refresh()
            local.update { it.copy(refreshing = false, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(refreshing = false, error = friendly(t)) }
        }
    }

    private fun assemble(snapshot: FleetSnapshot, l: Local, nowSeconds: Long): SessionsUiState {
        val triage = triageSessions(
            sessions = snapshot.sessions,
            hosts = snapshot.hosts,
            projects = snapshot.projects,
            lens = l.lens,
            query = l.query,
            hideNoise = l.hideNoise,
            hostFilter = l.hostFilter,
            collapsedHosts = l.collapsedHosts,
            nowSeconds = nowSeconds,
        )
        return SessionsUiState(
            groups = triage.groups,
            dormant = triage.dormant,
            dormantExpanded = l.dormantExpanded,
            results = triage.results,
            query = l.query,
            lens = l.lens,
            hideNoise = l.hideNoise,
            hiddenNoise = triage.hiddenNoise,
            status = snapshot.status,
            hostFilter = l.hostFilter,
            attentionCount = snapshot.sessions.count { it.needsAttention },
            refreshing = l.refreshing,
            error = l.error,
            nowSeconds = nowSeconds,
        )
    }

    private companion object {
        const val LENS_KEY = "sessions.lens"
        const val HIDE_NOISE_KEY = "sessions.hideNoise"
        const val COLLAPSED_KEY = "sessions.collapsedHosts"
        const val DORMANT_KEY = "sessions.dormantExpanded"
    }
}

/**
 * The stored lens, or [Lens.All].
 *
 * An unrecognised value reads as the default rather than throwing: the store
 * outlives the app version that wrote it, so a downgrade — or a build that has
 * dropped a lens — would otherwise crash on the first frame with a value it
 * put there itself.
 */
private fun Prefs.lens(): Lens {
    val stored = getStringList("sessions.lens").firstOrNull() ?: return Lens.All
    return Lens.entries.firstOrNull { it.name == stored } ?: Lens.All
}

/** A boolean in a store that holds string lists. Anything unrecognised is [default]. */
private fun Prefs.flag(key: String, default: Boolean): Boolean =
    when (getStringList(key).firstOrNull()) {
        "true" -> true
        "false" -> false
        else -> default
    }

private fun Prefs.putFlag(key: String, value: Boolean) {
    putStringList(key, listOf(if (value) "true" else "false"))
}
