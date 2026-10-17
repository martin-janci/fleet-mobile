package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostLoadTest {
    private val now = 10_000L
    private fun host(
        alias: String = "box",
        reachable: Boolean = true,
        cpu: Double? = 18.0,
        avail: Long? = 4_500,
        free: Long? = 60,
        at: Long? = now - 30,
        memLow: Boolean = false,
        saturated: Boolean = false,
    ) = HostRow(
        alias = alias,
        reachable = reachable,
        memTotalKb = 10_000,
        memAvailKb = avail,
        diskHomeFreeKb = free,
        diskHomeTotalKb = 100,
        healthAt = at,
        cpuPct = cpu,
        memLow = memLow,
        cpuSaturated = saturated,
    )

    @Test
    fun a_healthy_host_reads_its_three_numbers_and_no_trouble() {
        val l = hostLoad(host(), now)
        assertEquals(listOf("CPU 18%", "Mem 55%", "Disk 40%"), loadParts(l))
        assertNull(l.trouble)
        assertEquals(LoadLevel.OK, l.level)
        assertNull(troubleSentence(l))
    }

    @Test
    fun trouble_is_named_worst_first_and_only_on_a_fresh_sample() {
        assertEquals("offline", hostLoad(host(reachable = false), now).trouble)
        assertNull(hostLoad(host(alias = "local", reachable = false), now).trouble, "the hub's own machine is never offline")
        assertEquals("mem 95%", hostLoad(host(avail = 500, saturated = true), now).trouble)
        assertEquals("mem 55%", hostLoad(host(memLow = true), now).trouble, "the hub's judgment counts on its own")
        assertEquals("cpu 97%", hostLoad(host(cpu = 97.0, saturated = true), now).trouble)
        assertEquals("disk 92%", hostLoad(host(free = 8), now).trouble)
        val stale = hostLoad(host(avail = 500, at = now - HEALTH_SAMPLE_FRESH_SECS - 1), now)
        assertNull(stale.trouble, "an hour-old sample says nothing about now")
        assertEquals(95, stale.mem, "its numbers still show")
        assertTrue(troubleSentence(hostLoad(host(avail = 500), now))!!.contains("low on memory"))
    }

    @Test
    fun a_host_that_answered_nothing_says_so() {
        val l = hostLoad(HostRow(alias = "box", reachable = true), now)
        assertEquals(listOf("not sampled yet"), loadParts(l))
        assertEquals(listOf("offline"), loadParts(hostLoad(HostRow(alias = "box"), now)))
        assertEquals("checked 30s ago", checkedAgo(now - 30, now))
        assertEquals("checked 5m ago", checkedAgo(now - 300, now))
    }

    @Test
    fun a_lost_row_says_why() {
        val row = SessionRow(id = 1, lostAt = 5, lostReason = "process_died")
        assertEquals("Lost · its process stopped (out of memory or killed)", lostLine(row))
        assertEquals("Lost", lostLine(row.copy(lostReason = "something_new")))
        assertNull(lostLine(row.copy(lostAt = null)))
        assertTrue("process_died" in RESUMABLE_LOST_REASONS)
    }
}
