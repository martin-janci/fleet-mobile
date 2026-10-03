package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.data.ConnectionStatus
import dev.claudefleet.mobile.data.DownloadActions
import dev.claudefleet.mobile.data.FleetState
import dev.claudefleet.mobile.data.ALL_DOWNLOADS_CHANGED
import dev.claudefleet.mobile.model.Download
import dev.claudefleet.mobile.model.DownloadList
import dev.claudefleet.mobile.model.HostRow
import dev.claudefleet.mobile.model.ProjectRow
import dev.claudefleet.mobile.model.SessionRow
import dev.claudefleet.mobile.net.DOWNLOAD_GONE
import dev.claudefleet.mobile.net.FetchedFile
import dev.claudefleet.mobile.net.HubCapabilities
import dev.claudefleet.mobile.net.HubError
import dev.claudefleet.mobile.net.ToolCatalog
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FilesFleet(caps: HubCapabilities) : FleetState {
    override val sessions = MutableStateFlow<List<SessionRow>>(emptyList())
    override val hosts = MutableStateFlow<List<HostRow>>(emptyList())
    override val projects = MutableStateFlow<List<ProjectRow>>(emptyList())
    override val status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Connected("0.9.9"))
    override val hubVersion = MutableStateFlow<String?>("0.9.9")
    override val clockSkewSeconds = MutableStateFlow(0L)
    override val sessionChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override val capabilities = MutableStateFlow(caps)
    override val downloadChanges = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    override suspend fun refresh() = Unit
}

private class FakeDownloads : DownloadActions {
    var rows: List<Download> = emptyList()
    var listCalls = 0
    val fetched = mutableListOf<Pair<Long, String>>()
    val removed = mutableListOf<Long>()
    val sent = mutableListOf<Triple<Long, String, String?>>()
    var cached = setOf<String>()
    var fetchFailure: Throwable? = null

    override suspend fun list(sessionId: Long?, limit: Int?): DownloadList {
        listCalls += 1
        return DownloadList(rows, totalBytes = rows.sumOf { it.size }, maxTotalBytes = 2L * 1024 * 1024 * 1024)
    }

    override suspend fun send(sessionId: Long, path: String, note: String?): Download {
        sent += Triple(sessionId, path, note)
        return Download(id = 99, state = Download.FETCHING)
    }

    override suspend fun remove(id: Long): Boolean {
        removed += id
        rows = rows.filterNot { it.id == id }
        return true
    }

    override suspend fun fetch(download: Download, destination: String, onProgress: (Long, Long?) -> Unit): FetchedFile {
        fetchFailure?.let { throw it }
        onProgress(download.size, download.size)
        fetched += download.id to destination
        return FetchedFile(download.size, "ab")
    }

    override suspend fun isCached(download: Download, destination: String): Boolean = destination in cached
}

private class FakeHandoff(override val offered: List<Handoff> = Handoff.entries) : FileHandoff {
    override val cacheDir = "/cache"
    val done = mutableListOf<Pair<Handoff, String>>()
    override suspend fun perform(action: Handoff, path: String, name: String): String? {
        done += action to path
        return if (action == Handoff.Save) "Saved to Downloads" else null
    }
}

private val ALL = HubCapabilities.of(ToolCatalog(setOf("list_downloads", "send_file", "remove_download")))
private val READ_ONLY = HubCapabilities.of(ToolCatalog(setOf("list_downloads")))

private const val NOW = 1_790_000_600L

private fun download(id: Long, state: String = Download.READY, at: Long = NOW - 300, name: String = "f$id.pdf") =
    Download(id = id, at = at, hostAlias = "gpu-1", sessionId = 12, sessionName = "report", path = "/p/$name", name = name, size = 48_213, state = state, source = "agent")

/**
 * The Files tab (claude-fleet file downloads): offered when the hub lists
 * `list_downloads`, read on opening and on `download:changed`, a ready file
 * fetched once into the cache and handed on, and Remove only for a token that
 * may — after asking.
 */
class FilesViewModelTest {

    private fun vm(
        fleet: FilesFleet,
        actions: FakeDownloads,
        scope: kotlinx.coroutines.CoroutineScope,
        canWrite: Boolean = true,
        handoff: FakeHandoff = FakeHandoff(),
    ) = FilesViewModel(fleet, actions, scope, canWrite, handoff, clock = { NOW })

