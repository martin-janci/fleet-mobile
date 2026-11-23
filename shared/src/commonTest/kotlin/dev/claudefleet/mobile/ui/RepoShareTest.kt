package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.FileContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Review w11-8: Share on a file hands on the whole text or says why it cannot. */
class RepoShareTest {
    @Test
    fun only_a_whole_small_text_file_is_shared_as_text() {
        val small = FileContent(path = "a.log", content = "hello")
        assertTrue(canShareText(small))
        assertNull(shareNote(small, canSendFile = true))

        val big = FileContent(path = "big.log", content = "x".repeat(SHARE_TEXT_MAX + 1))
        assertFalse(canShareText(big))
        assertEquals("This file is too large to share as text. Send it to Downloads to share the whole file.", shareNote(big, canSendFile = true))
        assertEquals("This file is too large to share as text.", shareNote(big, canSendFile = false))

        val cut = FileContent(path = "gen.ts", content = "y", truncated = true)
        assertFalse(canShareText(cut))
        assertTrue(shareNote(cut, canSendFile = false)!!.startsWith("The hub sent only the first 512 KiB"))
    }

    @Test
    fun a_binary_file_names_downloads_only_where_this_phone_can_send_there() {
        val bin = FileContent(path = "a.png", binary = true, size = 10)
        assertTrue("send it to Downloads" in binaryFileNote(bin, canSendFile = true))
        assertFalse("Downloads" in binaryFileNote(bin, canSendFile = false))
        assertNull(shareNote(bin, canSendFile = true))
    }
}
