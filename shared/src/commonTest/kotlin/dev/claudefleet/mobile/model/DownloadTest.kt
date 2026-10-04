package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `Download` row and the `list_downloads` answer, in the hub's own wire
 * shape (claude-fleet `docs/superpowers/specs/2026-10-03-file-downloads-design.md`,
 * contract revision 7).
 */
class DownloadTest {

    @Test
    fun a_full_ready_row_reads_every_field() {
        val row = json.decodeFromString(
            Download.serializer(),
            """
            {"id":7,"at":1790000000,"host_alias":"gpu-1","session_id":12,"session_name":"fleet-report",
             "path":"/home/u/proj/out/report.pdf","name":"report.pdf","size":48213,"state":"ready",
             "sha256":"ab","source":"agent","note":"the Q3 report","ready_at":1790000004,
             "downloaded_at":1790000100,"expires_at":1790604804}
            """,
        )

        assertEquals(
            Download(
                id = 7, at = 1_790_000_000, hostAlias = "gpu-1", sessionId = 12, sessionName = "fleet-report",
                path = "/home/u/proj/out/report.pdf", name = "report.pdf", size = 48_213, state = "ready",
                sha256 = "ab", source = "agent", note = "the Q3 report", readyAt = 1_790_000_004,
                downloadedAt = 1_790_000_100, expiresAt = 1_790_604_804,
            ),
            row,
        )
        assertTrue(row.isReady)
        assertFalse(row.isFetching || row.isFailed)
    }

    /** Only the eight always-sent fields, plus one the hub has grown since: still a row. */
    @Test
    fun the_optional_fields_may_be_absent_and_unknown_ones_are_ignored() {
        val row = json.decodeFromString(
            Download.serializer(),
            """{"id":8,"at":1,"host_alias":"h","path":"/p/a.csv","name":"a.csv","size":3,"state":"fetching",
               "source":"person","session_id":null,"a_field_from_a_later_hub":{"x":1}}""",
        )

        assertEquals(8, row.id)
        assertNull(row.sessionId)
        assertNull(row.sessionName)
        assertNull(row.error)
        assertNull(row.sha256)
        assertNull(row.expiresAt)
        assertTrue(row.isFetching)
    }

    @Test
    fun a_failed_row_carries_its_reason_and_an_unknown_state_is_neither() {
        val failed = json.decodeFromString(
            Download.serializer(),
            """{"id":9,"at":1,"host_alias":"h","path":"/p","name":"p","size":0,"state":"failed","source":"agent","error":"permission denied"}""",
        )
        assertTrue(failed.isFailed)
        assertEquals("permission denied", failed.error)

        val later = failed.copy(state = "quarantined")
        assertFalse(later.isReady || later.isFetching || later.isFailed)
    }

    @Test
    fun the_list_answer_reads_its_rows_and_budget() {
        val list = json.decodeFromString(
            DownloadList.serializer(),
            """{"downloads":[{"id":2,"at":2,"host_alias":"h","path":"/b","name":"b","size":1,"state":"ready","source":"agent"},
                             {"id":1,"at":1,"host_alias":"h","path":"/a","name":"a","size":1,"state":"failed","source":"person"}],
               "total_bytes":123,"max_total_bytes":2147483648,"max_file_bytes":104857600}""",
        )

        assertEquals(listOf(2L, 1L), list.downloads.map { it.id })
        assertEquals(123, list.totalBytes)
        assertEquals(2_147_483_648, list.maxTotalBytes)
        assertEquals(104_857_600, list.maxFileBytes)
        assertEquals(DownloadList(), json.decodeFromString(DownloadList.serializer(), "{}"))
    }

    @Test
    fun remove_answers_whether_it_was_there() {
        assertTrue(json.decodeFromString(DownloadRemoved.serializer(), """{"removed":true}""").removed)
        assertFalse(json.decodeFromString(DownloadRemoved.serializer(), """{"removed":false}""").removed)
    }

    @Test
    fun sizes_read_the_way_a_file_manager_reads_them() {
        assertEquals("0 B", humanBytes(0))
        assertEquals("1023 B", humanBytes(1023))
        assertEquals("1.0 KB", humanBytes(1024))
        assertEquals("47 KB", humanBytes(48_213))
        assertEquals("9.9 MB", humanBytes(10L * 1024 * 1024 - 1))
        assertEquals("100 MB", humanBytes(104_857_600))
        assertEquals("2.0 GB", humanBytes(2_147_483_648))
    }
}
