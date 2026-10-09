package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.DownloadActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.RepoActions
import dev.claudefleet.mobile.model.BranchDiff
import dev.claudefleet.mobile.model.ChangedFile
import dev.claudefleet.mobile.model.Commit
import dev.claudefleet.mobile.model.CommitDetail
import dev.claudefleet.mobile.model.FileContent
import dev.claudefleet.mobile.model.FileDiff
import dev.claudefleet.mobile.model.RepoTree
import dev.claudefleet.mobile.model.SessionRow
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

/** The three things a session's worktree screen shows. */
enum class RepoTab(val label: String) { Changes("Changes"), History("History"), Files("Files") }

/**
 * What is open over a tab, most recent last — a diff, a commit, one file of a
 * commit, a file. Back pops one; with none open, back leaves the screen.
 * A null payload is still loading.
 */
sealed interface RepoView {
    data class Diff(val path: String, val diff: FileDiff? = null) : RepoView
    data class CommitView(val hash: String, val detail: CommitDetail? = null) : RepoView
    data class CommitDiff(val hash: String, val path: String, val diff: FileDiff? = null) : RepoView
    data class File(val path: String, val content: FileContent? = null) : RepoView
}

data class RepoUiState(
    val session: SessionRow? = null,
    val tab: RepoTab = RepoTab.Changes,
    /** Which tabs the hub serves (`HubCapabilities.repo` / `repoLog` / `repoFiles`). */
    val tabs: List<RepoTab> = emptyList(),
    /** Null until read. */
    val changes: List<ChangedFile>? = null,
    /** How far the branch is from its remote and its base; null when unread or not served. */
    val branch: BranchDiff? = null,
    val log: List<Commit>? = null,
    /** The log's last page came back short: there is nothing older. */
    val logEnd: Boolean = false,
    val tree: RepoTree? = null,
    val query: String = "",
    val views: List<RepoView> = emptyList(),
    val loading: Boolean = false,
    val error: Friendly? = null,
    /** This token may ask the hub for a copy of a file (`send_file`). */
    val canSendFile: Boolean = false,
    val sending: Boolean = false,
    /** Said after a send: where the copy will appear. */
    val notice: String? = null,
) {
    val top: RepoView? get() = views.lastOrNull()

    /** The tree's entries narrowed by [query] — every word must appear in the path. */
    val shownEntries: List<String>
        get() {
            val words = query.lowercase().split(' ').filter { it.isNotBlank() }
            val all = tree?.entries.orEmpty()
            return if (words.isEmpty()) all else all.filter { p -> words.all { it in p.lowercase() } }
        }
}

/**
 * A session's worktree, read-only: what it changed (and each change's diff),
 * its commit history (and each commit's files and their diffs), and its files.
 * Each tab is read the first time it is shown and again on [refresh]. A file
 * can be sent to Downloads, the Files tab, where the phone can save it.
 */
