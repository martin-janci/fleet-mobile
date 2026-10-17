package dev.claudefleet.mobile.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 2026-10-17T10:15:40.123Z. */
private const val AT = 1_792_232_140_123L

class LogRingTest {
    @Test
    fun a_line_starts_with_its_utc_stamp_two_spaces_and_the_level() {
        val ring = LogRing(clock = { AT })
        ring.add(LogLevel.Error, "stream", "the hub went away")
        assertEquals(listOf("2026-10-17T10:15:40.123Z  ERROR stream: the hub went away"), ring.window(5, AT))
        assertEquals("2000-02-29T00:00:00.005Z", logStamp(951_782_400_005L))
        assertEquals("1969-12-31T23:59:59.999Z", logStamp(-1L))
        assertEquals("1970-01-01T00:00:00.000Z", logStamp(0L))
    }

    @Test
    fun the_ring_keeps_the_newest_lines_in_order_within_its_caps() {
        val ring = LogRing(maxLines = 3, clock = { AT })
        for (i in 1..5) ring.add(LogLevel.Info, "t", "line $i")
        assertEquals(listOf("line 3", "line 4", "line 5"), ring.lines().map { it.text.substringAfter("t: ") })

        // The byte cap drops the oldest too: each line here is 36 bytes.
        val small = LogRing(maxBytes = 100, clock = { AT })
        for (i in 1..4) small.add(LogLevel.Info, "t", "n$i")
        assertEquals(listOf("n3", "n4"), small.lines().map { it.text.substringAfter("t: ") })
        assertTrue(small.lines().sumOf { utf8Size(it.text) } <= 100)
    }

    @Test
    fun a_stack_trace_becomes_one_stamped_line_per_frame() {
        val ring = LogRing(clock = { AT })
        ring.add(LogLevel.Error, "crash", "boom\n  at a.b(c.kt:1)\r\n\n  at d.e(f.kt:2)")
        val lines = ring.lines().map { it.text }
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.startsWith("2026-10-17T10:15:40.123Z  ERROR crash: ") }, "$lines")
        assertTrue(lines.last().endsWith("at d.e(f.kt:2)"))
    }

    @Test
    fun a_line_is_cut_at_the_hubs_length() {
        val ring = LogRing(clock = { AT })
        ring.add(LogLevel.Info, "t", "x ".repeat(3000))
        assertEquals(LOG_LINE_MAX_CHARS, ring.lines().single().text.length)
    }

    @Test
    fun the_window_is_the_last_minutes_before_now() {
        var now = AT
        val ring = LogRing(clock = { now })
        ring.add(LogLevel.Info, "t", "an hour ago")
        now += 50 * 60_000L
        ring.add(LogLevel.Info, "t", "ten minutes ago")
        now += 10 * 60_000L
        ring.add(LogLevel.Info, "t", "now")
        fun words(minutes: Long) = ring.window(minutes, now).map { it.substringAfter("t: ") }
        assertEquals(listOf("now"), words(1))
        assertEquals(listOf("ten minutes ago", "now"), words(15))
        assertEquals(listOf("an hour ago", "ten minutes ago", "now"), words(60))
    }

    @Test
    fun secrets_are_masked_before_a_line_is_held() {
        val hex = "0123456789abcdef".repeat(4)
        assertEquals("Authorization: Bearer $LOG_REDACTED", redactLog("Authorization: Bearer $hex"))
        assertEquals("auth basic $LOG_REDACTED done", redactLog("auth basic dXNlcjpwYXNzd29yZA== done"))
        assertEquals(
            "GET /x?token=$LOG_REDACTED&page=2 password=$LOG_REDACTED",
            redactLog("GET /x?token=abc123&page=2 password=hunter2"),
        )
        assertEquals("""{"access_token":"$LOG_REDACTED","n":1}""", redactLog("""{"access_token":"s3cr3t","n":1}"""))
        assertEquals("key $LOG_REDACTED end", redactLog("key ghp_${"A1b2C3d4".repeat(5)} end"))
        // Ordinary text stays: long words without digits, short ids, a UUID.
        val plain = "reconnecting_to_the_hub_after_a_long_pause_and_more session 42 7f3c9a1e-2b4d-4c6e-8f00-123456789abc"
        assertEquals(plain, redactLog(plain))

        val ring = LogRing(clock = { AT })
        ring.add(LogLevel.Warn, "ui", "refused: Bearer $hex")
        assertFalse(ring.lines().any { hex in it.text })
    }

    @Test
    fun a_crash_record_comes_back_with_its_stamps() {
        FleetLog.ring.add(LogLevel.Error, "crash", "kept across a restart", atMillis = AT)
        val record = FleetLog.crashRecord(1)
        assertEquals(listOf("$AT\t2026-10-17T10:15:40.123Z  ERROR crash: kept across a restart"), record)
        // The shared ring may be full from other tests' failures, so only the end is read.
        FleetLog.restore(listOf("not a record") + record)
        val tail = FleetLog.ring.lines().takeLast(2)
        assertEquals(LogLine(AT, "2026-10-17T10:15:40.123Z  ERROR crash: kept across a restart"), tail.last())
        assertFalse(tail.any { "not a record" in it.text })
    }
}
