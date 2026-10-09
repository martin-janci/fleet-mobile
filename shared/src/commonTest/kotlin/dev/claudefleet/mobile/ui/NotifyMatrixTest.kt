package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.Condition
import dev.claudefleet.mobile.model.PageItem
import dev.claudefleet.mobile.model.SettingDescriptor
import dev.claudefleet.mobile.model.SettingKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The notifications page (claude-fleet 11.9) as the phone draws it. */
class NotifyMatrixTest {
    private val states = listOf("needs_you", "failed", "blocked", "done", "routine_failed")
    private val labels = listOf(listOf("needs_you", "Needs you"), listOf("failed", "Failed"), listOf("routine_failed", "Routine run failed"))

    private fun set(key: String, label: String, options: List<String> = states) =
        SettingDescriptor(key = key, label = label, kind = SettingKind("choice_set", options = options), optionLabels = labels)

    private val state = FleetSettingsUiState(
        loaded = true,
        descriptors = listOf(
            set("notify.desktop", "Desktop"),
            set("notify.phone", "Phone"),
            set("notify.sound", "Sound"),
            set("other.set", "Other", options = listOf("a", "b")),
            SettingDescriptor(key = "notify.quiet_hours", label = "Quiet hours", kind = SettingKind("time_range")),
            SettingDescriptor(key = "notify.quiet_except", label = "Through quiet hours", kind = SettingKind("choice_set", options = states), optionLabels = labels),
        ).associateBy { it.key },
        values = mapOf(
            "notify.phone" to "needs_you,failed,routine_failed",
            "notify.quiet_hours" to "22:00-07:30",
            "notify.quiet_except" to "failed",
        ),
    )

    private fun field(key: String, condition: Condition? = null) = PageItem.Field(key, null, condition)

    @Test
    fun a_matrix_is_its_choice_set_fields_over_the_same_options() {
        val cols = matrixColumns(state, listOf(field("notify.desktop"), field("notify.phone"), field("notify.sound")))
        assertEquals(listOf("Desktop", "Phone", "Sound"), cols?.map { it.label })
        assertNull(matrixColumns(state, listOf(field("notify.phone"))), "one column is a plain row")
        assertNull(matrixColumns(state, listOf(field("notify.phone"), field("other.set"))), "different options")
        assertNull(matrixColumns(state, listOf(field("notify.phone"), field("notify.quiet_hours"))), "not a choice set")
        assertNull(matrixColumns(state, listOf(field("notify.phone"), field("notify.gone"))), "a key this hub does not describe")
        assertNull(matrixColumns(state, listOf(field("notify.phone"), PageItem.Notice("info", "x"))), "only fields")
    }

    @Test
    fun this_phone_says_what_the_fleet_adds() {
        assertEquals(
            "The fleet sends this phone only: Needs you, Failed, Routine run failed. " +
                "Quiet hours 22:00–07:30, except Failed. Fleet settings › Notifications.",
            fleetNotifyLine(state),
        )
        assertEquals(
            "The fleet sends this phone only: nothing. Fleet settings › Notifications.",
            fleetNotifyLine(state.copy(values = mapOf("notify.phone" to ""))),
        )
        assertNull(fleetNotifyLine(FleetSettingsUiState()), "a hub before 11.9")
    }
}