class RepoViewModel(
    private val sessionId: Long,
    private val fleet: FleetState,
    private val actions: RepoActions,
    private val downloads: DownloadActions,
    private val scope: CoroutineScope,
    private val canWrite: Boolean,
) {
    private data class Local(
        val tab: RepoTab? = null,
        val changes: List<ChangedFile>? = null,
        val branch: BranchDiff? = null,
        val log: List<Commit>? = null,
        val logEnd: Boolean = false,
        val tree: RepoTree? = null,
        val query: String = "",
        val views: List<RepoView> = emptyList(),
        val loading: Boolean = false,
        val error: Friendly? = null,
        val sending: Boolean = false,
        val notice: String? = null,
    )

    private val local = MutableStateFlow(Local())

    val state: StateFlow<RepoUiState> =
        combine(local, fleet.sessions, fleet.capabilities) { l, rows, caps ->
            val tabs = buildList {
                if (caps.repo) add(RepoTab.Changes)
                if (caps.repoLog) add(RepoTab.History)
                if (caps.repoFiles) add(RepoTab.Files)
            }
            RepoUiState(
                session = rows.firstOrNull { it.id == sessionId },
                tab = l.tab?.takeIf { it in tabs } ?: tabs.firstOrNull() ?: RepoTab.Changes,
                tabs = tabs,
                changes = l.changes,
                branch = l.branch?.takeIf { caps.repoBranch },
                log = l.log,
                logEnd = l.logEnd,
                tree = l.tree,
                query = l.query,
                views = l.views,
                loading = l.loading,
                error = l.error,
                canSendFile = canWrite && caps.sendFile,
                sending = l.sending,
                notice = l.notice,
            )
        }.stateIn(scope, SharingStarted.Eagerly, RepoUiState())

    /** Read the first tab the hub serves. */
    fun load(): Job = scope.launch { readTab(state.value.tab) }

    fun select(tab: RepoTab): Job = scope.launch {
        local.update { it.copy(tab = tab, views = emptyList()) }
        val s = local.value
        val unread = when (tab) {
            RepoTab.Changes -> s.changes == null
            RepoTab.History -> s.log == null
            RepoTab.Files -> s.tree == null
        }
        if (unread) readTab(tab)
    }

    /** Read the shown tab again, and whatever is open over it. */
    fun refresh(): Job = scope.launch {
        readTab(state.value.tab)
        state.value.top?.let { reopen(it) }
    }

    fun setQuery(query: String) {
        local.update { it.copy(query = query) }
    }

    fun dismissError() {
        local.update { it.copy(error = null) }
    }

    fun dismissNotice() {
        local.update { it.copy(notice = null) }
    }

    /** Pop what is open; false when nothing was, so back leaves the screen. */
    fun back(): Boolean {
        if (local.value.views.isEmpty()) return false
        local.update { it.copy(views = it.views.dropLast(1)) }
        return true
    }

    fun openDiff(path: String): Job = open(RepoView.Diff(path))
    fun openCommit(hash: String): Job = open(RepoView.CommitView(hash))
    fun openCommitDiff(hash: String, path: String): Job = open(RepoView.CommitDiff(hash, path))
    fun openFile(path: String): Job = open(RepoView.File(path))

    /** The next page of the log. */
    fun moreLog(): Job = scope.launch {
        val held = local.value.log ?: return@launch
        if (local.value.logEnd || local.value.loading) return@launch
        guarded {
            val page = actions.log(sessionId, skip = held.size)
            local.update { it.copy(log = held + page, logEnd = page.size < LOG_PAGE) }
        }
    }

    /**
     * Ask the hub for a copy of [path] (`send_file`); it lands in the Files
     * tab, from where the phone saves or shares it.
     */
    fun sendToDownloads(path: String): Job = scope.launch {
        if (!state.value.canSendFile || local.value.sending) return@launch
        local.update { it.copy(sending = true, error = null, notice = null) }
        try {
            downloads.send(sessionId, path)
            local.update { it.copy(notice = "Sent to Downloads — it appears in the Files tab when the copy is ready.") }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        } finally {
            local.update { it.copy(sending = false) }
        }
    }

    private fun open(view: RepoView): Job = scope.launch {
        local.update { it.copy(views = it.views + view, error = null) }
        reopen(view)
    }

    /** Read [view]'s payload and put it in place of the entry for the same thing. */
    private suspend fun reopen(view: RepoView) = guarded {
        val loaded: RepoView = when (view) {
            is RepoView.Diff -> view.copy(diff = actions.diff(sessionId, view.path))
            is RepoView.CommitView -> view.copy(detail = actions.commit(sessionId, view.hash))
            is RepoView.CommitDiff -> view.copy(diff = actions.commitDiff(sessionId, view.hash, view.path))
            is RepoView.File -> view.copy(content = actions.file(sessionId, view.path))
        }
        local.update { l -> l.copy(views = l.views.map { if (it.sameAs(view)) loaded else it }) }
    }

    private suspend fun readTab(tab: RepoTab) = guarded {
        when (tab) {
            RepoTab.Changes -> {
                val changes = actions.changes(sessionId)
                local.update { it.copy(changes = changes) }
                // Ahead and behind (r09 A3): a line under the tab, so a hub
                // that cannot say leaves the changes as they are.
                if (fleet.capabilities.value.repoBranch) {
                    val branch = try {
                        actions.branch(sessionId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    local.update { it.copy(branch = branch) }
                }
            }
            RepoTab.History -> {
                val log = actions.log(sessionId)
                local.update { it.copy(log = log, logEnd = log.size < LOG_PAGE) }
            }
            RepoTab.Files -> {
                val tree = actions.tree(sessionId)
                local.update { it.copy(tree = tree) }
            }
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        local.update { it.copy(loading = true) }
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            local.update { it.copy(error = friendly(t)) }
        } finally {
            local.update { it.copy(loading = false) }
        }
    }

    private companion object {
        /** `repo_log`'s page, as [RepoActions.log] asks for it. */
        const val LOG_PAGE = 50
    }
}

/** The same thing open, whether or not its payload has loaded. */
private fun RepoView.sameAs(other: RepoView): Boolean = when (this) {
    is RepoView.Diff -> other is RepoView.Diff && other.path == path
    is RepoView.CommitView -> other is RepoView.CommitView && other.hash == hash
    is RepoView.CommitDiff -> other is RepoView.CommitDiff && other.hash == hash && other.path == path
    is RepoView.File -> other is RepoView.File && other.path == path
}
