package dev.claudefleet.mobile.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A screenshot's base64 drawn through Skia, as the JVM and iOS actuals do it.
 * JVM-only: it needs Skia's native runtime, which only `jvmTest` carries
 * (`compose.desktop.currentOs`).
 */
class ImageDecodeTest {
    /** A 2 × 3 RGB PNG. */
    private val png = "iVBORw0KGgoAAAANSUhEUgAAAAIAAAADCAIAAAA2iEnWAAAAEUlEQVR4nGP4z8AARGACTgMAPtYF+1iCz0YAAAAASUVORK5CYII="

    @Test
    fun a_png_decodes_at_its_own_size() {
        val bitmap = assertNotNull(decodeBase64Image(png))
        assertEquals(2 to 3, bitmap.width to bitmap.height)
        // Wrapped as a transcript may wrap it.
        assertNotNull(decodeBase64Image(png.chunked(20).joinToString("\n")))
    }

    @Test
    fun what_is_not_an_image_is_null_and_never_a_throw() {
        assertNull(decodeBase64Image("not base64 at all!"))
        assertNull(decodeBase64Image("aGVsbG8="))
        assertNull(decodeBase64Image(""))
        assertNull(decodeImage(ByteArray(0)))
    }
}
