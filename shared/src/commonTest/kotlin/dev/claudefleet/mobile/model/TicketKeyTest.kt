package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Link a ticket* (MobileFormsWork): checked on Link, the fix offered in the
 * error — "Not a key. Did you mean FLEET-142?".
 */
class TicketKeyTest {

    @Test
    fun an_almost_key_is_offered_its_well_formed_spelling() {
        assertEquals("FLEET-142", ticketKeyFix("fleet142"))
        assertEquals("FLEET-142", ticketKeyFix("FLEET 142"))
        assertEquals("FLEET-142", ticketKeyFix("  FLEET_142 "))
        assertEquals("FLEET-142", ticketKeyFix("Fleet.142"))
        assertEquals("FLEET-142", ticketKeyFix("FLEET#142"))
        assertEquals("FLEET-142", ticketKeyFix("FLEET–142"), "an en dash a phone keyboard put in")
        assertEquals("Not a key. Did you mean FLEET-142?", keyFixLine("FLEET-142"))
    }

    @Test
    fun what_is_already_a_key_or_not_key_shaped_at_all_goes_as_typed() {
        assertNull(ticketKeyFix("FLEET-142"))
        assertNull(ticketKeyFix("fleet-142"), "the hub upper-cases a well-formed key itself")
        assertNull(ticketKeyFix("https://acme.atlassian.net/browse/FLEET-142"))
        assertNull(ticketKeyFix("acme/api#12"), "a GitHub issue")
        assertNull(ticketKeyFix("hosts screen polish"), "free text is a local key on the hub")
        assertNull(ticketKeyFix(""))
        assertNull(ticketKeyFix("142"), "a bare number with no project to put it in")
        assertNull(ticketKeyFix("F 142"), "a one-letter prefix is no key")
    }

    @Test
    fun the_projects_the_phone_knows_correct_a_prefix_or_a_bare_number() {
        val known = listOf("FLEET", "SAL")
        assertEquals("FLEET-142", ticketKeyFix("FLET 142", known), "one letter off one it knows")
        assertEquals("SAL-522", ticketKeyFix("sal522", known))
        assertEquals("PAY-7", ticketKeyFix("pay 7", known), "an unknown prefix is kept as typed")
        assertEquals("FLEET-142", ticketKeyFix("#142", listOf("FLEET")), "one project: a number is its ticket")
        assertNull(ticketKeyFix("142", known), "two projects: which one is not the phone's guess")
    }

    @Test
    fun known_prefixes_come_from_well_formed_ticket_keys() {
        val tickets = listOf(
            Ticket(id = 1, key = "FLEET-142"),
            Ticket(id = 2, key = "fleet-150"),
            Ticket(id = 3, key = "acme/api#12"),
            Ticket(id = 4, key = null, title = "local work"),
            Ticket(id = 5, key = "SAL-522"),
        )
        assertEquals(listOf("FLEET", "SAL"), knownKeyPrefixes(tickets))
        assertTrue(isTicketKey("AB_1-1234567"))
        assertFalse(isTicketKey("A-1"))
        assertFalse(isTicketKey("ABC-12345678"))
    }

    /** Check on Link, not on each key: a second Link on the same text sends it as typed. */
    @Test
    fun a_second_link_on_the_same_text_sends_it_as_typed() {
        assertEquals("FLEET-142", keyFixOnLink("FLEET 142", offeredFor = null))
        assertNull(keyFixOnLink("FLEET 142 ", offeredFor = "FLEET 142"), "the person saw the fix and kept theirs")
        assertEquals("FLEET-143", keyFixOnLink("FLEET 143", offeredFor = "FLEET 142"), "changed text is checked again")
        assertNull(keyFixOnLink("FLEET-142", offeredFor = null))
    }
}
