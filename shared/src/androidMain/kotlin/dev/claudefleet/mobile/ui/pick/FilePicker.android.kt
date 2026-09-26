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
import dev.claudefleet.mobile.model.ATTACH_MAX_TOTAL
import dev.claudefleet.mobile.model.PickResult
import dev.claudefleet.mobile.model.PickedFile
import dev.claudefleet.mobile.model.SkipReason
import dev.claudefleet.mobile.model.SkippedFile
import dev.claudefleet.mobile.model.readCap
import dev.claudefleet.mobile.model.skipForSize
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Always: `OpenMultipleDocuments` is part of the platform, not an optional app. */
actual fun filePickerSupported(): Boolean = true

@Composable
actual fun rememberFilePicker(onPicked: (PickResult) -> Unit): () -> Unit {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(
        // No storage permission is asked for and none is needed: the picker
        // runs outside this app and hands back a read grant per URI.
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        // Cancelling delivers an empty list here rather than skipping the
        // callback, which is the whole reason the shared contract can promise
        // that a caller's spinner always stops — and with no uris there is
        // nothing to skip either, so the result is empty on both sides, which
        // is exactly how the contract spells "cancelled".
        val files = mutableListOf<PickedFile>()
        val skipped = mutableListOf<SkippedFile>()
        // One pick's own budget, spent as it goes. Without it a multi-select
        // of twenty 10 MB files reads 200 MB into the heap on this thread —
        // an OOM and an ANR on a mid-range phone — before the composer gets
        // a chance to refuse the batch. The composer still checks the total
        // again over its queue; this is the bound that stops the bytes ever
        // being here to check.
        var used = 0L
        for (uri in uris) {
            when (val outcome = read(context.contentResolver, uri, ATTACH_MAX_TOTAL - used)) {
                is Outcome.Took -> {
                    files += outcome.file
                    used += outcome.file.size
                }
                is Outcome.Left -> skipped += outcome.file
            }
        }
        picked(PickResult(files, skipped))
    }
    // Every type. The system picker lists Photos among its providers, so a
    // screenshot is reachable here without a second, photo-specific flow.
    return { launcher.launch(arrayOf("*/*")) }
}

/** One picked URI: either it came back whole, or it is named as left behind. */
private sealed interface Outcome {
    class Took(val file: PickedFile) : Outcome
    class Left(val file: SkippedFile) : Outcome
}

/**
 * The name and the bytes, or a [SkippedFile] naming what went — the file was
 * over the per-file ceiling, did not fit in what [remaining] is left of this
 * pick's total, or the grant did not survive the trip.
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
private fun read(resolver: ContentResolver, uri: Uri, remaining: Long): Outcome {
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

    // A URI with no name at all is still a file the person chose, so it is
    // reported rather than dropped — under the only handle there is.
    val name = meta?.first ?: uri.lastPathSegment ?: uri.toString()
    val size = meta?.second
    if (size != null) skipForSize(name, size, remaining)?.let { return Outcome.Left(it) }

    // What may be read when the provider would not say how big it is: the
    // per-file ceiling, or the rest of this pick's total when that is less.
    val cap = readCap(remaining)

    // The two ways this can still come to nothing are kept apart, because
    // they are two different sentences on the composer: a stream that will
    // not open (or throws part-way) is *unreadable*, while `readCapped`
    // answering null is the ceiling — the only outcome left for a provider
    // that would not declare a size.
    val bytes = try {
        val stream = resolver.openInputStream(uri)
            ?: return Outcome.Left(SkippedFile(name, size, SkipReason.Unreadable))
        stream.use { it.readCapped(cap) }
    } catch (t: Throwable) {
        return Outcome.Left(SkippedFile(name, size, SkipReason.Unreadable))
    } ?: return Outcome.Left(
        // Which bound it hit is knowable: the cap is the pick's remainder
        // only when that is the smaller of the two.
        SkippedFile(name, size, if (cap < ATTACH_MAX_BYTES) SkipReason.OverTotal else SkipReason.TooBig),
    )
    return Outcome.Took(PickedFile(name = name, size = bytes.size.toLong(), bytes = bytes))
}

/**
 * Everything, or null the moment there is more than [cap] of it.
 *
 * Only reached when the provider would not say how big the file is. It stops at
 * the first byte past the ceiling rather than reading to the end and then
 * measuring, so a file that lied about being unsized still cannot be held —
 * and [cap] falls below [ATTACH_MAX_BYTES] once this pick has spent most of
 * its total, so an unsized file cannot walk past that bound either.
 */
private fun InputStream.readCapped(cap: Long): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        if (total > cap) return null
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
