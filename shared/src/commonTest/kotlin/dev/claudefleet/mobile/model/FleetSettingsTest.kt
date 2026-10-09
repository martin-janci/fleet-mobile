package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The fixture's shape: the hub's `list_pages` pages and its descriptors. */
@Serializable
private data class Registry(val pages: List<Page>, val descriptors: List<SettingDescriptor>)

private val registry: Registry by lazy { json.decodeFromString(Registry.serializer(), PAGES_REGISTRY_FIXTURE) }
private fun d(key: String) = registry.descriptors.single { it.key == key }

/**
 * The phone reads the hub's own pages and registry (claude-fleet declarative
 * pages P6) — the fixture is the hub's answer on a fresh store, so a renamed
 * field on the hub's side fails here rather than drawing an empty screen.
 */
class FleetSettingsTest {

    @Test
    fun the_hubs_pages_and_settings_parse_and_every_field_names_a_setting() {
        assertTrue(registry.pages.size > 5)
        val keys = registry.descriptors.map { it.key }.toSet()
        val fields = registry.pages.flatMap { p ->
            if (p.layout == "master_detail") emptyList()
            else p.allSections.flatMap { s -> s.items.map { PageItem.of(it) } }.filterIsInstance<PageItem.Field>()
        }
        assertTrue(fields.isNotEmpty())
        for (f in fields) assertTrue(f.key in keys, "${f.key} is placed but not described")
    }

    @Test
    fun a_phone_is_offered_the_settings_pages_with_fields_and_the_review_not_resources_or_data() {
        val offered = offeredPages(PagesBundle(registry.pages)).map { it.id }
        assertTrue("settings.automation" in offered, "$offered")
        assertTrue("settings.review" in offered, "$offered")
        assertFalse("settings.trackers" in offered, "a resource page is a desktop's")
        assertFalse("settings.orgs" in offered)
        assertFalse("usage" in offered, "a data page is a desktop's")
        assertFalse("settings" in offered, "the overview is the list itself")
        assertTrue("settings.updates" in offered, "$offered")
    }

    @Test
    fun a_field_the_page_marks_read_only_is_shown_not_edited() {
        // D32: Jev's work link is an offline benchmark until J1 passes.
        val field = registry.pages.flatMap { it.allSections }.flatMap { it.items }.map { PageItem.of(it) }
            .filterIsInstance<PageItem.Field>().single { it.key == "decide.jev.work_link" }
        assertTrue(field.readOnly)
    }

    @Test
    fun conditions_hold_as_the_desktop_evaluates_them() {
        val on = Condition(key = "gc.enabled", truthy = true)
        assertTrue(on.holds(mapOf("gc.enabled" to "true")))
        assertFalse(on.holds(mapOf("gc.enabled" to "false")))
        assertTrue(Condition(key = "k", oneOf = listOf("a", "b")).holds(mapOf("k" to " b ")))
        assertFalse(Condition(not = Condition(key = "k", eq = "a")).holds(mapOf("k" to "a")))
        assertTrue(Condition(all = listOf(on, Condition(key = "x", eq = "1"))).holds(mapOf("gc.enabled" to "true", "x" to "1")))
        assertTrue(Condition(any = listOf(on, Condition(key = "x", eq = "1"))).holds(mapOf("x" to "1")))
        assertTrue((null as Condition?).holds(emptyMap()))
    }

    @Test
    fun numbers_show_in_the_unit_the_field_names_and_go_back_to_stored_units() {
        val hours = d("gc.bg_idle_secs")
        assertEquals("hours", hours.unit)
        assertEquals("6", hours.toDisplay("21600"))
        assertEquals("1.5", hours.toDisplay("5400"))
        assertEquals(Result.success("21600"), hours.fromDisplay("6"))
        assertTrue(hours.fromDisplay("-1").isFailure)
        assertTrue(hours.fromDisplay("0.0001").isFailure, "rounds to 0, which means off")
        val days = d("work.recent_days")
        assertEquals(Result.success("3"), days.fromDisplay("3"))
        assertTrue(days.fromDisplay("2.5").isFailure, "a whole number")
        assertEquals("3 days", days.inWords("3"))
        assertTrue(days.rangeText().startsWith("1–365 days"), days.rangeText())
    }

    @Test
    fun values_read_in_words() {
        assertEquals("On", d("playbooks.press_enter").inWords("true"))
        assertEquals("Off", d("playbooks.press_enter").inWords("false"))
        assertEquals("(empty)", d("playbooks.press_enter").inWords(""))
        val layout = d("projects.layout")
        val first = layout.kind.options.first()
        assertEquals(layout.optionLabel(first), layout.inWords(first))
    }

    @Test
    fun a_confirmed_or_owned_setting_says_so_and_maps_are_a_desktops() {
        assertTrue(d("gc.enabled").danger.confirms)
        assertTrue(d("gc.enabled").danger.message.orEmpty().endsWith("."))
        assertTrue(d("mcp.port").readOnlyHere)
        assertFalse(d("projects.base_path").editableOnPhone, "a path map is edited on a desktop")
        assertTrue(d("work.recent_days").editableOnPhone)
    }
}

/** 11.9: the choice sets and the time range the notifications page uses. */
class NotifySettingKindsTest {
    private val set = SettingDescriptor(
        key = "notify.phone",
        label = "Phone",
        kind = SettingKind("choice_set", options = listOf("needs_you", "failed", "blocked", "done", "routine_failed")),
        optionLabels = listOf(listOf("needs_you", "Needs you"), listOf("failed", "Failed")),
    )
    private val range = SettingDescriptor(key = "notify.quiet_hours", label = "Quiet hours", kind = SettingKind("time_range"))

    @Test
    fun a_choice_set_ticks_in_the_settings_own_order() {
        assertEquals(setOf("needs_you", "failed"), choiceSetOf("needs_you, failed,"))
        assertEquals("needs_you,failed,done", set.withChoice("done,needs_you", "failed", on = true))
        assertEquals("done", set.withChoice("needs_you,done", "needs_you", on = false))
        assertEquals("", set.withChoice("failed", "failed", on = false))
        assertEquals("Needs you, Failed", set.inWords("needs_you,failed"))
    }

    @Test
    fun both_are_edited_on_the_phone_and_an_empty_range_is_none() {
        assertTrue(set.editableOnPhone)
        assertTrue(range.editableOnPhone)
        assertEquals("None", range.inWords(""))
        assertEquals("22:00–07:30", range.inWords("22:00-07:30"))
    }
}
