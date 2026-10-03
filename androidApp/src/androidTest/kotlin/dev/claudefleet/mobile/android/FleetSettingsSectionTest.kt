package dev.claudefleet.mobile.android

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.claudefleet.mobile.model.Danger
import dev.claudefleet.mobile.model.Page
import dev.claudefleet.mobile.model.PageTab
import dev.claudefleet.mobile.model.Section
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingKind
import dev.claudefleet.mobile.model.SettingProposal
import dev.claudefleet.mobile.ui.FleetSettingsSection
import dev.claudefleet.mobile.ui.FleetSettingsUiState
import dev.claudefleet.mobile.ui.PendingConfirm
import dev.claudefleet.mobile.ui.theme.FleetTheme
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * What the fleet-settings section actually DRAWS.
 *
 * Every other test of this screen is over the view model or the pure model, and
 * eight of this feature's findings were things the renderer did or did not put
 * on screen: the trust instruction shown before the hub had answered, a tab
 * structure flattened, `advanced` and `intro` and a notice's `tone` declared and
 * never read, "Change it on a desktop" on a field no desktop can change either,
 * and `restart: hooks` dropped. A composed test is the only thing that sees any
 * of it.
 */
class FleetSettingsSectionTest {

    @get:Rule
    val compose = createComposeRule()

    private fun field(key: String, widget: String? = null) = JsonObject(
        buildMap {
            put("type", JsonPrimitive("field"))
            put("key", JsonPrimitive(key))
            if (widget != null) put("widget", JsonPrimitive(widget))
        },
    )

    private fun notice(tone: String, text: String) = JsonObject(
        mapOf(
            "type" to JsonPrimitive("notice"),
            "tone" to JsonPrimitive(tone),
            "text" to JsonPrimitive(text),
        ),
    )

    private fun desc(
        key: String,
        label: String,
        type: String = "bool",
        restart: String = "none",
        ownedBy: String? = null,
    ) = SettingDescriptor(
        key = key,
        label = label,
        help = "What $label does.",
        kind = SettingKind(type = type),
        default = "false",
        value = "false",
        restart = restart,
        ownedBy = ownedBy,
    )

    private val page = Page(
        id = "settings.demo",
        title = "Demo",
        parent = "settings",
        layout = "category",
        tabs = listOf(
            PageTab(
                title = "First tab",
                sections = listOf(
                    Section(
                        title = "Plain",
                        intro = "Why this section exists.",
                        items = listOf(field("a.one"), notice("warn", "Mind this one."), notice("info", "Just so you know.")),
                    ),
                    Section(title = "Deep end", advanced = true, items = listOf(field("a.two"))),
                ),
            ),
            PageTab(title = "Second tab", sections = listOf(Section(title = "Elsewhere", items = listOf(field("a.three"))))),
        ),
    )

    private val descriptors = listOf(
        desc("a.one", "Hook setting", restart = "hooks"),
        desc("a.two", "Advanced setting"),
        desc("a.three", "Other setting"),
    ).associateBy { it.key }

