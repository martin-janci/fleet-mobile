package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.Page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsGroupsTest {

    /** Every page the hub served the phone on 2026-10-08 (claude-fleet `crates/fleet-core/pages/settings*.json`). */
    private val served = listOf(
        "settings", "settings.automation", "settings.catalogs", "settings.control_api", "settings.decisions",
        "settings.devices", "settings.hub", "settings.limits", "settings.orgs", "settings.people",
        "settings.projects", "settings.review", "settings.trackers", "settings.updates", "settings.work",
    ).map { Page(id = it, title = it, layout = "form") }

    @Test
    fun the_hubs_pages_land_in_the_desktops_groups() {
        val groups = groupPages(served).mapValues { (_, pages) -> pages.map { it.id } }
        assertEquals(listOf("settings.automation", "settings.limits", "settings.projects"), groups[SettingsGroup.SESSIONS])
        assertEquals(listOf("settings.catalogs", "settings.decisions", "settings.trackers", "settings.work"), groups[SettingsGroup.WORK])
        assertEquals(listOf("settings.orgs", "settings.people"), groups[SettingsGroup.ORGANISATIONS])
        assertEquals(listOf("settings.control_api", "settings.hub"), groups[SettingsGroup.SYSTEM])
        assertEquals(listOf("settings.devices", "settings.review", "settings.updates"), groups[SettingsGroup.GENERAL])
    }

    /** Nothing is lost, only moved: every page but the root link page is in exactly one group. */
    @Test
    fun every_page_but_the_root_is_reachable_once() {
        val listed = groupPages(served).values.flatten().map { it.id }
        assertEquals(served.map { it.id }.filter { it != ROOT_SETTINGS_PAGE }.sorted(), listed.sorted())
        assertFalse(ROOT_SETTINGS_PAGE in listed, "Settings is not listed inside Settings")
    }

    @Test
    fun a_page_this_build_does_not_know_is_listed_under_general() {
        val groups = groupPages(listOf(Page(id = "settings.voice", title = "Voice", layout = "form")))
        assertTrue(groups[SettingsGroup.GENERAL].orEmpty().any { it.id == "settings.voice" })
    }
}
