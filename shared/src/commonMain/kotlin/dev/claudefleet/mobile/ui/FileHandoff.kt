package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable

/** What can be done with a downloaded file once it is on this device. */
enum class Handoff(val label: String) {
    /** Into the device's own Downloads (Android 10+: MediaStore). */
    Save("Save"),

    /** The platform's share sheet — on iOS, also where *Save to Files* lives. */
    Share("Share"),

    /** Straight into an app that opens this kind of file. */
    Open("Open"),
}

/**
 * The platform half of the Files tab: where a download is cached, and the
 * ways a cached file leaves the app — Android's MediaStore `Downloads` and a
 * `FileProvider` URI for Share / Open, iOS's share sheet.
 *
 * Kept as small as the three verbs, so everything that decides *whether* and
 * *when* (fetching, checking, the error words) stays in common code where the
 * tests are.
 */
interface FileHandoff {
    /** A directory this app owns and the OS may clear; downloads live under `downloads/` in it. */
    val cacheDir: String

    /** The verbs this platform offers, in the order the sheet draws them. */
    val offered: List<Handoff>

    /**
     * Do [action] with the file at [path], named [name] (its MIME type is
     * guessed from the name). Answers a sentence to show when the result is
     * not visible by itself (a save), or null. Throws a [HandoffFailed] with
     * words for a person when it cannot.
     */
    suspend fun perform(action: Handoff, path: String, name: String): String?
}

/** A [FileHandoff] that could not do what it was asked; [message] is written for a person. */
class HandoffFailed(message: String) : Exception(message)

@Composable
internal expect fun rememberFileHandoff(): FileHandoff

/**
 * The MIME type for a file name, by extension — what Android's Open and
 * Save need to pick an app and a collection. Mirrors the common half of the
 * hub's own `Content-Type` choice; anything else is `application/octet-stream`.
 */
fun mimeTypeFor(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MIME[ext] ?: "application/octet-stream"
}

private val MIME = mapOf(
    "pdf" to "application/pdf",
    "txt" to "text/plain",
    "log" to "text/plain",
    "md" to "text/markdown",
    "csv" to "text/csv",
    "tsv" to "text/tab-separated-values",
    "json" to "application/json",
    "xml" to "application/xml",
    "html" to "text/html",
    "htm" to "text/html",
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "svg" to "image/svg+xml",
    "mp4" to "video/mp4",
    "mov" to "video/quicktime",
    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "zip" to "application/zip",
    "gz" to "application/gzip",
    "tgz" to "application/gzip",
    "tar" to "application/x-tar",
    "apk" to "application/vnd.android.package-archive",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)
