package dev.claudefleet.mobile.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One ticket with its tasks as one sheet (redesign 14.15, MobileTidyTickets):
 * on the New layout the ticket chip stands for the Tasks chip, and the sheet
 * is up while either half was opened. Classic keeps both chips.
 */
class PhoneTicketSheetTest {

    @Test
    fun on_new_the_ticket_chip_stands_for_the_tasks_chip() {
        assertFalse(tasksChipShown(newLayout = true, ticketChip = true, tasksAvailable = true))
        assertTrue(tasksChipShown(newLayout = true, ticketChip = false, tasksAvailable = true))
        assertFalse(tasksChipShown(newLayout = true, ticketChip = false, tasksAvailable = false))
    }

    @Test
    fun classic_keeps_both_chips() {
        assertTrue(tasksChipShown(newLayout = false, ticketChip = true, tasksAvailable = true))
        assertTrue(tasksChipShown(newLayout = false, ticketChip = false, tasksAvailable = true))
        assertFalse(tasksChipShown(newLayout = false, ticketChip = true, tasksAvailable = false))
    }

    @Test
    fun the_sheet_is_up_while_either_half_is_open() {
        assertFalse(ticketSheetOpen(SessionWorkUiState(), SessionTasksUiState()))
        assertTrue(ticketSheetOpen(SessionWorkUiState(sheetOpen = true), SessionTasksUiState()))
        assertTrue(ticketSheetOpen(SessionWorkUiState(), SessionTasksUiState(sheetOpen = true)))
    }
}
