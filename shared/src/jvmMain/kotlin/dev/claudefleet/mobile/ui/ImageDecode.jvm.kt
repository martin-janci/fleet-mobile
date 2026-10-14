package dev.claudefleet.mobile.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image

internal actual fun decodeImage(bytes: ByteArray): ImageBitmap? =
    if (bytes.isEmpty()) null else try {
        Image.makeFromEncoded(bytes).toComposeImageBitmap()
    } catch (_: RuntimeException) {
        null
    }
