package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.Download
import dev.claudefleet.mobile.model.DownloadList
import dev.claudefleet.mobile.net.FetchedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The calls the Files tab makes (claude-fleet file downloads, contract
 * revision 7), narrow for the reason every `*Actions` is: no screen holds a
 * [dev.claudefleet.mobile.net.HubClient], so none can make a call that skips
 * [AppSession.withClient] and its 401 rule.
 *
 * `list_downloads` is readonly; `send_file` and `remove_download` are writes
 * the hub refuses a readonly token, so the screens offer them only when
 * `Credentials.canWrite` and `HubCapabilities.sendFile` / `removeDownload`
 * both say so.
 */
interface DownloadActions {
    suspend fun list(sessionId: Long? = null, limit: Int? = null): DownloadList

    /** Ask the hub to copy [path] off [sessionId]'s host; answers the `fetching` row. */
    suspend fun send(sessionId: Long, path: String, note: String? = null): Download

    /** Forget one copy on the hub. False when it was already gone. */
    suspend fun remove(id: Long): Boolean

    /**
     * Download [download]'s bytes into [destination] (a file path), replacing
     * whatever is there. Written beside it first and moved into place only
     * once whole and checked, so a cut connection never leaves a half file
     * under the real name.
     */
    suspend fun fetch(
        download: Download,
        destination: String,
        onProgress: (received: Long, total: Long?) -> Unit = { _, _ -> },
    ): FetchedFile

    /**
     * Whether [destination] already holds [download] — a copy this device
     * fetched and checked earlier, of the same size — so opening it again
     * costs no second transfer.
     */
    suspend fun isCached(download: Download, destination: String): Boolean
}

/** [DownloadActions] against the paired hub, through [AppSession.withClient]. */
class HubDownloadActions(private val session: AppSession) : DownloadActions {
    override suspend fun list(sessionId: Long?, limit: Int?): DownloadList =
        session.withClient { it.listDownloads(sessionId, limit) }

    override suspend fun send(sessionId: Long, path: String, note: String?): Download =
        session.withClient { it.sendFile(sessionId, path, note) }

    override suspend fun remove(id: Long): Boolean =
        session.withClient { it.removeDownload(id) }

    override suspend fun fetch(
        download: Download,
        destination: String,
        onProgress: (received: Long, total: Long?) -> Unit,
    ): FetchedFile = session.withClient { client ->
        // File I/O off whatever dispatcher the caller is on: a tap's handler
        // may be the UI one, and this writes up to the hub's per-file ceiling.
        withContext(Dispatchers.IO) {
            val target = Path(destination)
            target.parent?.let { SystemFileSystem.createDirectories(it) }
            val partial = Path("$destination.part")
            try {
                val fetched = SystemFileSystem.sink(partial).buffered().use { sink ->
                    client.downloadFile(download.id, sink, expectedSize = download.size, onProgress = onProgress)
                }
                deleteQuietly(target)
                SystemFileSystem.atomicMove(partial, target)
                fetched
            } catch (e: CancellationException) {
                deleteQuietly(partial)
                throw e
            } catch (t: Throwable) {
                deleteQuietly(partial)
                throw t
            }
        }
    }

    override suspend fun isCached(download: Download, destination: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                SystemFileSystem.metadataOrNull(Path(destination))?.let { it.isRegularFile && it.size == download.size } == true
            } catch (_: Throwable) {
                false
            }
        }
}

/**
 * Where a hub download is kept on this device: `<cache>/downloads/<id>/<name>`.
 * One directory per id, so two files of the same name never overwrite one
 * another, and the file keeps its own name for Save / Share / Open.
 */
fun downloadCachePath(cacheDir: String, download: Download): String =
    "${cacheDir.trimEnd('/')}/downloads/${download.id}/${safeFileName(download.name)}"

/**
 * [name] made safe to be one path segment: no separators, no control
 * characters, no `.`/`..`, never empty. The hub's `name` is the last segment
 * of a path on a host, so this is a backstop rather than a rewrite.
 */
fun safeFileName(name: String): String {
    val cleaned = name.map { c -> if (c == '/' || c == '\\' || c == ':' || c.code < 0x20 || c.code == 0x7f) '_' else c }
        .joinToString("")
        .trim()
        .take(MAX_NAME)
    return if (cleaned.isEmpty() || cleaned.all { it == '.' }) "download" else cleaned
}

private const val MAX_NAME = 200

private fun deleteQuietly(path: Path) {
    try {
        SystemFileSystem.delete(path, mustExist = false)
    } catch (_: Throwable) {
        // Nothing to report: the failure being raised is the one that matters.
    }
}