    private fun show(state: FleetSettingsUiState) {
        compose.setContent {
            FleetTheme {
                // Scrollable, as `SettingsScreen` wraps it: without one a page
                // taller than the test device fails `assertIsDisplayed` on
                // whatever is below the fold, which is about the harness and
                // not about the screen.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    FleetSettingsSection(
                        state = state,
                        clientName = "phone",
                        onOpen = {},
                        onBack = {},
                        onSet = { _, _ -> },
                        onRefuse = { _, _ -> },
                        onDecide = { _, _ -> },
                        onConfirm = {},
                        onCancelConfirm = {},
                        onDismissError = {},
                        onRetry = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun loaded(openPage: String? = null) = FleetSettingsUiState(
        loaded = true,
        pages = listOf(page),
        descriptors = descriptors,
        values = descriptors.mapValues { it.value.value },
        canWrite = true,
        openPage = openPage,
    )

    /**
     * Before the hub has answered, the list says it is READING — not that the
     * operator must trust this device.
     *
     * `canWrite` defaults to false and is set at the end of a successful load,
     * so while the three reads were in flight, and for ever after a failure,
     * the section told an already-trusted operator to run a command they had
     * already run — and that was also what an empty catalogue looked like.
     */
    @Test
    fun the_list_says_it_is_reading_before_it_says_who_may_write() {
        show(FleetSettingsUiState(loading = true))

        compose.onNodeWithText("Reading the hub’s settings…").performScrollTo().assertIsDisplayed()
        assertTrue(
            "the trust instruction must wait for the hub's answer",
            compose.onAllNodesWithText("client trust", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** And a hub that answered with nothing says so, rather than looking unread. */
    @Test
    fun an_empty_catalogue_says_it_is_empty() {
        show(FleetSettingsUiState(loaded = true, canWrite = true))

        compose.onNodeWithText("The hub offers this device no settings pages.").performScrollTo().assertIsDisplayed()
    }

    /** Once it has answered, a trusted device is told what a change means. */
    @Test
    fun a_trusted_device_is_told_a_change_is_the_whole_fleets() {
        show(loaded())

        compose.onNodeWithText("The hub’s settings: a change here is the hub’s, for the whole fleet.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Demo").performScrollTo().assertIsDisplayed()
    }

    /**
     * One tab at a time, each by its own name — and an `advanced` section
     * closed, with its badge.
     */
    @Test
    fun a_page_draws_one_tab_with_its_title_and_folds_what_is_advanced() {
        show(loaded(openPage = "settings.demo"))

        compose.onNodeWithText("First tab").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Second tab").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("PLAIN").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Hook setting").performScrollTo().assertIsDisplayed()
        // The other tab's section is not drawn with it.
        assertTrue(
            "one tab at a time",
            compose.onAllNodesWithText("Other setting").fetchSemanticsNodes().isEmpty(),
        )
        // `advanced`: badged, and its field folded away.
        compose.onNodeWithText("ADVANCED").performScrollTo().assertIsDisplayed()
        assertTrue(
            "an advanced section opens closed",
            compose.onAllNodesWithText("Advanced setting").fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithText("DEEP END").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Advanced setting").performScrollTo().assertIsDisplayed()

        // The second tab, on a tap.
        compose.onNodeWithText("Second tab").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Other setting").performScrollTo().assertIsDisplayed()
        assertTrue(
            "and not the first tab's",
            compose.onAllNodesWithText("Hook setting").fetchSemanticsNodes().isEmpty(),
        )
    }

    /** A section's `intro` is drawn, and a notice's `tone` is visible as one. */
    @Test
    fun a_sections_intro_and_a_notices_tone_reach_the_screen() {
        show(loaded(openPage = "settings.demo"))

        compose.onNodeWithText("Why this section exists.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("⚠ Mind this one.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Just so you know.").performScrollTo().assertIsDisplayed()
    }

    /** `restart: hooks` is the wire's other non-`none` value, and it is said. */
    @Test
    fun a_hooks_restart_says_when_it_applies() {
        show(loaded(openPage = "settings.demo"))

        compose.onNodeWithText("Applies when the hosts’ hooks are next installed.", substring = true).performScrollTo().assertIsDisplayed()
    }

    /**
     * "Change it on a desktop." only where a desktop CAN.
     *
     * The sentence was gated on the composite `!editable`, which folds "this
     * kind needs a desktop" together with "this PAGE shows this field
     * read-only" — and a desktop renders a `readonly` widget as a plain span
     * too, so it could not be changed there either.
     */
    @Test
    fun a_page_locked_field_is_not_sent_to_a_desktop() {
        val locked = page.copy(
            tabs = listOf(
                PageTab(
                    title = "Only",
                    sections = listOf(Section(title = "Locked", items = listOf(field("a.one", widget = "readonly")))),
                ),
            ),
        )
        show(loaded(openPage = "settings.demo").copy(pages = listOf(locked)))

        compose.onNodeWithText("Hook setting").performScrollTo().assertIsDisplayed()
        assertTrue(
            "a desktop cannot change it either, so it must not be offered as the answer",
            compose.onAllNodesWithText("Change it on a desktop.", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** A kind the phone draws no control for IS a desktop's, and says so. */
    @Test
    fun a_kind_the_phone_cannot_edit_is_sent_to_a_desktop() {
        val mapKind = page.copy(
            tabs = listOf(
                PageTab(
                    title = "Only",
                    sections = listOf(Section(title = "Maps", items = listOf(field("a.map")))),
                ),
            ),
        )
        show(
            loaded(openPage = "settings.demo").copy(
                pages = listOf(mapKind),
                descriptors = descriptors + ("a.map" to desc("a.map", "Path map", type = "path_map")),
                values = descriptors.mapValues { it.value.value } + ("a.map" to "{}"),
            ),
        )

        compose.onNodeWithText("Change it on a desktop.", substring = true).performScrollTo().assertIsDisplayed()
    }

    /** An inline suggestion says WHO suggested it, as the Review page does. */
    @Test
    fun an_inline_suggestion_names_its_source() {
        show(
            loaded(openPage = "settings.demo").copy(
                proposals = listOf(
                    SettingProposal(
                        id = 4,
                        key = "a.one",
                        value = "true",
                        before = "false",
                        current = "false",
                        why = "it helps",
                        source = "agent",
                        sourceDetail = "control API",
                    ),
                ),
            ),
        )

        compose.onNodeWithText("Suggested by agent (control API)", substring = true).performScrollTo().assertIsDisplayed()
    }

    /** A confirm-level change asks, with the setting's own sentence. */
    @Test
    fun a_confirm_level_change_puts_the_settings_own_sentence_in_the_dialog() {
        val dangerous = desc("a.one", "Hook setting").copy(
            danger = Danger(level = "confirm", message = "It kills idle sessions."),
        )
        show(
            loaded(openPage = "settings.demo").copy(
                descriptors = descriptors + ("a.one" to dangerous),
                confirm = PendingConfirm(
                    key = "a.one",
                    value = "true",
                    label = "Hook setting",
                    message = "It kills idle sessions.",
                    proposalId = 4,
                ),
            ),
        )

        compose.onNodeWithText("It kills idle sessions.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Change it").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").performScrollTo().assertIsDisplayed()
    }
}
