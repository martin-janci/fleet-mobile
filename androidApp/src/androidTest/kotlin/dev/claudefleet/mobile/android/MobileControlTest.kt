package dev.claudefleet.mobile.android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.claudefleet.mobile.data.ChatFormActions
import dev.claudefleet.mobile.model.ConfirmRequest
import dev.claudefleet.mobile.model.FormDraft
import dev.claudefleet.mobile.model.FormView
import dev.claudefleet.mobile.model.PendingForm
import dev.claudefleet.mobile.ui.CONFIRM_APPROVE_TAG
import dev.claudefleet.mobile.ui.CONFIRM_DENY_TAG
import dev.claudefleet.mobile.ui.CONTROL_VIEW_TAG
import dev.claudefleet.mobile.ui.CONTROL_WAKE_TAG
import dev.claudefleet.mobile.ui.ChatFormCard
import dev.claudefleet.mobile.ui.ChatFormDraftCard
import dev.claudefleet.mobile.ui.ConfirmCards
import dev.claudefleet.mobile.ui.ControlUiState
import dev.claudefleet.mobile.ui.ControlViews
import dev.claudefleet.mobile.ui.ControlWaiting
import dev.claudefleet.mobile.ui.HANDOFF_CHIP_TAG
import dev.claudefleet.mobile.ui.HandoffChip
import dev.claudefleet.mobile.ui.HandoffChips
import dev.claudefleet.mobile.ui.theme.FleetTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The MobileControl and MobileChatForms boards (redesign 9.8, 14.7):
 * Control's confirms, the tab before Control runs, and a chat form's states.
 */
class MobileControlTest {

    @get:Rule
    val compose = createComposeRule()

    private val confirms = listOf(
        ConfirmRequest(nonce = "n1", tool = "kill_session", summary = "Stop pine/api", operator = true),
        ConfirmRequest(nonce = "n2", tool = "merge_pr", summary = "Merge #12", caller = "ci-bot"),
    )