    @Test
    fun it_is_offered_only_when_the_hub_lists_downloads() = runTest {
        val actions = FakeDownloads()
        val fleet = FilesFleet(HubCapabilities())
        val files = vm(fleet, actions, backgroundScope)
        files.attach()
        runCurrent()

        assertFalse(files.state.value.available)
        assertEquals(0, actions.listCalls, "a hub without the tool is never asked")

        fleet.capabilities.value = ALL
        runCurrent()
        assertTrue(files.state.value.available)
        assertEquals(1, actions.listCalls, "read once the hub says it keeps downloads")
    }

    @Test
    fun it_reads_on_opening_and_draws_newest_first() = runTest {
        val actions = FakeDownloads().apply {
            rows = listOf(download(1, at = NOW - 7200), download(2, Download.FETCHING, at = NOW - 60), download(3, Download.FAILED, at = NOW - 600).copy(error = "permission denied"))
        }
        val files = vm(FilesFleet(ALL), actions, backgroundScope)
        files.attach()
        runCurrent()

        val s = files.state.value
        assertTrue(s.loaded)
        assertEquals(listOf(2L, 3L, 1L), s.files.map { it.id })
        assertEquals(listOf(FileState.Fetching, FileState.Failed, FileState.Ready), s.files.map { it.state })
        assertEquals("gpu-1 · report", s.files[0].where)
        assertEquals("47 KB", s.files[0].size)
        assertEquals("1 min", s.files[0].age)
        assertEquals("permission denied", s.files[1].error)
        assertNull(s.files[2].error)
        assertTrue(s.files[0].fromAgent)
        assertNotNull(s.usage)
    }

    @Test
    fun a_download_changed_frame_rereads_while_the_tab_is_showing_and_not_after() = runTest {
        val actions = FakeDownloads().apply { rows = listOf(download(1, Download.FETCHING)) }
        val fleet = FilesFleet(ALL)
        val files = vm(fleet, actions, backgroundScope)
        files.attach()
        runCurrent()
        assertEquals(1, actions.listCalls)

        actions.rows = listOf(download(1, Download.READY))
        fleet.downloadChanges.emit(1)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, actions.listCalls)
        assertEquals(FileState.Ready, files.state.value.files.single().state)

