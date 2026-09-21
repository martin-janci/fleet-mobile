package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The tab bar showed the letters S, H, S. A letter is not an icon.
 *
 * The first version of this gate asserted that `Icon(` and `FleetIcons.`
 * appeared *somewhere* in App.kt, which by then was true of half a dozen
 * unrelated call sites — the file would have passed with the navigation bar
 * still drawing letters. It matches the call site now: an `Icon(` inside the
 * `icon = {` lambda of a `NavigationBarItem`.
 */
class NoLetterTabIconsTest {
    @Test
    fun the_navigation_bar_uses_image_vectors_not_the_first_letter_of_the_tab_name() {
        val app = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt").readText()

        assertTrue(
            NAV_ITEM_ICON.containsMatchIn(app),
            "no NavigationBarItem in App.kt draws an Icon in its icon slot",
        )
        assertFalse("name.take(1)" in app, "App.kt still draws the first letter as the icon")
        assertFalse("name.first()" in app, "App.kt still draws the first letter as the icon")
    }

    private companion object {
        /** `NavigationBarItem( … icon = { … Icon( …` — non-greedy, so it cannot span two items. */
        val NAV_ITEM_ICON = Regex("""NavigationBarItem\([\s\S]*?icon\s*=\s*\{[\s\S]*?Icon\(""")
    }
}
