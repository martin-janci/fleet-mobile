package dev.claudefleet.mobile.host

import dev.claudefleet.mobile.ui.Tab
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The tab bar showed the letters S, H, S. A letter is not an icon.
 *
 * The first version of this gate asserted that `Icon(` and `FleetIcons.`
 * appeared *somewhere* in App.kt, which by then was true of half a dozen
 * unrelated call sites. It matches the call site: since the Classic bar went
 * (redesign 14.13), each tab's `BottomBarItem` in `newBarItem`, whose icon is
 * an `ImageVector` from `OrbitIcons` — one per tab.
 */
class NoLetterTabIconsTest {
    @Test
    fun the_navigation_bar_uses_image_vectors_not_the_first_letter_of_the_tab_name() {
        val app = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt").readText()

        assertEquals(
            Tab.entries.size,
            BAR_ITEM_ICON.findAll(app).count(),
            "every tab's BottomBarItem in App.kt draws an OrbitIcons vector",
        )
        assertFalse("name.take(1)" in app, "App.kt still draws the first letter as the icon")
        assertFalse("name.first()" in app, "App.kt still draws the first letter as the icon")
    }

    private companion object {
        /** `BottomBarItem(tab.name, "Inbox", OrbitIcons.Inbox)`. */
        val BAR_ITEM_ICON = Regex("""BottomBarItem\(tab\.name, "[^"]+", OrbitIcons\.\w+\)""")
    }
}
