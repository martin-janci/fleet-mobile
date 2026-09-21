package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The tab bar showed the letters S, H, S. A letter is not an icon. */
class NoLetterTabIconsTest {
    @Test
    fun the_navigation_bar_uses_image_vectors_not_the_first_letter_of_the_tab_name() {
        val app = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt").readText()
        assertFalse("name.take(1)" in app, "App.kt still draws the first letter as the icon")
        assertTrue("Icon(" in app && "FleetIcons." in app, "App.kt does not draw FleetIcons in the NavigationBar")
    }
}
