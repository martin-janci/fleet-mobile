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

    /**
     * A `secs` floor shown in a larger unit is a FRACTION of it. Dividing two
     * `Long`s truncated it, so a 900 s minimum read "at least 0 hours" — a
     * floor the hub refuses, advertised on the same row where the value itself
     * printed in correct fractional hours. The desktop shows 0.25
     * (`pages.ts`).
     */
    @Test
    fun a_secs_floor_shows_as_a_fraction_of_its_unit() {
        val every = d("update.check_interval_secs")
        assertEquals("hours", every.unit)
        assertEquals(900L, every.kind.min)
        assertEquals("at least 0.25 hours", every.rangeText().substringBefore(';'))
        // A whole multiple still reads as a whole number.
        val whole = d("update.check_interval_secs").let { it.copy(kind = it.kind.copy(min = 7200)) }
        assertEquals("at least 2 hours", whole.rangeText().substringBefore(';'))
    }

    @Test
    fun values_read_in_words() {
        assertEquals("On", d("playbooks.press_enter").inWords("true"))
        assertEquals("Off", d("playbooks.press_enter").inWords("false"))
        assertEquals("(empty)", d("playbooks.press_enter").inWords(""))
        // The LABEL, not `optionLabel(value)` again: `inWords`'s choice branch
        // IS `optionLabel(value)`, and `optionLabel` falls back to the raw
        // value — so if `@SerialName("option_labels")` stopped deserialising
        // both sides returned "github" and the test stayed green. The fixture
        // carries the real label.
        val layout = d("projects.layout")
        assertEquals("github: root/owner/repo", layout.inWords("github"))
        assertEquals("flat: root/repo", layout.inWords("flat"))
        assertEquals("nonesuch", layout.inWords("nonesuch"), "an unknown option is its own name")
        // And a choice_set reads every member by its label.
        val reasons = d("work.auto_tidy_reasons")
        assertEquals("Done and idle, PR merged, idle", reasons.inWords("done_idle,pr_merged_idle"))
        assertEquals("Done and idle", reasons.inWords(" done_idle "), "trimmed")

        // A `secs` value reads in its own unit, which no test asked for.
        assertEquals("1 hours", d("gc.bg_idle_secs").inWords("3600"))
        assertEquals("60 minutes", d("health.hooks_silent_secs").inWords("3600"))
    }

    /**
     * Both directions, at every factor the fixture carries, with the
     * non-multiples that were the whole problem.
     *
     * The conversion was tested at ONE factor (hours) on three inputs, plus a
     * line on `work.recent_days` — which is `kind: int`, so its factor is 1 and
     * that line exercises the identity path, not seconds→days. A table would
     * have caught the two divergences from `pages.ts` this now pins: wholeness
     * tested before rounding (so `7199` s in hours printed "2.0" where the
     * desktop prints "2") and ties-to-even instead of half-up (`450` s in hours
     * is exactly 0.125 → exactly 12.5 scaled, so "0.12" against "0.13").
     */
    @Test
    fun seconds_convert_both_ways_at_every_factor_the_hub_uses() {
        val hours = d("gc.bg_idle_secs")
        val minutes = d("health.hooks_silent_secs")
        assertEquals(3600L, hours.unitFactor())
        assertEquals(60L, minutes.unitFactor())

        // (descriptor, stored, shown) — shown is what a person reads and types.
        val table = listOf(
            Triple(minutes, "0", "0"),
            Triple(minutes, "60", "1"),
            Triple(minutes, "3600", "60"),
            Triple(minutes, "90", "1.5"),
            Triple(hours, "0", "0"),
            Triple(hours, "3600", "1"),
            Triple(hours, "5400", "1.5"),
            Triple(hours, "21600", "6"),
            // Half-up, not ties-to-even: exactly 12.5 hundredths.
            Triple(hours, "450", "0.13"),
            // Whole AFTER rounding, so no trailing ".0".
            Triple(hours, "7199", "2"),
            Triple(hours, "3601", "1"),
            Triple(hours, "315360000", "87600"),
        )
        for ((desc, stored, shown) in table) {
            assertEquals(shown, desc.toDisplay(stored), "${desc.key}: $stored shows as")
        }

        // A whole number of the shown unit round-trips exactly, both ways.
        for ((desc, stored) in listOf(
            minutes to "0", minutes to "60", minutes to "3600", minutes to "90",
            hours to "0", hours to "3600", hours to "5400", hours to "21600",
        )) {
            val shown = desc.toDisplay(stored)
            assertEquals(
                Result.success(stored),
                desc.fromDisplay(shown),
                "${desc.key}: $stored → $shown → back",
            )
        }

        // And where it does NOT: two decimals cannot hold every second, so a
        // non-multiple comes back rounded. Pinned, not claimed as exact — the
        // desktop's `pages.ts` loses the same seconds, and Save is gated on
        // `draft != shown`, so an untouched field is never written back.
        assertEquals(Result.success("7200"), hours.fromDisplay(hours.toDisplay("7199")))
        assertEquals(Result.success("288"), hours.fromDisplay(hours.toDisplay("300")))
    }

    /**
     * `fromDisplay` refuses what is not a number — including Kotlin's own float
     * suffixes.
     *
     * `String.toDouble` reads `"2d"`, `"2f"` and `"2D"` as 2.0, so a typo went
     * to the hub as a valid value where the desktop's `Number("2d")` is NaN and
     * refuses it. On an hours field "2d" silently meant two HOURS. It also
     * parses differently on Android and on Kotlin/Native.
     */
    @Test
    fun a_typed_number_is_digits_and_at_most_one_point() {
        val hours = d("gc.bg_idle_secs")
        for (bad in listOf("2d", "2f", "2D", "2F", "1e3", "0x10", "+2", " 2 2 ", "two", "", "   ", ".5", "2.", "-1", "NaN", "Infinity")) {
            assertTrue(hours.fromDisplay(bad).isFailure, "`$bad` is not a number a field may send")
        }
        for (good in listOf("0", "2", "0.5", "1.25", " 6 ")) {
            assertTrue(hours.fromDisplay(good).isSuccess, "`$good` is one")
        }
    }

    /**
     * [PageItem.of] on items the hub would not send.
     *
     * The parser is defensive everywhere except the `when` decode, which threw
     * `SerializationException` on a wrong-typed member — inside composition, so
     * a UI crash rather than an empty row. A condition that cannot be read is
     * now no condition, which is how a MISSING one already behaves.
     */
    @Test
    fun a_malformed_item_is_parsed_rather_than_thrown() {
        fun item(vararg pairs: Pair<String, kotlinx.serialization.json.JsonElement>) =
            kotlinx.serialization.json.JsonObject(pairs.toMap())
        fun s(v: String) = kotlinx.serialization.json.JsonPrimitive(v)

        val noKey = PageItem.of(item("type" to s("field")))
        assertEquals(PageItem.Field("", null, null), noKey)

        val numberKey = PageItem.of(item("type" to s("field"), "key" to kotlinx.serialization.json.JsonPrimitive(7)))
        assertEquals("", (numberKey as PageItem.Field).key, "a non-string key is no key")

        assertEquals(PageItem.Elsewhere("source"), PageItem.of(item("type" to s("source"))))
        assertEquals(PageItem.Elsewhere(""), PageItem.of(item()), "no type at all")

        // A `when` that is not an object, and one whose member is the wrong
        // type: neither throws, and the field is SHOWN (no condition means
        // "always holds"), not silently hidden.
        val notObject = PageItem.of(item("type" to s("field"), "key" to s("k"), "when" to s("nonsense")))
        assertEquals(null, (notObject as PageItem.Field).condition)
        val badMember = PageItem.of(
            kotlinx.serialization.json.JsonObject(
                mapOf(
                    "type" to s("field"),
                    "key" to s("k"),
                    "when" to kotlinx.serialization.json.JsonObject(mapOf("eq" to kotlinx.serialization.json.JsonPrimitive(7))),
                ),
            ),
        )
        assertEquals(null, (badMember as PageItem.Field).condition)
        assertTrue(badMember.condition.holds(emptyMap()), "shown, not hidden")
    }

    /**
     * A page's tabs are a structure, not a bag of sections.
     *
     * `allSections` flattened them, so `settings.work` — tabs-only (Detection /
     * Tidy-up / Retention) — drew its six sections run together with the three
     * tab names gone, and a tab's own `when` was never evaluated.
     */
    @Test
    fun a_pages_tabs_keep_their_titles_and_their_conditions() {
        val work = registry.pages.single { it.id == "settings.work" }
        assertTrue(work.tabs.size > 1, "the fixture's tabs-only page")
        assertEquals(emptyList(), work.sections, "it places nothing outside a tab")

        val titles = work.shownTabs(emptyMap()).map { it.title }
        assertEquals(work.tabs.map { it.title }, titles, "no tab carries a condition today")

        // One tab at a time, and the page's own sections with it.
        val first = work.shownSections(emptyMap(), 0)
        val second = work.shownSections(emptyMap(), 1)
        assertEquals(work.tabs[0].sections, first)
        assertEquals(work.tabs[1].sections, second)
        assertTrue(first != second, "a tab is not every tab")
        assertEquals(work.allSections.size, work.tabs.sumOf { it.sections.size })

        // A tab whose condition fails is not offered, and index 0 is then the
        // first one that IS.
        val gated = work.copy(
            tabs = listOf(work.tabs[0].copy(condition = Condition(key = "k", eq = "no"))) + work.tabs.drop(1),
        )
        assertEquals(work.tabs.drop(1).map { it.title }, gated.shownTabs(mapOf("k" to "yes")).map { it.title })
        assertEquals(work.tabs[1].sections, gated.shownSections(mapOf("k" to "yes"), 0))

        // An out-of-range tab reads as the first, never as a crash.
        assertEquals(work.tabs[0].sections, work.shownSections(emptyMap(), 99))
        // A page with no tabs is its own sections.
        val flat = registry.pages.single { it.id == "settings.automation" }
        assertEquals(flat.sections, flat.shownSections(emptyMap()))
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
