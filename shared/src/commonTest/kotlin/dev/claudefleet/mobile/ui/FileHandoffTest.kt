package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.downloadCachePath
import dev.claudefleet.mobile.data.safeFileName
import dev.claudefleet.mobile.model.Download
import kotlin.test.Test
import kotlin.test.assertEquals

/** Where a hub download is cached on the phone, and what the platform is told it is. */
class FileHandoffTest {
    @Test
    fun a_cached_copy_lives_under_its_id_and_keeps_a_safe_name() {
        val row = Download(id = 7, name = "report.pdf")
        assertEquals("/cache/downloads/7/report.pdf", downloadCachePath("/cache/", row))

        assertEquals("a_b_c.txt", safeFileName("a/b\\c.txt"))
        assertEquals("download", safeFileName(".."))
        assertEquals("download", safeFileName("  "))
        assertEquals("x_y", safeFileName("x\u0000y"))
        assertEquals(200, safeFileName("n".repeat(500)).length)
    }

    @Test
    fun the_mime_type_follows_the_extension() {
        assertEquals("application/pdf", mimeTypeFor("Report.PDF"))
        assertEquals("text/csv", mimeTypeFor("q3.csv"))
        assertEquals("application/octet-stream", mimeTypeFor("a.unknownext"))
        assertEquals("application/octet-stream", mimeTypeFor("Makefile"))
    }
}
