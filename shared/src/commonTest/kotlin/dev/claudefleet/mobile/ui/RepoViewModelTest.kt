package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.DownloadActions
import dev.claudefleet.mobile.net.FetchedFile
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.RepoActions
import dev.claudefleet.mobile.model.ChangedFile
import dev.claudefleet.mobile.model.Commit
import dev.claudefleet.mobile.model.CommitDetail
import dev.claudefleet.mobile.model.Download
import dev.claudefleet.mobile.model.DownloadList
import dev.claudefleet.mobile.model.FileContent
import dev.claudefleet.mobile.model.FileDiff
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.RepoTree
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.HubCapabilities
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val S = 3L

private class RepoFleet(tools: Set<String>) : FleetState {
    override val sessions = MutableStateFlow(listOf(SessionRow(id = S, tmuxName = "s", hostAlias = "pine")))
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "pine", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(HubCapabilities(tools = tools))
    override suspend fun refresh() = Unit
}

private class Repo : RepoActions {
    val calls = mutableListOf<String>()
    var logPages = ArrayDeque(listOf(List(50) { Commit(hash = "h$it") }, listOf(Commit(hash = "last"))))
    override suspend fun changes(sessionId: Long): List<ChangedFile> {
        calls += "changes"
        return listOf(ChangedFile("src/a.kt", "M"))
    }
    override suspend fun diff(sessionId: Long, path: String): FileDiff {
        calls += "diff:$path"
        return FileDiff(path, "@@ -1 +1 @@\n-a\n+b")
    }
    override suspend fun log(sessionId: Long, skip: Int): List<Commit> {
        calls += "log:$skip"
        return logPages.removeFirstOrNull() ?: emptyList()
    }
    override suspend fun commit(sessionId: Long, hash: String) = CommitDetail(hash, files = listOf(ChangedFile("x", "A")))
    override suspend fun commitDiff(sessionId: Long, hash: String, path: String) = FileDiff(path, "+x")
    override suspend fun tree(sessionId: Long): RepoTree {
        calls += "tree"
        return RepoTree(listOf("src/Main.kt", "src/test/MainTest.kt", "README.md"))
    }
    override suspend fun file(sessionId: Long, path: String) = FileContent(path, "hello")
}

private class Downloads : DownloadActions {
    val sent = mutableListOf<String>()
    override suspend fun list(sessionId: Long?, limit: Int?): DownloadList = error("unused")
    override suspend fun send(sessionId: Long, path: String, note: String?): Download {
        sent += path
        return Download(id = 1)
    }
    override suspend fun remove(id: Long): Boolean = error("unused")
    override suspend fun fetch(download: Download, destination: String, onProgress: (Long, Long?) -> Unit): FetchedFile =
        error("unused")
    override suspend fun isCached(download: Download, destination: String): Boolean = error("unused")
}

private val ALL_REPO = setOf(
    HubCapabilities.REPO_CHANGES, HubCapabilities.REPO_DIFF, HubCapabilities.REPO_LOG, HubCapabilities.REPO_COMMIT,
    HubCapabilities.REPO_COMMIT_DIFF, HubCapabilities.REPO_TREE, HubCapabilities.REPO_FILE, HubCapabilities.SEND_FILE,
)

class RepoViewModelTest {

    @Test
    fun only_the_tabs_the_hub_serves_are_offered_and_the_first_is_read() = runTest {
        val repo = Repo()
        val vm = RepoViewModel(S, RepoFleet(setOf(HubCapabilities.REPO_TREE, HubCapabilities.REPO_FILE)), repo, Downloads(), backgroundScope, canWrite = true)
        runCurrent()
        vm.load().join()
        runCurrent()

        assertEquals(listOf(RepoTab.Files), vm.state.value.tabs)
        assertEquals(listOf("tree"), repo.calls)
    }

    @Test
    fun a_tab_is_read_once_until_refresh() = runTest {
        val repo = Repo()
        val vm = RepoViewModel(S, RepoFleet(ALL_REPO), repo, Downloads(), backgroundScope, canWrite = true)
        runCurrent()
        vm.load().join()
        vm.select(RepoTab.Files).join()
        vm.select(RepoTab.Changes).join()
        vm.select(RepoTab.Files).join()
        runCurrent()
        assertEquals(listOf("changes", "tree"), repo.calls)

        vm.refresh().join()
        runCurrent()
        assertEquals("tree", repo.calls.last())
    }

    @Test
    fun a_diff_opens_over_the_tab_and_back_closes_it() = runTest {
        val vm = RepoViewModel(S, RepoFleet(ALL_REPO), Repo(), Downloads(), backgroundScope, canWrite = true)
        runCurrent()
        vm.load().join()

        vm.openDiff("src/a.kt").join()
        runCurrent()
        val top = assertIs<RepoView.Diff>(vm.state.value.top)
        assertNotNull(top.diff)

        assertTrue(vm.back())
        runCurrent()
        assertEquals(null, vm.state.value.top)
        assertFalse(vm.back(), "nothing open: back leaves the screen")
    }

    @Test
    fun the_log_pages_until_a_short_page() = runTest {
        val repo = Repo()
        val vm = RepoViewModel(S, RepoFleet(ALL_REPO), repo, Downloads(), backgroundScope, canWrite = true)
        runCurrent()
        vm.select(RepoTab.History).join()
        runCurrent()
        assertFalse(vm.state.value.logEnd)

        vm.moreLog().join()
        runCurrent()
        assertEquals("log:50", repo.calls.last())
        assertEquals(51, vm.state.value.log?.size)
        assertTrue(vm.state.value.logEnd)
    }

    @Test
    fun find_narrows_the_tree_by_every_word() = runTest {
        val vm = RepoViewModel(S, RepoFleet(ALL_REPO), Repo(), Downloads(), backgroundScope, canWrite = true)
        runCurrent()
        vm.select(RepoTab.Files).join()
        vm.setQuery("src TEST")
        runCurrent()
        assertEquals(listOf("src/test/MainTest.kt"), vm.state.value.shownEntries)
    }

    @Test
    fun send_to_downloads_needs_a_token_that_may_write() = runTest {
        val readonly = Downloads()
        val ro = RepoViewModel(S, RepoFleet(ALL_REPO), Repo(), readonly, backgroundScope, canWrite = false)
        runCurrent()
        ro.sendToDownloads("a").join()
        assertTrue(readonly.sent.isEmpty())

        val full = Downloads()
        val vm = RepoViewModel(S, RepoFleet(ALL_REPO), Repo(), full, backgroundScope, canWrite = true)
        runCurrent()
        vm.sendToDownloads("README.md").join()
        runCurrent()
        assertEquals(listOf("README.md"), full.sent)
        assertNotNull(vm.state.value.notice)
    }

    @Test
    fun diff_lines_know_what_they_are() {
        val kinds = diffLines("diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-a\n+b\n c").map { it.kind }
        assertEquals(
            listOf(LineKind.Meta, LineKind.Meta, LineKind.Meta, LineKind.Hunk, LineKind.Removed, LineKind.Added, LineKind.Plain),
            kinds,
        )
        assertEquals("2026-10-04 18:10", shortDate("2026-10-04T18:10:13+02:00"))
        assertEquals("yesterday", shortDate("yesterday"))
    }
}
