package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.DownloadActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.downloadCachePath
import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.model.Download
import dev.claudefleet.mobile.model.humanBytes
import dev.claudefleet.mobile.model.relativeTime
import dev.claudefleet.mobile.net.DOWNLOAD_GONE
import dev.claudefleet.mobile.net.HubError
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

/** One hub download, as the Files tab draws it. */
data class FileLine(
    val id: Long,
    val name: String,
    /** "48 KB". */
    val size: String,
    /** "gpu-1 · fleet-report" — the session's name outlives the session. */
    val where: String,
    /** "4 min" — since it was asked for. */
    val age: String,
    val state: FileState,
    /** Why the hub could not copy it; [FileState.Failed] only. */
    val error: String? = null,
    val note: String? = null,
    /** Claude sent it (rather than a person picking it). */
    val fromAgent: Boolean = false,
)

enum class FileState { Fetching, Ready, Failed, Other }

/** The file a tap brought onto this phone, and what can be done with it. */
data class OpenedFile(val id: Long, val name: String, val path: String, val size: String)

/** A tap's transfer to this phone, while it runs. [total] null when the hub did not say. */
data class Transfer(val id: Long, val name: String, val received: Long, val total: Long?) {
    /** 0..1, or null when there is nothing to divide by. */
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (received.toFloat() / it).coerceIn(0f, 1f) }
}

data class FilesUiState(
    /** The hub keeps downloads at all (`list_downloads` listed). */
    val available: Boolean = false,
    val files: List<FileLine> = emptyList(),
    /** The hub has answered at least once since the tab opened. */
    val loaded: Boolean = false,
    val refreshing: Boolean = false,
    /** This token may forget a file: `full` and the hub lists `remove_download`. */
    val canRemove: Boolean = false,
    /** "1.2 GB of 2 GB" — the hub's budget, when it said. */
    val usage: String? = null,
    val transfer: Transfer? = null,
    val opened: OpenedFile? = null,
    /** What the platform offers for [opened]. */
    val handoffs: List<Handoff> = emptyList(),
    /** The file a Remove is waiting to be confirmed for. */
    val confirmRemove: FileLine? = null,
    /** A sentence that is not an error: "Saved to Downloads". */
    val notice: String? = null,
    val error: Friendly? = null,
) {
    val isEmpty: Boolean get() = loaded && files.isEmpty()
}

/**
 * The Files tab (claude-fleet file downloads): the hub's copies of files a
 * session sent, newest first, and the way one gets from the hub onto this
 * phone and out of the app.
 *
 * Re-reads `list_downloads` on opening, on a pull, and on every
 * `download:changed` (or resync) while the tab is showing — the frame carries
 * an id only, and the hub's answer is the one picture. A tap on a ready row
 * streams `GET /downloads/<id>` into the app's cache (or reuses a copy this
 * phone already checked), then offers the platform's verbs ([FileHandoff]).
 *
 * Removing is a write: offered only to a `full` token on a hub that lists
 * `remove_download`, and always asked about first — it is gone for every
 * device, not just this one.
 */
