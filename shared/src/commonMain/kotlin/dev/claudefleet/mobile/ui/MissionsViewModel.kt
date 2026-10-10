package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.MissionActions
import dev.claudefleet.mobile.model.Mission
import dev.claudefleet.mobile.model.MissionCard
import dev.claudefleet.mobile.model.MissionDetail
import dev.claudefleet.mobile.model.MissionStep
import dev.claudefleet.mobile.model.StepResult
import dev.claudefleet.mobile.model.grantHoursLeft
import dev.claudefleet.mobile.model.dollars
import dev.claudefleet.mobile.model.SpendAsk
import dev.claudefleet.mobile.model.key
import dev.claudefleet.mobile.model.pauseMove
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
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

data class MissionsUiState(
    /** The hub keeps missions and lists them to this token. */
    val available: Boolean = false,
    /** A person may press a step (`mission_start`). */
    val canStart: Boolean = false,
    /** A person may apply or dismiss a card (`card_decide`). */
    val canDecide: Boolean = false,
    /** A person may pause or resume one mission (`mission_state`). */
    val canPause: Boolean = false,
    /** A person may pause every mission at once (`missions_pause_all`). */
    val canPauseAll: Boolean = false,
    /**
     * A person may answer a spend ask (redesign 14.16): re-sign the grant
     * (`mission_grant`), resume (`mission_state`) and answer its card.
     */
    val canAnswerSpend: Boolean = false,
    /** A person may sign a mission's grant here (`mission_grant`, gap plan G5.7). */
    val canSignGrant: Boolean = false,
    /** A person may try a failed task again (`work_link { retry }`, G5.7). */
    val canRetry: Boolean = false,
    val open: Boolean = false,
    val loading: Boolean = false,
    val missions: List<Mission> = emptyList(),
    /** The mission opened from the list; null shows the list. */
    val detail: MissionDetail? = null,
    /** What is being sent: a step key, `card:<id>`, `state`, `pause_all`. */
    val busy: String? = null,
    /** The last press's outcome, one line per step. */
    val results: List<StepResult>? = null,
    /** A short line after a press that has no step results ("Paused 2 missions"). */
    val notice: String? = null,
    val error: Friendly? = null,
    /** The list has answered at least once. */
    val loaded: Boolean = false,
    /** The last list read failed: its body says so rather than "No missions yet". */
    val listFailed: Boolean = false,
) {
    /** Missions the loop may be driving now — what Pause all would stop. */
    val running: Int get() = missions.count { it.state == "active" }
}

/**
 * The desktop's Missions tab on the phone (claude-fleet orchestration O4–O8):
 * the list, and one mission's next steps, its confirm queue and the autonomy
 * that applies — each step pressed by a person (Go), each card applied or
 * dismissed, a question answered in words; Pause and Resume for one mission,
 * Pause all for every one. A grant is signed here only from its review sheet,
 * which spells every term out and pre-selects none (gap plan G5.7); planning
 * and editing a mission stay on the desktop.
 */
