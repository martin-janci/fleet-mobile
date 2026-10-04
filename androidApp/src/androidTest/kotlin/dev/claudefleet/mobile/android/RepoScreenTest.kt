package dev.claudefleet.mobile.android

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.claudefleet.mobile.model.ChangedFile
import dev.claudefleet.mobile.model.FileContent
import dev.claudefleet.mobile.model.FileDiff
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
        compose.onNodeWithText("src/Main.kt").performClick()
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
}
