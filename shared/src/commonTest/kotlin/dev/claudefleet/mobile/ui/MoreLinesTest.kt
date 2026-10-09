package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.AccountUsageSnapshot
import dev.claudefleet.mobile.model.AccountUsageWindows
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.UsageWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** More's live lines, header and footer (review r09 B9, B10). */
class MoreLinesTest {
    private fun account(id: String, weekly: Double, resetsAt: Long? = 2_000) =
        AccountUsageSnapshot(id, AccountUsageWindows(sevenDay = UsageWindow(weekly, resetsAt)))

    @Test
    fun accounts_name_a_limit_first_then_the_busiest_week() {
        assertNull(accountsLine(emptyList(), 1_000))
        assertEquals("Weekly 86% on the busiest account", accountsLine(listOf(account("a", 40.0), account("b", 86.2)), 1_000))
        assertEquals("An account is at its weekly limit", accountsLine(listOf(account("a", 100.0), account("b", 10.0)), 1_000))
        assertEquals("2 accounts at a limit", accountsLine(listOf(account("a", 100.0), account("b", 100.0)), 1_000))
        assertEquals("Weekly 100% on the busiest account", accountsLine(listOf(account("a", 100.0, resetsAt = 500)), 1_000), "a window that reset is no limit")
    }

    @Test
    fun files_say_the_transfer_in_flight() {
        assertEquals("5 files", filesLine(5, null))
        assertEquals("1 file · release.apk · 62%", filesLine(1, Transfer(1, "release.apk", 62, 100)))
        assertEquals("0 files · notes.txt", filesLine(0, Transfer(2, "notes.txt", 10, null)))
    }

    @Test
    fun the_header_names_the_hub_and_the_access() {
        assertEquals("fleet.example.com · full access", moreSubtitle("fleet.example.com", canWrite = true))
        assertEquals("fleet.example.com · read-only", moreSubtitle("fleet.example.com", canWrite = false))
    }

    @Test
    fun the_footer_has_both_versions_and_the_health() {
        val up = HostRow(alias = "mac", reachable = true)
        val down = HostRow(alias = "nas", reachable = false)
        assertEquals("Orbit Fleet 0.9.4 · hub 0.9.4 · all systems OK", moreFooter("0.9.4", "0.9.4", listOf(up), connected = true))
        assertEquals("Orbit Fleet 0.9.4 · 1 host offline", moreFooter("0.9.4", null, listOf(up, down), connected = true))
        assertEquals("Orbit Fleet 0.9.4 · hub 0.9.3 · hub offline", moreFooter("0.9.4", "0.9.3", listOf(up), connected = false))
    }
}