class MissionsViewModel(
    private val fleet: FleetState,
    private val actions: MissionActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val open: Boolean = false,
        val loading: Boolean = false,
        val missions: List<Mission> = emptyList(),
        val detail: MissionDetail? = null,
        val busy: String? = null,
        val results: List<StepResult>? = null,
        val notice: String? = null,
        val error: Friendly? = null,
        val loaded: Boolean = false,
        val listFailed: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<MissionsUiState> = combine(local, fleet.capabilities) { l, caps ->
        val link = { action: String -> canWrite && caps.lists(HubCapabilities.WORK_LINK, action) }
        MissionsUiState(
            available = caps.lists(HubCapabilities.WORK, "missions") && caps.lists(HubCapabilities.WORK, "mission"),
            canStart = link("mission_start"),
            canDecide = link("card_decide"),
            canPause = link("mission_state"),
            canPauseAll = link("missions_pause_all"),
            canAnswerSpend = link("mission_grant") && link("mission_state") && link("card_decide"),
            canSignGrant = link("mission_grant"),
            canRetry = link("retry"),
            open = l.open,
            loading = l.loading,
            missions = l.missions,
            detail = l.detail,
            busy = l.busy,
            results = l.results,
            notice = l.notice,
            error = l.error,
            loaded = l.loaded,
            listFailed = l.listFailed,
        )
    }.stateIn(scope, SharingStarted.Eagerly, MissionsUiState())

    fun open(): Job = scope.launch {
        local.update { it.copy(open = true, detail = null, results = null, notice = null, error = null) }
        read()
    }

    /**
     * Open the sheet straight on one mission: the New layout's Missions
     * screen lists them itself and hands a tap to the sheet's detail.
     */
    fun openOne(missionId: Long): Job = scope.launch {
        local.update { it.copy(open = true, detail = null, results = null, notice = null, error = null) }
        readOne(missionId)
    }

    fun close() {
        local.update { it.copy(open = false, detail = null, results = null, notice = null) }
    }

    fun refresh(): Job = scope.launch { read() }

    fun select(missionId: Long): Job = scope.launch {
        local.update { it.copy(results = null, notice = null, error = null) }
        readOne(missionId)
    }

    /** Back from a mission to the list. */
    fun back() {
        local.update { it.copy(detail = null, results = null, notice = null) }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    /** Go on one step; null takes every next step the loop lists. */
    fun start(step: MissionStep? = null): Job = act(step?.key() ?: "all") { id ->
        val out = actions.start(id, step?.key())
        local.update { it.copy(results = out.results) }
    }

    /** Apply ([ok]) or dismiss a card; a question's answer is [note]. */
    fun decide(card: MissionCard, ok: Boolean, note: String? = null): Job = act("card:${card.id}") {
        actions.decideCard(card.id, ok, note)
        local.update { it.copy(notice = if (ok) "Applied." else "Dismissed.") }
    }

    /**
     * The spend ask's Approve (redesign 14.16). Only ever from a person's tap
     * on the card — nothing calls it on a read, and the card has no default
     * answer. Re-signs the live grant with [ask]'s new limit (every other term
     * unchanged, for what is left of its time), resumes the mission, and
     * answers the brake's card so the lead knows.
     */
    fun approveSpend(ask: SpendAsk, nowSeconds: Long): Job = act("spend:${ask.missionId}") { id ->
        if (!state.value.canAnswerSpend || id != ask.missionId) return@act
        val m = local.value.detail?.mission ?: return@act
        actions.regrant(id, ask.grant, budgetCents = ask.newLimitMicros / 10_000, hours = grantHoursLeft(ask.grant, nowSeconds))
        // Three calls, not one step: the budget is raised by now, and the
        // card leaves the screen with it (`spendAsk` no longer reads it as an
        // ask). A resume that loses a version race must not leave the mission
        // paused behind a raised budget with no button left to resume it, so
        // it is tried once more against the version as it is now.
        try {
            actions.setState(id, "active", m.version)
        } catch (e: HubError.Tool) {
            if (e.code != "E_CONFLICT") throw e
            val now = actions.mission(id).mission
            if (now.state != "active") actions.setState(id, "active", now.version)
        }
        actions.decideCard(ask.cardId, ok = true, note = "Approved ${dollars(ask.moreMicros)} more; the new limit is ${dollars(ask.newLimitMicros)}.")
        local.update { it.copy(notice = "Approved ${dollars(ask.moreMicros)} more. The mission runs again.") }
    }

    /**
     * The spend ask's Deny: the budget stays; the brake's card is answered so
     * the lead finishes within it. The grant is not touched.
     */
    fun denySpend(ask: SpendAsk): Job = act("spend:${ask.missionId}") { id ->
        if (!state.value.canDecide || id != ask.missionId) return@act
        actions.decideCard(ask.cardId, ok = true, note = "No more spend: finish within ${dollars(ask.budgetMicros)}.")
        local.update { it.copy(notice = "Denied. The budget stays at ${dollars(ask.budgetMicros)}.") }
    }

    /**
     * Sign the grant the open mission waits on (gap plan G5.7), from the
     * review sheet's Sign only: [level] is what the mission asked for, and
     * [hours] and [budgetDollars] (null: no cap) are what the person picked.
     */
    fun signGrant(level: Int, hours: Int, budgetDollars: Long?): Job = act("grant") { id ->
        if (!state.value.canSignGrant) return@act
        if (level !in 1..3 || hours !in 1..GRANT_MAX_HOURS || (budgetDollars != null && budgetDollars <= 0)) return@act
        actions.signGrant(id, level, hours, budgetDollars?.let { it * 100 })
        local.update {
            it.copy(notice = "Signed: level $level for $hours h" + (budgetDollars?.let { d -> ", up to \$$d." } ?: ", no spend cap."))
        }
    }

    /** Another attempt at the open mission's failed task (G5.7); the outcome is the step's line. */
    fun retryItem(itemId: Long): Job = act("retry:$itemId") {
        if (!state.value.canRetry) return@act
        val r = actions.retryItem(itemId)
        local.update { it.copy(results = listOf(r)) }
    }

    /** Pause an active mission, resume a paused one. */
    fun togglePause(): Job = act("state") { id ->
        val m = local.value.detail?.mission ?: return@act
        val to = m.pauseMove() ?: return@act
        actions.setState(id, to, m.version)
        local.update { it.copy(notice = if (to == "paused") "Paused." else "Resumed.") }
    }

    /** Pause every mission this person may change, and end their grants. */
    fun pauseAll(): Job = scope.launch {
        if (!state.value.canPauseAll || local.value.busy != null) return@launch
        local.update { it.copy(busy = "pause_all", error = null, results = null) }
        try {
            val paused = actions.pauseAll()
            local.update {
                it.copy(
                    busy = null,
                    notice = when (paused.size) {
                        0 -> "Nothing was running."
                        1 -> "Paused 1 mission."
                        else -> "Paused ${paused.size} missions."
                    },
                )
            }
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
    }

    /** One press on the open mission, then a fresh read of it — whatever it changed is the hub's to say. */
    private fun act(busy: String, call: suspend (Long) -> Unit): Job = scope.launch {
        val id = local.value.detail?.mission?.id ?: return@launch
        if (local.value.busy != null) return@launch
        local.update { it.copy(busy = busy, error = null, results = null, notice = null) }
        try {
            call(id)
            local.update { it.copy(busy = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(busy = null, error = friendly(t)) }
        }
        readOne(id)
    }

    private suspend fun read() {
        local.update { it.copy(loading = true) }
        try {
            val missions = actions.missions()
            local.update { it.copy(loading = false, missions = missions, loaded = true, listFailed = false) }
            local.value.detail?.mission?.id?.let { readOne(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(loading = false, error = friendly(t), listFailed = true) }
        }
    }

    private suspend fun readOne(missionId: Long) {
        local.update { it.copy(loading = true) }
        try {
            val detail = actions.mission(missionId)
            local.update { l ->
                l.copy(
                    loading = false,
                    detail = detail,
                    // The list line follows the mission's state; its counts
                    // are the list's own, which the detail may not carry.
                    missions = l.missions.map {
                        if (it.id == missionId) it.copy(state = detail.mission.state, version = detail.mission.version) else it
                    },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(loading = false, error = friendly(t)) }
        }
    }
}

/** A grant runs at most a week (the hub's `GRANT_MAX_SECS`). */
const val GRANT_MAX_HOURS: Int = 168