        files.detach()
        fleet.downloadChanges.emit(ALL_DOWNLOADS_CHANGED)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, actions.listCalls, "a hidden tab does not follow the stream")
    }

    @Test
    fun a_tap_on_a_ready_file_fetches_it_into_the_cache_and_offers_the_platforms_verbs() = runTest {
        val actions = FakeDownloads().apply { rows = listOf(download(7, name = "r.pdf")) }
        val handoff = FakeHandoff()
        val files = vm(FilesFleet(ALL), actions, backgroundScope, handoff = handoff)
        files.attach()
        runCurrent()

        files.tap(7)!!.join()
        runCurrent()

        assertEquals(listOf(7L to "/cache/downloads/7/r.pdf"), actions.fetched)
        val opened = files.state.value.opened!!
        assertEquals("/cache/downloads/7/r.pdf", opened.path)
        assertNull(files.state.value.transfer)
        assertEquals(Handoff.entries, files.state.value.handoffs)

        files.handOff(Handoff.Share)!!.join()
        runCurrent()
        assertEquals(listOf(Handoff.Share to "/cache/downloads/7/r.pdf"), handoff.done)
        assertNotNull(files.state.value.opened, "Share leaves the sheet up")

        files.handOff(Handoff.Save)!!.join()
        runCurrent()
        assertEquals("Saved to Downloads", files.state.value.notice)
        assertNull(files.state.value.opened)
    }

    @Test
    fun a_copy_already_on_the_phone_is_not_fetched_again() = runTest {
        val actions = FakeDownloads().apply {
            rows = listOf(download(7, name = "r.pdf"))
            cached = setOf("/cache/downloads/7/r.pdf")
        }
        val files = vm(FilesFleet(ALL), actions, backgroundScope)
        files.attach()
        runCurrent()

        files.tap(7)!!.join()
        runCurrent()

        assertEquals(emptyList(), actions.fetched)
        assertNotNull(files.state.value.opened)
    }

    @Test
    fun nothing_to_fetch_until_the_hub_has_it() = runTest {
        val actions = FakeDownloads().apply { rows = listOf(download(1, Download.FETCHING), download(2, Download.FAILED)) }
        val files = vm(FilesFleet(ALL), actions, backgroundScope)
        files.attach()
        runCurrent()

        assertNull(files.tap(1))
        assertNull(files.tap(2))
        assertNull(files.tap(404))
        assertEquals(emptyList(), actions.fetched)
    }

    @Test
    fun a_file_gone_from_the_hub_says_so_and_rereads() = runTest {
        val actions = FakeDownloads().apply {
            rows = listOf(download(7))
            fetchFailure = HubError.Tool(DOWNLOAD_GONE, "That file is no longer on the hub: it expired.")
        }
        val files = vm(FilesFleet(ALL), actions, backgroundScope)
        files.attach()
        runCurrent()
        val before = actions.listCalls

        files.tap(7)!!.join()
        runCurrent()

        assertEquals("No longer available", files.state.value.error?.title)
        assertNull(files.state.value.opened)
        assertNull(files.state.value.transfer)
        assertEquals(before + 1, actions.listCalls)
    }

    @Test
    fun a_damaged_transfer_is_said_in_words() = runTest {
        val actions = FakeDownloads().apply {
            rows = listOf(download(7))
            fetchFailure = HubError.Damaged("its checksum does not match the hub's")
        }
        val files = vm(FilesFleet(ALL), actions, backgroundScope)
        files.attach()
        runCurrent()

        files.tap(7)!!.join()
        runCurrent()

        assertEquals("The file arrived damaged", files.state.value.error?.title)
    }

    @Test
    fun remove_asks_first_and_then_forgets_the_row() = runTest {
        val actions = FakeDownloads().apply { rows = listOf(download(1), download(2)) }
        val files = vm(FilesFleet(ALL), actions, backgroundScope)
        files.attach()
        runCurrent()
        assertTrue(files.state.value.canRemove)

        files.askRemove(1)
        runCurrent()
        assertEquals(1L, files.state.value.confirmRemove?.id)
        assertEquals(emptyList(), actions.removed, "asking is not removing")

        files.cancelRemove()
        assertNull(files.confirmRemove())
        assertEquals(emptyList(), actions.removed)

        files.askRemove(1)
        files.confirmRemove()!!.join()
        runCurrent()
        assertEquals(listOf(1L), actions.removed)
        assertEquals(listOf(2L), files.state.value.files.map { it.id })
        assertNull(files.state.value.confirmRemove)
    }

    /** Readonly: the hub hides `remove_download`, and the app checks the token too. */
    @Test
    fun a_readonly_token_is_never_offered_remove() = runTest {
        for ((caps, canWrite) in listOf(READ_ONLY to true, ALL to false)) {
            val actions = FakeDownloads().apply { rows = listOf(download(1)) }
            val files = vm(FilesFleet(caps), actions, backgroundScope, canWrite = canWrite)
            files.attach()
            runCurrent()

            assertTrue(files.state.value.available)
            assertFalse(files.state.value.canRemove)
            files.askRemove(1)
            runCurrent()
            assertNull(files.state.value.confirmRemove)
            assertNull(files.confirmRemove())
            assertEquals(emptyList(), actions.removed)
            files.detach()
        }
    }

    @Test
    fun a_failed_read_is_a_dismissable_error_and_the_rows_stay() = runTest {
        val actions = object : DownloadActions by FakeDownloads() {
            var fail = false
            override suspend fun list(sessionId: Long?, limit: Int?): DownloadList =
                if (fail) throw HubError.Transport(IllegalStateException("x")) else DownloadList(listOf(download(1)))
        }
        val files = FilesViewModel(FilesFleet(ALL), actions, backgroundScope, true, FakeHandoff(), clock = { NOW })
        files.attach()
        runCurrent()
        actions.fail = true

        files.refresh().join()
        runCurrent()

        assertEquals("Cannot reach the hub", files.state.value.error?.title)
        assertFalse(files.state.value.refreshing)
        assertEquals(listOf(1L), files.state.value.files.map { it.id })
        files.dismissError()
        runCurrent()
        assertNull(files.state.value.error)
    }
}
