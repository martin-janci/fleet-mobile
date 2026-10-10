package dev.claudefleet.mobile.android

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import dev.claudefleet.mobile.model.ChangedFile
import dev.claudefleet.mobile.model.CommitDetail
import dev.claudefleet.mobile.model.FileContent
import dev.claudefleet.mobile.model.FileDiff
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.RepoHandlers
import dev.claudefleet.mobile.ui.RepoScreen
import dev.claudefleet.mobile.ui.RepoTab
import dev.claudefleet.mobile.ui.RepoUiState
import dev.claudefleet.mobile.ui.RepoView
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** A session's worktree, drawn: the tabs, a change opening its diff, and a file's Send to Downloads. */
class RepoScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(state: RepoUiState, handlers: RepoHandlers = RepoHandlers()) {
        compose.setContent { FleetTheme { RepoScreen(state, handlers) } }
        compose.waitForIdle()
    }

    private val tabs = listOf(RepoTab.Changes, RepoTab.History, RepoTab.Files)

    @Test
    fun a_change_opens_its_diff() {
        val opened = mutableListOf<String>()
        show(
            RepoUiState(tabs = tabs, changes = listOf(ChangedFile("src/Main.kt", "M"))),
            RepoHandlers(onOpenDiff = { opened += it }),
        )
        compose.onNodeWithText("History").assertExists()
        // A row shows the file's name first and its folder under it.
        compose.onNodeWithText("src").assertExists()
        compose.onNodeWithText("Main.kt").performClick()
        assertEquals(listOf("src/Main.kt"), opened)
    }

    @Test
    fun a_diff_draws_its_lines() {
        show(RepoUiState(tabs = tabs, views = listOf(RepoView.Diff("a.kt", FileDiff("a.kt", "@@ -1 +1 @@\n-old line\n+new line")))))
        compose.onNodeWithText("-old line").assertExists()
        compose.onNodeWithText("+new line").assertExists()
    }

    @Test
    fun a_file_can_be_sent_to_downloads_by_a_token_that_may_write() {
        val sent = mutableListOf<String>()
        val file = RepoView.File("README.md", FileContent("README.md", "hello"))
        show(RepoUiState(tabs = tabs, views = listOf(file), canSendFile = true), RepoHandlers(onSendToDownloads = { sent += it }))
        compose.onNodeWithText("hello").assertExists()
        compose.onNodeWithText("Send to Downloads").performClick()
        assertEquals(listOf("README.md"), sent)
    }

    @Test
    fun a_readonly_token_is_not_offered_the_send() {
        val file = RepoView.File("README.md", FileContent("README.md", "hello"))
        show(RepoUiState(tabs = tabs, views = listOf(file), canSendFile = false))
        compose.onNodeWithText("Send to Downloads").assertDoesNotExist()
    }

    @Test
    fun a_not_pushed_commit_offers_ask_to_push_and_not_github() {
        val asked = mutableListOf<String>()
        val commit = RepoView.CommitView("9f3c2a1e", CommitDetail("9f3c2a1e", subject = "Hosts: show last ping", pushed = false))
        show(
            RepoUiState(
                session = SessionRow(id = 1, projectId = 4),
                project = ProjectRow(id = 4, owner = "acme", repo = "fleet-mobile"),
                tabs = tabs,
                views = listOf(commit),
            ),
            RepoHandlers(onAsk = { asked += it }),
        )
        compose.onNodeWithText("Open on GitHub").assertDoesNotExist()
        compose.onNodeWithText("Ask to push").performClick()
        assertEquals(listOf("Push this branch so commit 9f3c2a1 reaches the remote."), asked)
    }

    @Test
    fun a_text_file_offers_share_and_find() {
        val file = RepoView.File("Main.kt", FileContent("Main.kt", "val host = 1\nval hostName = host"))
        show(RepoUiState(tabs = tabs, views = listOf(file)))
        compose.onNodeWithText("Share").assertExists()
        compose.onNodeWithContentDescription("Find in file").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("host")
        compose.onNodeWithText("1 of 3").assertExists()
    }
}
