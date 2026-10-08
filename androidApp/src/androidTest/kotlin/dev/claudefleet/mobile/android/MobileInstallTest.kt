package dev.claudefleet.mobile.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.claudefleet.mobile.model.AgentInstall
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.ui.AgentInstallHandlers
import dev.claudefleet.mobile.ui.AgentInstallUiState
import dev.claudefleet.mobile.ui.INSTALLING_TAG
import dev.claudefleet.mobile.ui.InstallAgentSheet
import dev.claudefleet.mobile.ui.InstallingScreen
import dev.claudefleet.mobile.ui.NO_HUB_STEPS_TEXT
import dev.claudefleet.mobile.ui.NoHubScreen
import dev.claudefleet.mobile.ui.WELCOME_NO_HUB_TAG
import dev.claudefleet.mobile.ui.WELCOME_PAIR_TAG
import dev.claudefleet.mobile.ui.WelcomeScreen
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The MobileInstall board (redesign 14.19): the welcome, "no hub yet", the
 * install review, and the install followed to the first heartbeat.
 */
class MobileInstallTest {

    @get:Rule
    val compose = createComposeRule()

    private val pine = HostRow(alias = "pine", reachable = true, tmuxVersion = "3.4", sshAlias = "pine")

    @Test
    fun the_welcome_offers_pairing_and_a_way_for_someone_without_a_hub() {
        val taps = mutableListOf<String>()
        compose.setContent {
            FleetTheme { WelcomeScreen(onPair = { taps += "pair" }, onNoHub = { taps += "nohub" }) }
        }
        compose.onNodeWithText("Answer when a session needs you").assertExists()
        compose.onNodeWithTag(WELCOME_NO_HUB_TAG).performClick()
        compose.onNodeWithTag(WELCOME_PAIR_TAG).performClick()
        compose.waitForIdle()
        assertEquals(listOf("nohub", "pair"), taps)
    }

    @Test
    fun no_hub_yet_is_three_steps_that_can_be_sent_on() {
        val shared = mutableListOf<String>()
        var paired = false
        compose.setContent {
            FleetTheme { NoHubScreen(onBack = {}, onPair = { paired = true }, onShare = { shared += it }) }
        }
        compose.onNodeWithText("fleet-hub serve").assertExists()
        compose.onNodeWithText("Come back and scan").assertExists()
        compose.onNodeWithText("Send these steps").performClick()
        compose.onNodeWithText("I have a code, pair now").performClick()
        compose.waitForIdle()
        assertEquals(listOf(NO_HUB_STEPS_TEXT), shared)
        assertEquals(true, paired)
    }

    @Test
    fun the_review_says_what_gets_installed_and_installs_only_on_the_tap() {
        var installs = 0
        compose.setContent {
            FleetTheme {
                InstallAgentSheet(
                    AgentInstallUiState(alias = "pine", host = pine, hubVersion = "0.9.3", canInstall = true),
                    AgentInstallHandlers(onInstall = { installs++ }),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Add pine").assertExists()
        compose.onNodeWithText("What gets installed").assertExists()
        assertEquals(0, installs)
        compose.onNodeWithText("Install agent").performClick()
        compose.waitForIdle()
        assertEquals(1, installs)
    }

    @Test
    fun a_pairing_the_hub_does_not_offer_the_job_cannot_install() {
        var installs = 0
        compose.setContent {
            FleetTheme {
                InstallAgentSheet(
                    AgentInstallUiState(alias = "pine", host = pine, canInstall = false),
                    AgentInstallHandlers(onInstall = { installs++ }),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("This pairing cannot install", substring = true).assertExists()
        compose.onNodeWithText("Install agent").performClick()
        compose.waitForIdle()
        assertEquals(0, installs)
    }

    @Test
    fun installing_follows_the_job_to_the_first_heartbeat_and_can_be_left() {
        var job by mutableStateOf(AgentInstall(id = 9, hostAlias = "pine", version = "0.9.3", step = "download"))
        var left = false
        compose.setContent {
            FleetTheme {
                InstallingScreen(
                    AgentInstallUiState(alias = "pine", host = pine, job = job),
                    AgentInstallHandlers(onClose = { left = true }),
                )
            }
        }
        compose.onNodeWithTag(INSTALLING_TAG).assertExists()
        compose.onNodeWithText("Setting up pine").assertExists()
        compose.onNodeWithText("Copying fleet-agent 0.9.3").assertExists()

        job = job.copy(step = "connect")
        compose.waitForIdle()
        compose.onNodeWithText("Started the service").assertExists()
        compose.onNodeWithContentDescription("Waiting for the host to answer").assertExists()

        job = job.copy(step = "done", state = "done")
        compose.waitForIdle()
        compose.onNodeWithText("pine joined the fleet").assertExists()
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        assertEquals(true, left)
    }
}
