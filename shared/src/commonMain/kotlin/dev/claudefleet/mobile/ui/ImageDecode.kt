package dev.claudefleet.mobile.ui

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.io.encoding.Base64

/**
 * PNG or JPEG bytes as a bitmap Compose can draw: `BitmapFactory` on Android,
 * Skia's own decoder on the JVM and iOS. Null when the bytes are not an image
 * the platform reads — never a throw, since the bytes came off the wire (a
 * device screenshot, a tool result's image).
 */
internal expect fun decodeImage(bytes: ByteArray): ImageBitmap?

/** [data] decoded from base64 and then as an image; null when either step fails. */
internal fun decodeBase64Image(data: String): ImageBitmap? = base64Bytes(data)?.let(::decodeImage)

/** The bytes of a base64 text (whitespace allowed, as a transcript may wrap it); null when it is not base64. */
internal fun base64Bytes(data: String): ByteArray? = try {
    Base64.Default.decode(data.filterNot { it.isWhitespace() })
} catch (_: IllegalArgumentException) {
    null
}
