package dev.claudefleet.mobile.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android's half of the Files tab.
 *
 * - **Save** writes into the shared `Downloads` collection through
 *   MediaStore, which needs no storage permission from Android 10 (API 29).
 *   Below that a public write would need `WRITE_EXTERNAL_STORAGE`, which this
 *   app does not ask for (`AndroidHostTest` holds it to two permissions), so
 *   Save is simply not offered there and Share carries the file instead.
 * - **Share** / **Open** hand a `content://` URI from the app's
 *   `FileProvider` (declared in `androidApp`'s manifest, authority
 *   `<package>.files`, `res/xml/file_paths.xml` exposing only
 *   `cache/downloads/`) with a one-off read grant — never a `file://` path,
 *   which Android refuses to let leave the app.
 */
@Composable
internal actual fun rememberFileHandoff(): FileHandoff {
    val context = LocalContext.current
    return remember(context) { AndroidFileHandoff(context) }
}

private class AndroidFileHandoff(private val context: Context) : FileHandoff {
    override val cacheDir: String = context.cacheDir.absolutePath

    override val offered: List<Handoff> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listOf(Handoff.Save, Handoff.Share, Handoff.Open)
        else listOf(Handoff.Share, Handoff.Open)

    override suspend fun perform(action: Handoff, path: String, name: String): String? {
        val file = File(path)
        if (!file.isFile) throw HandoffFailed("The downloaded copy is gone from this phone. Tap the file again.")
        val mime = mimeTypeFor(name)
        return when (action) {
            Handoff.Save -> save(file, name, mime)
            Handoff.Share -> {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uriFor(file))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                start(Intent.createChooser(send, name), "No app on this phone can take this file.")
                null
            }
            Handoff.Open -> {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uriFor(file), mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                start(view, "No app on this phone opens this kind of file. Try Share.")
                null
            }
        }
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)

    private fun start(intent: Intent, whenNone: String) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            throw HandoffFailed(whenNone)
        }
    }

    private suspend fun save(file: File, name: String, mime: String): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw HandoffFailed("Saving needs Android 10 or later. Use Share instead.")
        }
        return withContext(Dispatchers.IO) { writeToDownloads(file, name, mime) }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToDownloads(file: File, name: String, mime: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Hidden from other apps until the bytes are all there.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw HandoffFailed("This phone would not make a file in Downloads.")
        try {
            val out = resolver.openOutputStream(uri)
                ?: throw HandoffFailed("This phone would not open the new file in Downloads.")
            out.use { sink -> file.inputStream().use { it.copyTo(sink) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            // A half-written entry would sit in Downloads forever, invisible
            // (pending) or broken; take it back out.
            runCatching { resolver.delete(uri, null, null) }
            if (t is HandoffFailed) throw t
            throw HandoffFailed("Saving to Downloads failed (${t::class.simpleName ?: "error"}).")
        }
        return "Saved to Downloads"
    }
}

/**
 * The `FileProvider` authority is the app's package plus this — the same
 * `${applicationId}.files` the manifest declares, so a debug build
 * (`.debug` suffix) and a release build each name their own.
 */
private const val FILE_PROVIDER_SUFFIX = ".files"
