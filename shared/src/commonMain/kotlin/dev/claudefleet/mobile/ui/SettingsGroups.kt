package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.Page

/**
 * The desktop's five Settings groups (redesign 14.11, board MobileSettings):
 * the phone lists the hub's pages under the same headings the desktop uses,
 * so a setting is found in the same place on both. [line] is what the group
 * row says under its name.
 */
enum class SettingsGroup(val title: String, val line: String) {
    GENERAL("General", "Hub, client name, updates"),
    SESSIONS("Sessions", "Automation, limits, projects"),
    WORK("Work", "Trackers, work graph, Jev"),
    ORGANISATIONS("Organisations", "Members and budgets are changed on the desktop"),
    SYSTEM("System", "Hub daemon, Control API"),
}

/** The hub page that only links to the others ("Settings" inside Settings); the groups replace it. */
internal const val ROOT_SETTINGS_PAGE = "settings"

/** The hub page holding proposed changes; it also gets its own row at the top while any wait. */
internal const val REVIEW_PAGE = "settings.review"

/**
 * Which group a hub page belongs to. A page this build has not heard of goes
 * under General rather than nowhere: a hub newer than the app must still have
 * every page reachable (nothing is lost, only moved).
 */
fun settingsGroupOf(pageId: String): SettingsGroup = when (pageId) {
    "settings.automation", "settings.limits", "settings.projects" -> SettingsGroup.SESSIONS
    "settings.trackers", "settings.work", "settings.decisions", "settings.catalogs" -> SettingsGroup.WORK
    "settings.orgs", "settings.people" -> SettingsGroup.ORGANISATIONS
    "settings.hub", "settings.control_api" -> SettingsGroup.SYSTEM
    else -> SettingsGroup.GENERAL
}

/**
 * The hub's pages by group, in the hub's own order within each group. The
 * root link page is left out (every page it links to is listed), and a page
 * nested under another page stays reachable from its parent and from here.
 */
fun groupPages(pages: List<Page>): Map<SettingsGroup, List<Page>> =
    pages.filter { it.id != ROOT_SETTINGS_PAGE }.groupBy { settingsGroupOf(it.id) }

/** Where a person is inside Settings in the New layout: its home, This phone, or one group. */
sealed interface SettingsPlace {
    data object Home : SettingsPlace
    data object ThisPhone : SettingsPlace
    data class Group(val group: SettingsGroup) : SettingsPlace
}
