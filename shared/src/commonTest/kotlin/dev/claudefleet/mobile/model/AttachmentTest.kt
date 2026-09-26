package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The phone's half of the attachment contract. Both halves are ports of the
 * desktop's — `src/lib/attach_prompt.ts` for the prompt, and
 * `crates/fleet-core/src/service/attachments.rs` for the budget and its
 * wording — so these are the same vectors both of those assert.
 */
class AttachmentTest {
    @Test
    fun a_draft_with_no_attachments_is_unchanged() {
        assertEquals("hello", withAttachments("hello", emptyList()))
    }

    @Test
    fun paths_go_in_a_block_after_the_draft() {
        assertEquals(
            "hello\n\nAttached files:\n/a/one.png\n/a/two.log",
            withAttachments("hello", listOf("/a/one.png", "/a/two.log")),
        )
    }

    @Test
    fun an_empty_draft_is_the_block_alone() {
        assertEquals("Attached files:\n/a/one.png", withAttachments("   ", listOf("/a/one.png")))
    }

    @Test
    fun the_budget_wording_is_the_hosts_own() {
        assertNull(checkBudget(listOf("small.png" to 1024L)))
        assertEquals(
            "big.png is 10.0 MB — the limit is 10 MB.",
            checkBudget(listOf("big.png" to ATTACH_MAX_BYTES + 1)),
        )
        val nine = 9L * 1024 * 1024
        assertEquals(
            "c.bin would make 27.0 MB in total — the limit is 25 MB in total.",
            checkBudget(listOf("a.bin" to nine, "b.bin" to nine, "c.bin" to nine)),
        )
    }

    @Test
    fun exactly_on_the_line_is_allowed_and_one_byte_more_is_not() {
        assertNull(checkBudget(listOf("edge.bin" to ATTACH_MAX_BYTES)))
        assertEquals(
            "edge.bin is 10.0 MB — the limit is 10 MB.",
            checkBudget(listOf("edge.bin" to ATTACH_MAX_BYTES + 1)),
        )
    }

    // ---- what the picker decides per file, and what it calls the result ----

    @Test
    fun a_file_that_fits_the_pick_is_taken() {
        assertNull(skipForSize("a.png", 1024, ATTACH_MAX_TOTAL))
        assertNull(skipForSize("edge.bin", ATTACH_MAX_BYTES, ATTACH_MAX_BYTES), "exactly on both lines")
    }

    @Test
    fun the_per_file_ceiling_and_the_picks_own_total_are_different_refusals() {
        val big = assertNotNull(skipForSize("big.png", ATTACH_MAX_BYTES + 1, ATTACH_MAX_TOTAL))
        assertEquals(SkipReason.TooBig, big.reason)

        // Fine on its own; there is simply no room left in this pick. Saying
        // "the limit is 10 MB" about an 8 MB file would be a lie.
        val noRoom = assertNotNull(skipForSize("h.bin", 8L * 1024 * 1024, 3L * 1024 * 1024))
        assertEquals(SkipReason.OverTotal, noRoom.reason)
        assertEquals("h.bin did not fit — the limit is 25 MB in total.", skippedRefusal(noRoom))
    }

    @Test
    fun the_read_cap_is_the_smaller_of_the_two_bounds_and_never_negative() {
        assertEquals(ATTACH_MAX_BYTES, readCap(ATTACH_MAX_TOTAL))
        assertEquals(1024L, readCap(1024))
        assertEquals(0L, readCap(-5))
    }

    @Test
    fun a_skipped_file_with_no_declared_size_still_gets_a_sentence() {
        assertEquals(
            "mystery.bin is over the 10 MB limit.",
            skippedRefusal(SkippedFile("mystery.bin", null, SkipReason.TooBig)),
        )
        assertEquals(
            "gone.txt could not be read.",
            skippedRefusal(SkippedFile("gone.txt", null, SkipReason.Unreadable)),
        )
    }

    // ---- deduped names: the desktop's `dedupe_names`, vector for vector ----

    @Test
    fun a_free_name_is_left_alone() {
        assertEquals("a.png", dedupedName("a.png", setOf("b.png")))
    }

    @Test
    fun a_collision_suffixes_before_the_extension_and_keeps_counting() {
        val taken = mutableSetOf("a.png")
        val second = dedupedName("a.png", taken)
        taken += second
        val third = dedupedName("a.png", taken)
        assertEquals("a-1.png", second)
        assertEquals("a-2.png", third)
    }

    @Test
    fun a_name_without_an_extension_takes_the_suffix_at_the_end() {
        assertEquals("notes-1", dedupedName("notes", setOf("notes")))
        // A leading dot is not an extension — `.env-1`, never `-1.env`.
        assertEquals(".env-1", dedupedName(".env", setOf(".env")))
    }

    @Test
    fun fmt_bytes_reads_the_way_the_host_says_it() {
        assertEquals("512 B", fmtBytes(512))
        assertEquals("1.0 MB", fmtBytes(1024 * 1024))
        assertEquals("11.0 MB", fmtBytes(11L * 1024 * 1024))
    }
}
