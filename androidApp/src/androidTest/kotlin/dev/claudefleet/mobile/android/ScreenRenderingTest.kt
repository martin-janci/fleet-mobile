package dev.claudefleet.mobile.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.ui.HostGroup
import dev.claudefleet.mobile.ui.HostLine
import dev.claudefleet.mobile.ui.HostsScreen
import dev.claudefleet.mobile.ui.HostsUiState
import dev.claudefleet.mobile.ui.ProjectGroup
import dev.claudefleet.mobile.ui.SessionsScreen
import dev.claudefleet.mobile.ui.SessionsUiState
import dev.claudefleet.mobile.ui.SettingsScreen
import dev.claudefleet.mobile.ui.SettingsUiState
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The three screens that had never been drawn anywhere.
 *
 * Until the deep-link tests, this repository had rendered no Compose at all;
 * afterwards it had rendered two screens. These are the rest. What a device
 * adds over the view-model tests is not the data — that is already covered —
 * but that the composition *runs*: that every `CompositionLocal` a screen
 * reaches for is provided, that a lazy list's keys do not collide, and that
 * nothing throws on a state the view model can actually produce.
 *
 * That is not a theoretical class of bug here. `StatusChip` reads
 * `LocalStatusColors`, a `staticCompositionLocalOf` whose default is
 * `error("FleetTheme is not applied")`, and two device tests written against a
 * branch where the bar did not yet draw one passed on that branch and threw
 * the moment they met main. Nothing off-device could have caught it.
 */
class SessionsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = SessionsUiState(
        groups = listOf(
            HostGroup(
                alias = "workbench",
                reachable = true,
                projects = listOf(
                    ProjectGroup(
                        projectId = 3,
                        label = "fleet-mobile",
                        sessions = listOf(
                            SessionRow(id = 1, tmuxName = "sess-1", friendlyName = "the deep link", hostAlias = "workbench", claudeStatus = "working"),
                            SessionRow(id = 2, tmuxName = "sess-2", hostAlias = "workbench", claudeStatus = "blocked"),
                        ),
                    ),
                    ProjectGroup(projectId = null, label = "No project", sessions = listOf(
                        SessionRow(id = 3, tmuxName = "shell-1", hostAlias = "workbench"),
                    )),
                ),
            ),
            HostGroup(alias = "laptop", reachable = false, projects = emptyList()),
        ),
        status = ConnectionStatus.Connected(hubVersion = "0.2.29"),
        // Seven rather than one: the count is asserted by its text, and "1"
        // is a substring of half the screen — a session id, a version, a
        // "1 session" label. A number nothing else on the screen can produce
        // is what makes the assertion mean what it says.
        attentionCount = 7,
        nowSeconds = 1_758_153_600,
    )

    private fun show(s: SessionsUiState) {
        compose.setContent {
            FleetTheme {
                SessionsScreen(state = s)
            }
        }
        compose.waitForIdle()
    }

    /**
     * The grouped list draws its headings and its rows.
     *
     * Two levels of grouping, a session with a friendly name and one without,
     * and a project-less shell session — the shapes the view model actually
     * emits, composed together, because a sticky-header lazy list is where a
     * duplicate key or a missing local would show up.
     */
    @Test
    fun the_grouped_fleet_list_draws() {
        show(state)

        compose.onNodeWithText("workbench", substring = true).assertIsDisplayed()
        compose.onNodeWithText("fleet-mobile", substring = true).assertIsDisplayed()
        compose.onNodeWithText("the deep link", substring = true).assertIsDisplayed()
        compose.onNodeWithText("sess-2", substring = true).assertIsDisplayed()
    }

    /** The attention filter says how many want a person. */
    @Test
    fun the_attention_filter_shows_its_count() {
        show(state)

        compose.onNodeWithText("Needs attention", substring = true).assertIsDisplayed()
        compose.onNodeWithText("7", substring = false).assertIsDisplayed()
    }

    /** An empty fleet is a state the view model emits, so it has to compose. */
    @Test
    fun an_empty_fleet_still_composes() {
        show(SessionsUiState(status = ConnectionStatus.Connected(hubVersion = "0.2.29")))

        compose.onNodeWithText("Sessions", substring = true).assertIsDisplayed()
    }
}

class HostsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(s: HostsUiState) {
        compose.setContent {
            FleetTheme {
                HostsScreen(state = s, onRefresh = {}, onDismissError = {}, onOpenHost = {})
            }
        }
        compose.waitForIdle()
    }

    /**
     * Reachable and unreachable have to look different, and both have to draw.
     *
     * The unreachable branch is the one that matters: it is the state somebody
     * opens this screen *to see*, and it is drawn in the error colour, which is
     * the half no off-device test can check at all.
     */
    @Test
    fun a_reachable_and_an_unreachable_host_both_draw() {
        show(
            HostsUiState(
                hosts = listOf(
                    HostLine("workbench", reachable = true, claudeVersion = "2.1.0", tmuxVersion = "3.4", sessions = 3, hidden = false, transport = "ssh"),
                    HostLine("laptop", reachable = false, claudeVersion = null, tmuxVersion = null, sessions = 0, hidden = false, transport = "agent"),
                ),
                status = ConnectionStatus.Connected(hubVersion = "0.2.29"),
            ),
        )

        compose.onNodeWithText("workbench", substring = true).assertIsDisplayed()
        compose.onNodeWithText("laptop", substring = true).assertIsDisplayed()

        // Exact, not substring: "reachable" is a substring of "unreachable",
        // so the loose form matches both rows and fails on the ambiguity —
        // which is what it did on the first run. The two labels are one word
        // apart by design, and a test for them has to be able to tell them
        // apart too.
        compose.onNodeWithText("reachable", substring = false).assertIsDisplayed()
        compose.onNodeWithText("unreachable", substring = false).assertIsDisplayed()
    }

    /** A host nobody has probed has no versions to show, and must not say "null". */
    @Test
    fun a_never_probed_host_does_not_print_null() {
        show(
            HostsUiState(
                hosts = listOf(HostLine("fresh", reachable = true, claudeVersion = null, tmuxVersion = null, sessions = 0, hidden = false, transport = "ssh")),
                status = ConnectionStatus.Connected(hubVersion = "0.2.29"),
            ),
        )

        compose.onNodeWithText("fresh", substring = true).assertIsDisplayed()
        val printedNull = compose.onAllNodesWithText("null", substring = true)
            .fetchSemanticsNodes()
            .size
        assertEquals("a missing version must not render as the word null", 0, printedNull)
    }

    @Test
    fun an_empty_host_list_says_so() {
        show(HostsUiState(status = ConnectionStatus.Connected(hubVersion = "0.2.29")))

        compose.onNodeWithText("No hosts", substring = true).assertIsDisplayed()
    }
}

class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(s: SettingsUiState) {
        compose.setContent {
            FleetTheme { SettingsScreen(state = s, onForget = {}, onDismissError = {}) }
        }
        compose.waitForIdle()
    }

    /**
     * A read-only device says so, in words.
     *
     * This is the one assertion on this screen that is about more than
     * layout. `readOnly` is what disables the prompt box, and the app's promise
     * is that it never calls a tool its token may not use — so the person
     * holding a read-only phone has to be able to find out *why* sending is
     * unavailable, rather than meeting a dead button.
     */
    @Test
    fun a_read_only_credential_is_explained_rather_than_just_enforced() {
        show(
            SettingsUiState(
                hub = "https://fleet.example.com",
                clientName = "phone",
                mode = "readonly",
                appVersion = "0.1.0",
            ),
        )

        compose.onNodeWithText("read only", substring = true).assertIsDisplayed()
        compose.onNodeWithText("cannot send prompts", substring = true).assertIsDisplayed()
    }

    /** And a full credential shows its mode rather than that sentence. */
    @Test
    fun a_full_credential_shows_its_mode() {
        show(
            SettingsUiState(
                hub = "https://fleet.example.com",
                clientName = "phone",
                mode = "full",
                appVersion = "0.1.0",
            ),
        )

        compose.onNodeWithText("full", substring = true).assertIsDisplayed()
        compose.onNodeWithText("https://fleet.example.com", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Forget this hub", substring = true).assertIsDisplayed()
    }
}