    @Test
    fun a_confirm_is_answered_only_by_the_tap_on_it() {
        val answers = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            FleetTheme {
                ConfirmCards(ControlUiState(available = true, canConfirm = true, confirms = confirms)) { n, ok -> answers += n to ok }
            }
        }
        compose.onNodeWithText("Control asks").assertExists()
        compose.onNodeWithText("ci-bot asks").assertExists()
        compose.onNodeWithText("Stop pine/api").assertExists()
        assertEquals(emptyList<Pair<String, Boolean>>(), answers)
        compose.onNodeWithTag(CONFIRM_APPROVE_TAG + "n1").performClick()
        compose.onNodeWithTag(CONFIRM_DENY_TAG + "n2").performClick()
        compose.waitForIdle()
        assertEquals(listOf("n1" to true, "n2" to false), answers)
    }

    @Test
    fun a_session_chip_shows_its_state_and_opens_the_session() {
        val opened = mutableListOf<Long>()
        compose.setContent {
            FleetTheme {
                HandoffChips(
                    listOf(
                        HandoffChip(1, "Sent to api", "working", 3),
                        HandoffChip(2, "Mission Release", "done"),
                    ),
                    onOpenSession = { opened += it },
                )
            }
        }
        compose.onNodeWithText("Sent to api").assertExists()
        compose.onNodeWithText("Working").assertExists()
        compose.onNodeWithTag(HANDOFF_CHIP_TAG + 1).performClick()
        compose.onNodeWithTag(HANDOFF_CHIP_TAG + 2).performClick()
        compose.waitForIdle()
        assertEquals(listOf(3L), opened)
    }

    @Test
    fun a_control_that_is_not_running_is_woken_by_the_button_and_the_views_open_their_screens() {
        var wakes = 0
        val opened = mutableListOf<String>()
        compose.setContent {
            FleetTheme {
                ControlWaiting(
                    state = ControlUiState(available = true, known = true, blocked = "absent"),
                    subtitle = "4 need you",
                    views = { ControlViews(22, 3, onSessions = { opened += "sessions" }, onMissions = { opened += "missions" }) },
                    onWake = { wakes++ },
                    onAnswer = { _, _ -> },
                    onDismissError = {},
                )
            }
        }
        compose.onNodeWithText("4 need you").assertExists()
        compose.onNodeWithText("Control is not running. Wake it to start the chat.").assertExists()
        compose.onNodeWithTag(CONTROL_WAKE_TAG).performClick()
        compose.onNodeWithText("Sessions 22").performClick()
        compose.onNodeWithTag(CONTROL_VIEW_TAG + "Missions").performClick()
        compose.waitForIdle()
        assertEquals(1, wakes)
        assertEquals(listOf("sessions", "missions"), opened)
    }

    @Test
    fun a_revoked_token_offers_no_wake() {
        compose.setContent {
            FleetTheme {
                ControlWaiting(
                    state = ControlUiState(available = true, known = true, blocked = "token_revoked"),
                    subtitle = null,
                    views = {},
                    onWake = {},
                    onAnswer = { _, _ -> },
                    onDismissError = {},
                )
            }
        }
        compose.onNodeWithText("Settings › Devices", substring = true).assertExists()
        compose.onNodeWithTag(CONTROL_WAKE_TAG).assertDoesNotExist()
    }

    private fun spec(steps: Int): JsonElement = Json.parseToJsonElement(
        """{"spec":"fleet.form/1","title":"Deploy","submit":"Deploy","steps":[""" +
            (1..steps).joinToString(",") { """{"title":"Step $it","fields":[{"name":"f$it","type":"text","label":"Field $it","required":true}]}""" } +
            "]}",
    )

    private class Forms(var view: FormView) : ChatFormActions {
        val answered = mutableListOf<Map<String, JsonElement>>()
        val declined = mutableListOf<String?>()
        override suspend fun get(formId: String) = view
        override suspend fun answer(formId: String, values: Map<String, JsonElement>): FormView {
            answered += values
            return view.copy(state = "answered", answeredBy = "you").also { view = it }
        }
        override suspend fun decline(formId: String, note: String?): FormView {
            declined += note
            return view.copy(state = "declined", note = note).also { view = it }
        }
    }

    private fun form(steps: Int) = FormView(formId = "f", title = "Deploy", spec = spec(steps), why = "I need a target")

    @Test
    fun a_one_step_form_is_answered_inline_and_folds_to_one_line() {
        val forms = Forms(form(1))
        compose.setContent {
            FleetTheme { ChatFormCard(PendingForm("f", "Deploy"), "Control", forms, canAnswer = true, orbit = true) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("I need a target").assertExists()
        compose.onNodeWithText("Field 1 *").performTextInput("api")
        compose.onNodeWithTag("form-answer").performClick()
        compose.waitForIdle()
        assertEquals(1, forms.answered.size)
        compose.onNodeWithText("✓ Deploy · answered by you").assertExists()
    }

    @Test
    fun decline_sends_the_note_back() {
        val forms = Forms(form(1))
        compose.setContent {
            FleetTheme { ChatFormCard(PendingForm("f", "Deploy"), "Control", forms, canAnswer = true, orbit = true) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Decline…").performClick()
        compose.onNodeWithText("Why not (optional)").performTextInput("not today")
        compose.onNodeWithTag("form-decline").performClick()
        compose.waitForIdle()
        assertEquals(listOf<String?>("not today"), forms.declined)
        compose.onNodeWithText("✕ Deploy · declined", substring = true).assertExists()
        compose.onNodeWithText("“not today”").assertExists()
    }

    @Test
    fun a_long_form_goes_one_step_at_a_time_and_its_button_names_what_is_missing() {
        val forms = Forms(form(4))
        compose.setContent {
            FleetTheme { ChatFormCard(PendingForm("f", "Deploy"), "Control", forms, canAnswer = true, orbit = true) }
        }
        compose.waitForIdle()
        compose.onNodeWithText("4 steps").assertExists()
        compose.onNodeWithTag("form-open").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Step 1 of 4").assertExists()
        compose.onNodeWithText("Fill in Field 1").assertExists()
        for (i in 1..4) {
            compose.onNodeWithText("Field $i *").performTextInput("v$i")
            compose.onNodeWithTag("form-next").performClick()
            compose.waitForIdle()
        }
        assertEquals(1, forms.answered.size)
        assertEquals(4, forms.answered.single().size)
    }

    @Test
    fun a_form_still_being_read_shows_building_with_the_atom() {
        val never = object : ChatFormActions {
            override suspend fun get(formId: String): FormView = kotlinx.coroutines.awaitCancellation()
            override suspend fun answer(formId: String, values: Map<String, JsonElement>) = error("not answered")
            override suspend fun decline(formId: String, note: String?) = error("not declined")
        }
        compose.setContent {
            FleetTheme { ChatFormCard(PendingForm("f", "Deploy"), "Control", never, canAnswer = true, orbit = true) }
        }
        compose.onNodeWithText("Deploy").assertExists()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Building a form · from Control").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("Building").assertExists()
    }

    @Test
    fun a_form_still_being_written_lets_its_first_field_be_filled_while_the_rest_arrives() {
        val text = """{"spec":"fleet.form/1","title":"Start Papaya receipts","steps":[{"title":"Project","fields":[""" +
            """{"name":"project","type":"text","label":"Project name","required":true},""" +
            """{"name":"host","type":"select","label":"Host","options":[["mercury","mer"""
        var values by mutableStateOf(mapOf<String, JsonElement>())
        compose.setContent {
            FleetTheme {
                ChatFormDraftCard(
                    draft = FormDraft(text, why = "the Jira epic PD-3100", updatedAt = 0),
                    values = values,
                    onChange = { n, v -> values = if (v == null) values - n else values + (n to v) },
                    canAnswer = true,
                )
            }
        }
        compose.onNodeWithText("Start Papaya receipts").assertExists()
        compose.onNodeWithText("Writing the form · reading the Jira epic PD-3100").assertExists()
        compose.onNodeWithContentDescription("Building").assertExists()
        // The select's options are still arriving: a skeleton under its label.
        compose.onNodeWithTag("form-draft-host").assertExists()
        compose.onNodeWithTag("form-draft-rest").assertExists()
        compose.onNodeWithText("You can fill this in while the rest arrives.").assertExists()
        compose.onNodeWithText("Project name *").performTextInput("papaya-receipts")
        compose.waitForIdle()
        assertEquals(JsonPrimitive("papaya-receipts"), values["project"])
    }

    @Test
    fun what_was_filled_in_the_draft_starts_the_open_form() {
        val forms = Forms(form(1))
        compose.setContent {
            FleetTheme {
                ChatFormCard(PendingForm("f", "Deploy"), "Control", forms, canAnswer = true, orbit = true, seed = mapOf("f1" to JsonPrimitive("api")))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("form-answer").performClick()
        compose.waitForIdle()
        assertEquals(mapOf<String, JsonElement>("f1" to JsonPrimitive("api")), forms.answered.single())
    }

    @Test
    fun an_expired_form_offers_ask_again() {
        var asked = 0
        val forms = Forms(form(1).copy(state = "expired"))
        compose.setContent {
            FleetTheme {
                ChatFormCard(PendingForm("f", "Deploy"), "Control", forms, canAnswer = true, open = false, orbit = true, onAskAgain = { asked++ })
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("◷ Deploy · expired").assertExists()
        compose.onNodeWithText("Ask again").performClick()
        compose.waitForIdle()
        assertEquals(1, asked)
    }
}
