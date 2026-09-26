package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun fmt_bytes_reads_the_way_the_host_says_it() {
        assertEquals("512 B", fmtBytes(512))
        assertEquals("1.0 MB", fmtBytes(1024 * 1024))
        assertEquals("11.0 MB", fmtBytes(11L * 1024 * 1024))
    }
}
