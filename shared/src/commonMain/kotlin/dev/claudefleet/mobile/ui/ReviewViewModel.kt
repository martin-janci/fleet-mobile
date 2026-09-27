package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.WorkActions
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.ReviewAlternative
import dev.claudefleet.mobile.model.ReviewItem
import dev.claudefleet.mobile.model.ReviewKind
import dev.claudefleet.mobile.model.WorkDecision
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK
import dev.claudefleet.mobile.net.HubCapabilities.Companion.WORK_LINK
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.isUnknownAction
import dev.claudefleet.mobile.utcOffsetSeconds
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

/**
 * The last decision, which *Undo* sends back to a suggestion (`reconsider`)
 * under [version] — the link's version the decision itself answered, so an
 * undo of a link another device has since changed is refused, not applied.
 */
data class UndoOffer(val sessionId: Long, val linkId: Long, val label: String, val version: Long)

/** A batch decision the hub refused, said beside its card. */
data class ReviewFailure(val code: String?, val message: String)

data class ReviewUiState(
    /** The hub serves `work { review }`: My work shows the Review button. */
    val available: Boolean = false,
    val open: Boolean = false,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val items: List<ReviewItem> = emptyList(),
    /** Everything waiting, not only what is loaded. */
    val total: Int = 0,
    /** More cards exist than are loaded: *Load more* is offered. */
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val connected: Boolean = false,
    /** "Offline · as of 10:42" over cards kept from before the connection went. */
    val stale: String? = null,
    /** Confirm and Change… (`confirm` / `link`). */
    val canConfirm: Boolean = false,
    val canReject: Boolean = false,
    /** Keep a conflict (`ack`). */
    val canKeep: Boolean = false,
    /** Remove a conflicting link (`unlink`). */
    val canRemove: Boolean = false,
    /** Make a link primary for a session with none (`set_primary`). */
    val canMakePrimary: Boolean = false,
    val canChange: Boolean = false,
    /** *Confirm all shown (n)* — `decide_batch`. */
    val canBatch: Boolean = false,
    /** How many cards *Confirm all shown* would confirm: the suggestions on screen. */
    val batchCount: Int = 0,
    /** Per link id: why a batch decision was refused. */
    val failures: Map<Long, ReviewFailure> = emptyMap(),
    val undo: UndoOffer? = null,
    val canUndo: Boolean = false,
    /** The card whose alternatives are showing (*Change…*). */
    val changing: Long? = null,
    val busy: Boolean = false,
    val error: Friendly? = null,
    val conflict: Boolean = false,
)

/**
 * The **Review** sheet over My work: one card per thing to decide
 * (`work { review }`) — a suggestion, a cross-org link, a ticket gone
 * unavailable, a session with no primary — each with the hub's visible
 * reason. Every card stays where it is until the person decides it; a
 * decision re-reads the list, nothing advances on its own.
 *
 * Every decision carries the link's `link_version`, so one made on another
 * device first is `E_CONFLICT` and nothing is overwritten. Confirm and
 * Reject go as a one-item `decide_batch` where the hub has it, because its
 * answer carries the link's new version: **Undo** sends the last decision
 * back to a suggestion (`reconsider`) under exactly that version. A hub
 * without `decide_batch` gets the single call and no Undo — there is no
 * version to send it under. **Confirm all shown** sends the suggestions on
 * screen as one `decide_batch`; the hub checks each on its own and the sheet
 * says, card by card, which it refused.
 *
 * **The primary is never taken.** A confirm (or *Change…*'s link) says
 * `primary: true` only for a session that has no primary — its row carries
 * no work, or the inbox itself lists it as *no primary* — and in a batch only
 * for the first decision of each such session; everything else is sent as
 * `primary: false`, since a `confirm` that leaves `primary` out takes the
 * primary on the hub.
 *
 * Offline the cards stay, marked with their age; a decision then is refused
 * with a message, and nothing queues.
 */
