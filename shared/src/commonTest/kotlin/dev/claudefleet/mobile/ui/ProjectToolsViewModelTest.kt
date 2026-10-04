package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.ProjectActions
import dev.claudefleet.mobile.model.GithubRepo
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.HostWorktrees
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.model.WorktreeRow
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

private class ProjFleet(tools: Set<String>) : FleetState {
    var refreshes = 0
    override val sessions = MutableStateFlow(emptyList<SessionRow>())
    override val hosts = MutableStateFlow(listOf(HostRow(alias = "pine", reachable = true)))
    override val projects = MutableStateFlow(emptyList<ProjectRow>())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.3"))
    override val hubVersion = MutableStateFlow<String?>("0.9.3")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(HubCapabilities(tools = tools))
    override suspend fun refresh() {
        refreshes += 1
    }
}

private class Projects : ProjectActions {
    val calls = mutableListOf<String>()
    var worktrees = HostWorktrees(hostAlias = "pine", projectId = 1, cloned = true, worktrees = listOf(WorktreeRow(id = 7, name = "feat-x", branch = "feat/x")))
    override suspend fun clone(hostAlias: String, url: String): ProjectRow {
        calls += "clone $hostAlias $url"
        return ProjectRow(id = 11, owner = "a", repo = "b")
    }
    override suspend fun create(hostAlias: String, owner: String, repo: String, onGithub: Boolean, confirm: String?): ProjectRow {
        calls += "create $owner/$repo github=$onGithub confirm=$confirm"
        if (onGithub && confirm == null) {
            throw HubError.Tool("E_CONFIRM_REQUIRED", "creating $owner/$repo on GitHub needs confirmation", buildJsonObject { put("confirm", JsonPrimitive("tok")) })
        }
        return ProjectRow(id = 12, owner = owner, repo = repo)
    }
    override suspend fun githubRepos(hostAlias: String) = listOf(GithubRepo("acme/app"))
    override suspend fun worktrees(hostAlias: String, projectId: Long): HostWorktrees {
        calls += "worktrees $hostAlias $projectId"
        return worktrees
    }
    override suspend fun deleteWorktree(worktreeId: Long) {
        calls += "delete $worktreeId"
        worktrees = worktrees.copy(worktrees = emptyList())
    }
}

private val ALL_PROJ = setOf(HubCapabilities.ADD_PROJECT, HubCapabilities.LIST_GITHUB_REPOS, HubCapabilities.LIST_HOST_WORKTREES, HubCapabilities.DELETE_WORKTREE)

class ProjectToolsViewModelTest {

    @Test
    fun a_clone_adds_the_project_and_hands_its_id_over() = runTest {
        val actions = Projects()
        val fleet = ProjFleet(ALL_PROJ)
        val vm = ProjectToolsViewModel(fleet, actions, backgroundScope, canWrite = true)
        vm.openAdd("pine").join()
        runCurrent()
        assertEquals(listOf("acme/app"), vm.state.value.repos?.map { it.nameWithOwner })
        var added: Long? = null

        vm.clone("https://github.com/acme/app ") { added = it }.join()
        runCurrent()

        assertEquals("clone pine https://github.com/acme/app", actions.calls.last())
        assertEquals(11L, added)
        assertNull(vm.state.value.addingOn)
        assertEquals(1, fleet.refreshes)
    }

    @Test
    fun a_github_creation_is_confirmed_with_the_hubs_token() = runTest {
        val actions = Projects()
        val vm = ProjectToolsViewModel(ProjFleet(ALL_PROJ), actions, backgroundScope, canWrite = true)
        vm.openAdd("pine").join()
        var added: Long? = null

        vm.create("acme", "new-app", onGithub = true) { added = it }.join()
        runCurrent()
        assertEquals(PendingCreate("acme", "new-app", "tok"), vm.state.value.pendingCreate)
        assertNull(added)

        vm.confirmCreate { added = it }.join()
        runCurrent()
        assertEquals("create acme/new-app github=true confirm=tok", actions.calls.last())
        assertEquals(12L, added)
    }

    @Test
    fun worktrees_are_read_for_the_host_and_project_and_one_can_be_deleted() = runTest {
        val actions = Projects()
        val vm = ProjectToolsViewModel(ProjFleet(ALL_PROJ), actions, backgroundScope, canWrite = true)
        vm.loadWorktrees("pine", 1).join()
        runCurrent()
        assertEquals(listOf(7L), vm.state.value.worktrees?.worktrees?.map { it.id })

        vm.deleteWorktree(7).join()
        runCurrent()
        assertEquals(listOf("worktrees pine 1", "delete 7", "worktrees pine 1"), actions.calls)
        assertEquals(emptyList(), vm.state.value.worktrees?.worktrees)
    }

    @Test
    fun a_readonly_pairing_reads_worktrees_but_adds_and_deletes_nothing() = runTest {
        val actions = Projects()
        val vm = ProjectToolsViewModel(ProjFleet(ALL_PROJ), actions, backgroundScope, canWrite = false)
        runCurrent()
        assertFalse(vm.state.value.canAdd)
        assertFalse(vm.state.value.canDeleteWorktree)
        vm.loadWorktrees("pine", 1).join()
        vm.deleteWorktree(7).join()
        runCurrent()
        assertEquals(listOf("worktrees pine 1"), actions.calls)
    }
}