class FilesViewModel(
    private val fleet: FleetState,
    private val actions: DownloadActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
    private val handoff: FileHandoff,
    private val clock: () -> Long = ::epochSeconds,
) {
    private data class Local(
        val rows: List<Download> = emptyList(),
        val totalBytes: Long = 0,
        val maxTotalBytes: Long? = null,
        val loaded: Boolean = false,
        val refreshing: Boolean = false,
        val transfer: Transfer? = null,
        val opened: OpenedFile? = null,
        val confirmRemove: Long? = null,
        val notice: String? = null,
        /** An action's failure (a fetch, a hand-off, a remove): a later read does not clear it. */
        val error: Friendly? = null,
        /** The last read's failure: the next good read clears it. */
        val listError: Friendly? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<FilesUiState> =
        combine(fleet.capabilities, fleet.clockSkewSeconds, local) { caps, skew, l ->
            assemble(caps.downloads, canWrite && caps.removeDownload, skew, l)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            assemble(fleet.capabilities.value.downloads, false, 0, local.value),
        )

    private var follow: Job? = null
    private var loadJob: Job? = null
    private var transferJob: Job? = null

    /** The tab is showing: read now, and on every change the hub reports until [detach]. */
    fun attach() {
        if (follow?.isActive == true) return
        follow = scope.launch {
            launch {
                // Discovery lands a beat after `ready`, so the first read waits
                // for the hub to have said it keeps downloads at all.
                fleet.capabilities.map { it.downloads }.distinctUntilChanged().collect { if (it) reload() }
            }
            fleet.downloadChanges.throttleLatest(RELOAD_THROTTLE_MS).collect {
                if (fleet.capabilities.value.downloads) reload()
            }
        }
    }

    /** The tab is gone: stop following. The list stays for the next visit. */
    fun detach() {
        follow?.cancel()
        follow = null
    }

    /** Pull to refresh: the spinner stays until the hub has answered. */
    fun refresh(): Job = scope.launch {
        local.update { it.copy(refreshing = true) }
        load()
        local.update { it.copy(refreshing = false) }
    }

    fun reload(): Job {
        loadJob?.cancel()
        return scope.launch { load() }.also { loadJob = it }
    }

    private suspend fun load() {
        try {
            val list = actions.list(limit = LIST_LIMIT)
            local.update {
                it.copy(
                    rows = list.downloads,
                    totalBytes = list.totalBytes,
                    maxTotalBytes = list.maxTotalBytes,
                    loaded = true,
                    listError = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(loaded = true, listError = friendly(t)) }
        }
    }

    /**
     * A tap on a row. Ready: bring it onto this phone (once) and offer what
     * can be done with it. Anything else has nothing to fetch yet.
     */
    fun tap(id: Long): Job? {
        val row = local.value.rows.firstOrNull { it.id == id } ?: return null
        if (!row.isReady || local.value.transfer != null) return null
        val path = downloadCachePath(handoff.cacheDir, row)
        return scope.launch {
            try {
                if (!actions.isCached(row, path)) {
                    local.update { it.copy(transfer = Transfer(row.id, row.name, 0, row.size), error = null, notice = null) }
                    actions.fetch(row, path) { received, total ->
                        local.update { l -> l.copy(transfer = l.transfer?.copy(received = received, total = total ?: row.size)) }
                    }
                }
                local.update {
                    it.copy(transfer = null, opened = OpenedFile(row.id, row.name, path, humanBytes(row.size)))
                }
            } catch (e: CancellationException) {
                local.update { it.copy(transfer = null) }
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(transfer = null, error = fetchFailure(t)) }
                // Gone on the hub: the row on screen is stale too.
                if (t is HubError.Tool && t.code == DOWNLOAD_GONE) reload()
            }
        }.also { transferJob = it }
    }

    /** Stop a transfer this phone started; the partial copy is deleted by [DownloadActions.fetch]. */
    fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
        local.update { it.copy(transfer = null) }
    }

    /** Save / Share / Open the file a tap brought onto this phone. */
    fun handOff(action: Handoff): Job? {
        val opened = local.value.opened ?: return null
        return scope.launch {
            try {
                val said = handoff.perform(action, opened.path, opened.name)
                local.update { it.copy(opened = if (said != null) null else it.opened, notice = said) }
            } catch (e: CancellationException) {
                throw e
            } catch (f: HandoffFailed) {
                local.update { it.copy(error = Friendly("Could not ${action.label.lowercase()} the file", f.message ?: "", isError = true)) }
            } catch (t: Throwable) {
                local.update { it.copy(error = friendly(t)) }
            }
        }
    }

    fun closeOpened() {
        local.update { it.copy(opened = null) }
    }

    /** Ask before removing: it is gone for every device, not only this one. */
    fun askRemove(id: Long) {
        if (!mayRemove()) return
        local.update { it.copy(confirmRemove = id) }
    }

    fun cancelRemove() {
        local.update { it.copy(confirmRemove = null) }
    }

    fun confirmRemove(): Job? {
        val id = local.value.confirmRemove ?: return null
        if (!mayRemove()) return null
        local.update { it.copy(confirmRemove = null) }
        return scope.launch {
            try {
                actions.remove(id)
                // Gone either way (`removed: false` is "already gone").
                local.update { l -> l.copy(rows = l.rows.filterNot { it.id == id }, opened = l.opened?.takeIf { it.id != id }) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                local.update { it.copy(error = friendly(t)) }
            }
            reload()
        }
    }

    /** Read live, not off [state]: a write gate must not lag the hub's answer by a recomposition. */
    private fun mayRemove(): Boolean = canWrite && fleet.capabilities.value.removeDownload

    fun dismissError() {
        local.update { it.copy(error = null, listError = null) }
    }

    fun dismissNotice() {
        local.update { it.copy(notice = null) }
    }

    private fun assemble(available: Boolean, canRemove: Boolean, skew: Long, l: Local): FilesUiState {
        val now = clock() + skew
        return FilesUiState(
            available = available,
            files = l.rows.sortedWith(NEWEST_FIRST).map { it.toLine(now) },
            loaded = l.loaded,
            refreshing = l.refreshing,
            canRemove = canRemove,
            usage = l.maxTotalBytes?.takeIf { it > 0 }?.let { "${humanBytes(l.totalBytes)} of ${humanBytes(it)} on the hub" },
            transfer = l.transfer,
            opened = l.opened,
            handoffs = if (l.opened != null) handoff.offered else emptyList(),
            confirmRemove = l.confirmRemove?.let { id -> l.rows.firstOrNull { it.id == id }?.toLine(now) },
            notice = l.notice,
            error = l.error ?: l.listError,
        )
    }

    private companion object {
        /** The hub answers newest first; a phone needs no more than this many rows. */
        const val LIST_LIMIT = 200

        /** A copy flips `fetching` → `ready` in one burst of frames; one read covers it. */
        const val RELOAD_THROTTLE_MS = 500L
    }
}

/** Newest first, as the hub sends them — reimposed, so the order is the screen's own. */
private val NEWEST_FIRST: Comparator<Download> = compareByDescending<Download> { it.at }.thenByDescending { it.id }

internal fun Download.toLine(now: Long): FileLine = FileLine(
    id = id,
    name = name.ifBlank { path.substringAfterLast('/').ifBlank { "file $id" } },
    size = humanBytes(size),
    where = listOf(hostAlias, sessionName ?: sessionId?.let { "session $it" } ?: "").filter { it.isNotBlank() }.joinToString(" · "),
    age = relativeTime(at, now) ?: "",
    state = when {
        isReady -> FileState.Ready
        isFetching -> FileState.Fetching
        isFailed -> FileState.Failed
        else -> FileState.Other
    },
    error = error?.takeIf { isFailed && it.isNotBlank() },
    note = note?.takeIf { it.isNotBlank() },
    fromAgent = source == "agent",
)

/** A fetch's failure in words: a vanished file is not "this session is gone". */
private fun fetchFailure(t: Throwable): Friendly = when {
    t is HubError.Tool && t.code == DOWNLOAD_GONE ->
        Friendly("No longer available", t.message, isError = true, details = explain(t))
    t is HubError.Damaged ->
        Friendly("The file arrived damaged", "Nothing was kept. Tap it to try again.", isError = true, details = explain(t))
    else -> friendly(t)
}
