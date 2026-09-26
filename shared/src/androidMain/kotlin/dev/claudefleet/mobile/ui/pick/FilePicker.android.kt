package dev.claudefleet.mobile.ui.pick

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import dev.claudefleet.mobile.model.ATTACH_MAX_BYTES
import dev.claudefleet.mobile.model.PickedFile
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Always: `OpenMultipleDocuments` is part of the platform, not an optional app. */
actual fun filePickerSupported(): Boolean = true

@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(
        // No storage permission is asked for and none is needed: the picker
        // runs outside this app and hands back a read grant per URI.
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        // Cancelling delivers an empty list here rather than skipping the
        // callback, which is the whole reason the shared contract can promise
        // that a caller's spinner always stops.
        picked(uris.mapNotNull { read(context.contentResolver, it) })
    }
    // Every type. The system picker lists Photos among its providers, so a
    // screenshot is reachable here without a second, photo-specific flow.
    return { launcher.launch(arrayOf("*/*")) }
}

/**
 * The name and the bytes, or null if the file is over the ceiling or the grant
 * did not survive the trip.
 *
 * The size is asked for in the **same** `ContentResolver.query` as the name —
 * `OpenableColumns.SIZE` sits beside `DISPLAY_NAME` in the same cursor — so a
 * file over [ATTACH_MAX_BYTES] costs one cursor and is never opened. That is
 * what makes the shared contract's "bounded" true rather than aspirational:
 * the picker accepts every MIME type, so without it a 2 GB video is one tap
 * away from being read whole into the heap on the main thread.
 *
 * (The wildcard the launcher passes is deliberately not written out in this
 * comment — the two characters that end it also end a KDoc block, which is
 * exactly the compile error this paragraph once was.)
 *
 * A provider that publishes no size is not trusted to be small: the read is
 * capped instead, and a stream that runs past the ceiling is abandoned rather
 * than finished and then rejected.
 *
 * Read on the callback's thread, which is the main one. That is on purpose and
 * it is now genuinely bounded: doing it here means [PickedFile] is complete the
 * instant it exists — no half-file that fails later at Send, when the URI's
 * grant may be gone.
 */
private fun read(resolver: ContentResolver, uri: Uri): PickedFile? {
    // Looked up by name rather than by projection position: a provider is not
    // obliged to return the columns in the order they were asked for.
    val meta = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) {
                    null
                } else {
                    val nameAt = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeAt = c.getColumnIndex(OpenableColumns.SIZE)
                    Pair(
                        if (nameAt >= 0 && !c.isNull(nameAt)) c.getString(nameAt) else null,
                        // A provider may answer the name and not the size.
                        if (sizeAt >= 0 && !c.isNull(sizeAt)) c.getLong(sizeAt) else null,
                    )
                }
            }
    }.getOrNull()

    val name = meta?.first ?: uri.lastPathSegment ?: return null
    val size = meta?.second
    if (size != null && size > ATTACH_MAX_BYTES) return null

    val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readCapped() } }
        .getOrNull()
        ?: return null
    return PickedFile(name = name, size = bytes.size.toLong(), bytes = bytes)
}

/**
 * Everything, or null the moment there is more than [ATTACH_MAX_BYTES] of it.
 *
 * Only reached when the provider would not say how big the file is. It stops at
 * the first byte past the ceiling rather than reading to the end and then
 * measuring, so a file that lied about being unsized still cannot be held.
 */
private fun InputStream.readCapped(): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > ATTACH_MAX_BYTES) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
