package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextFoldTest {
    @Test
    fun drops_case_and_diacritics() {
        assertEquals("uloha zlty kon", fold("Úloha ŽLTÝ kôň"))
        assertEquals("lodz straße æro", fold("Łódź Straße Ærø"))
        assertEquals("cafe", fold("Café"))
        assertEquals("plain-ascii_42", fold("plain-ascii_42"))
    }

    @Test
    fun matches_either_way_round() {
        assertTrue("Oprava prihlásenia".foldedContains(fold("prihlasenia")))
        assertTrue("Oprava prihlasenia".foldedContains(fold("prihlásenia")))
        assertFalse(null.foldedContains("x"))
    }
}