class ReviewViewModel(
    private val fleet: FleetState,
    private val actions: WorkActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    /** Told after every decision, so My work's count follows. */
    private val onChanged: () -> Unit = {},
    private val clock: () -> Long = { epochSeconds() },
    private val utcOffset: (Long) -> Int = ::utcOffsetSeconds,
) {
    private data class Local(
        val open: Boolean = false,
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val items: List<ReviewItem> = emptyList(),
        val total: Int = 0,
        val cursor: String? = null,
        val loadingMore: Boolean = false,
        /** Bumped by every re-read that replaces the cards: a *Load more* from before it is dropped. */
        val generation: Long = 0,
        val asOf: Long? = null,
        val failures: Map<Long, ReviewFailure> = emptyMap(),
        val undo: UndoOffer? = null,
        val changing: Long? = null,
        val busy: Boolean = false,
        val error: Friendly? = null,
        val conflict: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<ReviewUiState> =
        combine(fleet.capabilities, fleet.status, local) { caps, status, l -> assemble(caps, status, l) }
            .stateIn(scope, SharingStarted.Eagerly, assemble(fleet.capabilities.value, fleet.status.value, local.value))

    fun open(): Job? {
        if (!fleet.capabilities.value.has(WORK, REVIEW)) return null
        local.update { it.copy(open = true, error = null, conflict = false, failures = emptyMap()) }
        return reload()
    }

    fun close() {
        local.update { it.copy(open = false, undo = null, changing = null, failures = emptyMap(), error = null, conflict = false) }
    }

    /** Re-read the cards — the sheet's refresh, and *Reload* after a conflict, which it clears. */
    fun reload(): Job = scope.launch {
        local.update { it.copy(error = null, conflict = false) }
        load()
    }

    private suspend fun load() {
        if (!fleet.capabilities.value.has(WORK, REVIEW)) return
        if (!fleet.status.value.isConnected()) return
        local.update { it.copy(loading = true) }
        try {
            // As far as the person had paged, so a re-read after a decision
            // does not fold the list back to its first page.
            val limit = local.value.items.size.coerceIn(PAGE, MAX_LIMIT)
            val page = actions.review(limit = limit)
            local.update { l ->
                l.copy(
                    loading = false,
                    loaded = true,
                    items = page.items,
                    total = page.total,
                    cursor = page.nextCursor,
                    loadingMore = false,
                    generation = l.generation + 1,
                    asOf = clock(),
                    // A refusal stays beside its card while the card is still there.
                    failures = l.failures.filterKeys { id -> page.items.any { it.linkId == id } },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK, REVIEW)
            local.update { it.copy(loading = false, error = friendlyWork(t), conflict = false) }
        }
    }

    /** **Load more**: the next page after the last one read, appended. */
    fun loadMore(): Job? {
        val l = local.value
        val cursor = l.cursor ?: return null
        if (l.loadingMore || l.loading || !fleet.status.value.isConnected()) return null
        val generation = l.generation
        local.update { it.copy(loadingMore = true) }
        return scope.launch {
            try {
                val page = actions.review(cursor = cursor, limit = PAGE)
                local.update { now ->
                    // A re-read replaced the cards meanwhile: this page follows a list no longer showing.
                    if (now.generation != generation) return@update now
                    now.copy(
                        items = (now.items + page.items).distinctBy { it.reviewId.ifBlank { "link:${it.linkId}" } },
                        total = page.total,
                        cursor = page.nextCursor,
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update { now ->
                    if (now.generation != generation) now else now.copy(loadingMore = false, error = friendlyWork(t), conflict = false)
                }
            }
        }
    }

    /** **Confirm** a suggestion under its version — as the primary only for a session with none. */
    fun confirm(item: ReviewItem): Job? = decide(CONFIRM, undoOf(item, "Confirmed ${item.task.label}")) {
        decideOne(item, CONFIRM, primary = takesPrimary(item.sessionId, local.value.items))
    }

    /** **Reject** ("Not this") under its version. */
    fun reject(item: ReviewItem): Job? = decide(REJECT, undoOf(item, "Rejected ${item.task.label}")) {
        decideOne(item, REJECT, primary = null)
    }

    /** **Keep** a conflict (cross-org, unavailable) on purpose. */
    fun keep(item: ReviewItem): Job? = decide(ACK, null) {
        actions.ack(item.sessionId, item.linkId, expectedVersion = item.linkVersion)
        null
    }

    /** **Remove** a conflicting link: `unlink`, under its version. */
    fun remove(item: ReviewItem): Job? = decide(UNLINK, null) {
        actions.unlink(item.sessionId, item.linkId, expectedVersion = item.linkVersion)
        null
    }

    /** A session with no primary: make this link it — expecting none. */
    fun makePrimary(item: ReviewItem): Job? = decide(SET_PRIMARY, null) {
        actions.setPrimary(item.sessionId, item.linkId, expectedPrimary = 0)
        null
    }

    /** **Change…**: show the card's alternatives, or hide them. */
    fun toggleChange(item: ReviewItem) {
        local.update { it.copy(changing = if (it.changing == item.linkId) null else item.linkId) }
    }

    /**
     * Pick [alternative] instead: link the session to it — as the primary
     * only when the session has none, like Confirm — then reject the
     * suggestion under its version. The undo offered takes the rejection
     * back; the new link stays, like any link made by hand.
     */
    fun change(item: ReviewItem, alternative: ReviewAlternative): Job? {
        if (!allowed(fleet.capabilities.value, fleet.status.value, REJECT)) return null.also { refuseOffline(REJECT) }
        return decide(LINK, undoOf(item, "Changed to ${alternative.label}")) {
            val itemId = alternative.taskId.removePrefix(ITEM_PREFIX).takeIf { alternative.taskId.startsWith(ITEM_PREFIX) }?.toLongOrNull()
            val key = alternative.taskId.removePrefix(REF_PREFIX).takeIf { alternative.taskId.startsWith(REF_PREFIX) }
                ?: alternative.key
            val primary = takesPrimary(item.sessionId, local.value.items)
            if (itemId != null) {
                actions.link(item.sessionId, itemId = itemId, primary = primary)
            } else {
                actions.link(item.sessionId, key = key.orEmpty(), primary = primary)
            }
            decideOne(item, REJECT, primary = null)
        }
    }

    /** **Undo** the last decision: its link goes back to a suggestion, under the version the decision answered. */
    fun undo(): Job? {
        val offer = local.value.undo ?: return null
        return decide(RECONSIDER, null) {
            actions.reconsider(offer.sessionId, offer.linkId, expectedVersion = offer.version)
            null
        }
    }

    fun dismissUndo() {
        local.update { it.copy(undo = null) }
    }

    /**
     * **Confirm all shown**: every suggestion on screen, as one batch, each
     * under its own version. What the hub refused stays, with its reason on
     * its card; what it took leaves on the re-read. A session with no primary
     * gets one — its first decision here — and every other confirm is
     * secondary.
     */
    fun confirmAllShown(): Job? {
        val caps = fleet.capabilities.value
        if (local.value.busy) return null
        if (!allowed(caps, fleet.status.value, DECIDE_BATCH)) return null.also { refuseOffline(DECIDE_BATCH) }
        val items = local.value.items
        val batch = items.filter { it.kind == ReviewKind.Suggestion }.take(MAX_BATCH)
        if (batch.isEmpty()) return null
        val decisions = batchConfirmDecisions(batch) { takesPrimary(it, items) }
        local.update { it.copy(busy = true, error = null, conflict = false, undo = null) }
        return scope.launch {
            try {
                val result = actions.decideBatch(decisions)
                val failures = result.results.filter { !it.ok }.associate {
                    it.linkId to ReviewFailure(it.code, it.message?.takeIf { m -> m.isNotBlank() } ?: failureWords(it.code))
                }
                local.update { it.copy(failures = failures) }
                onChanged()
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK_LINK, DECIDE_BATCH)
                local.update { it.copy(error = friendlyWorkWrite(t), conflict = t.isConflict()) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    fun dismissError() {
        local.update { it.copy(error = null, conflict = false) }
    }

    /**
     * Whether a link made for [sessionId] now takes its primary: only when
     * the session has none — the inbox lists it as *no primary*, or its row
     * is on hand and carries no work (the row's `work` is its primary). A
     * session this phone has no row for is "unknown", and an unknown never
     * takes someone's primary.
     */
    private fun takesPrimary(sessionId: Long, items: List<ReviewItem>): Boolean =
        reviewSessionHasNoPrimary(sessionId, items) { id ->
            val row = fleet.sessions.value.firstOrNull { it.id == id }
            row != null && row.work == null
        }

    /**
     * One Confirm or Reject. Sent as a one-item `decide_batch` where the hub
     * has it, whose answer is the link's version afterwards — what *Undo*
     * sends back. A hub without it gets the single call, and the answer is
     * null: no version, so no Undo.
     */
    private suspend fun decideOne(item: ReviewItem, decision: String, primary: Boolean?): Long? {
        if (fleet.capabilities.value.has(WORK_LINK, DECIDE_BATCH)) {
            val result = try {
                actions.decideBatch(listOf(WorkDecision(item.sessionId, item.linkId, decision, expectedVersion = item.linkVersion, primary = primary)))
            } catch (e: HubError.Tool) {
                if (!e.isUnknownAction()) throw e
                fleet.actionMissing(WORK_LINK, DECIDE_BATCH)
                null
            }
            if (result != null) {
                val mine = result.results.firstOrNull { it.linkId == item.linkId }
                    ?: throw HubError.Tool("E_INTERNAL", "The hub did not answer this decision.")
                if (!mine.ok) throw HubError.Tool(mine.code ?: "E_REFUSED", mine.message?.takeIf { it.isNotBlank() } ?: failureWords(mine.code))
                return mine.version
            }
        }
        when (decision) {
            CONFIRM -> actions.confirm(item.sessionId, item.linkId, primary = primary, expectedVersion = item.linkVersion)
            else -> actions.reject(item.sessionId, item.linkId, expectedVersion = item.linkVersion)
        }
        return null
    }

    /**
     * One decision: gated (refused with a message while offline), never
     * shown as done before the hub answers, then the list is re-read. [call]
     * answers the link's version after it, when known; with [undoOf] that is
     * what an Undo is offered under. No version, no Undo.
     */
    private fun decide(action: String, undoOf: ((Long) -> UndoOffer)?, call: suspend () -> Long?): Job? {
        if (local.value.busy) return null
        if (!allowed(fleet.capabilities.value, fleet.status.value, action)) return null.also { refuseOffline(action) }
        local.update { it.copy(busy = true, error = null, conflict = false) }
        return scope.launch {
            try {
                val version = call()
                local.update { it.copy(undo = version?.let { v -> undoOf?.invoke(v) }, changing = null) }
                onChanged()
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (t is HubError.Tool && t.isUnknownAction()) fleet.actionMissing(WORK_LINK, action)
                local.update { it.copy(error = friendlyWorkWrite(t), conflict = t.isConflict()) }
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }
    private fun undoOf(item: ReviewItem, label: String): (Long) -> UndoOffer = { version ->
        UndoOffer(item.sessionId, item.linkId, label, version)
    }

    /** A decision this token and hub may make, tapped while offline: said, never silently dropped. */
    private fun refuseOffline(action: String) {
        if (canWrite && fleet.capabilities.value.has(WORK_LINK, action) && !fleet.status.value.isConnected()) {
            local.update { it.copy(error = OFFLINE_WRITE, conflict = false) }
        }
    }

    private fun allowed(caps: HubCapabilities, status: ConnectionStatus, action: String): Boolean =
        canWrite && status.isConnected() && caps.has(WORK_LINK, action)

    private fun assemble(caps: HubCapabilities, status: ConnectionStatus, l: Local): ReviewUiState {
        if (!caps.has(WORK, REVIEW)) return ReviewUiState()
        val connected = status.isConnected()
        val canBatch = allowed(caps, status, DECIDE_BATCH)
        return ReviewUiState(
            available = true,
            open = l.open,
            loading = l.loading,
            loaded = l.loaded,
            items = l.items,
            total = l.total,
            hasMore = l.cursor != null,
            loadingMore = l.loadingMore,
            connected = connected,
            stale = if (!connected && l.loaded) l.asOf?.let { staleLine(it, utcOffset(it)) } else null,
            canConfirm = allowed(caps, status, CONFIRM),
            canReject = allowed(caps, status, REJECT),
            canKeep = allowed(caps, status, ACK),
            canRemove = allowed(caps, status, UNLINK),
            canMakePrimary = allowed(caps, status, SET_PRIMARY),
            canChange = allowed(caps, status, LINK) && allowed(caps, status, REJECT),
            canBatch = canBatch,
            batchCount = if (canBatch) l.items.count { it.kind == ReviewKind.Suggestion }.coerceAtMost(MAX_BATCH) else 0,
            failures = l.failures,
            undo = l.undo,
            canUndo = l.undo != null && allowed(caps, status, RECONSIDER),
            changing = l.changing,
            busy = l.busy,
            error = l.error,
            conflict = l.conflict,
        )
    }

    private companion object {
        const val REVIEW = "review"
        const val CONFIRM = "confirm"
        const val REJECT = "reject"
        const val ACK = "ack"
        const val UNLINK = "unlink"
        const val LINK = "link"
        const val SET_PRIMARY = "set_primary"
        const val RECONSIDER = "reconsider"
        const val DECIDE_BATCH = "decide_batch"
        const val ITEM_PREFIX = "item:"
        const val REF_PREFIX = "ref:"
        const val PAGE = 50

        /** The most one read may ask for. */
        const val MAX_LIMIT = 200

        /** The hub's ceiling for one batch. */
        const val MAX_BATCH = 100
    }
}

/**
 * Whether [sessionId] has no primary, as far as the Review sheet can tell:
 * the inbox lists it as *no primary*, or [rowHasNoWork] says its row is on
 * hand and carries no work (a row's `work` is its primary link).
 */
internal fun reviewSessionHasNoPrimary(sessionId: Long, items: List<ReviewItem>, rowHasNoWork: (Long) -> Boolean): Boolean =
    items.any { it.sessionId == sessionId && it.kind == ReviewKind.NoPrimary } || rowHasNoWork(sessionId)

/**
 * *Confirm all shown*'s decisions, each under its own version. `primary` is
 * always explicit — the hub's default takes the primary — and true only for
 * the first decision of a session [hasNoPrimary] says has none: the one
 * primary that session gets, never a second decision stealing it back.
 */
internal fun batchConfirmDecisions(batch: List<ReviewItem>, hasNoPrimary: (Long) -> Boolean): List<WorkDecision> {
    val granted = mutableSetOf<Long>()
    return batch.map { item ->
        val primary = hasNoPrimary(item.sessionId) && granted.add(item.sessionId)
        WorkDecision(item.sessionId, item.linkId, "confirm", expectedVersion = item.linkVersion, primary = primary)
    }
}

/** A batch refusal with no message of its own, in words. */
internal fun failureWords(code: String?): String = when (code) {
    E_CONFLICT -> "Changed on another device — reload to see it now."
    "E_FORBIDDEN" -> "The hub refused this one."
    "E_NOTFOUND" -> "It is no longer there."
    else -> "The hub refused this one" + (code?.let { " ($it)" } ?: "") + "."
}
