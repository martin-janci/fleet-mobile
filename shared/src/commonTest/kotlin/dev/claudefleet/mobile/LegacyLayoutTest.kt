package dev.claudefleet.mobile

import dev.claudefleet.mobile.store.FakePrefs
import kotlin.test.Test
import kotlin.test.assertEquals

/** Review w08-8: the Classic layout's stored choice is forgotten, as the desktop forgets it at load. */
class LegacyLayoutTest {

    @Test
    fun a_stored_layout_choice_is_cleared() {
        val prefs = FakePrefs()
        prefs.putStringList(LEGACY_LAYOUT_KEY, listOf("classic"))
        forgetLayoutChoice(prefs)
        assertEquals(emptyList(), prefs.getStringList(LEGACY_LAYOUT_KEY))
        // Nothing stored: nothing written, nothing breaks.
        forgetLayoutChoice(FakePrefs())
    }
}
