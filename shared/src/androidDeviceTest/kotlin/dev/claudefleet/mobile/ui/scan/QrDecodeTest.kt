package dev.claudefleet.mobile.ui.scan

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android QR scanner's decode path, executed.
 *
 * `QrScanner.android.kt` is the one piece of `androidMain` that had no test of
 * any kind: the camera plumbing needs a device, so the frame handling under it
 * was never run either — and its two documented failure modes both look
 * *identical from the outside*. A sheared image and a half-stale image do not
 * crash and do not log; the viewfinder simply sits there decoding nothing,
 * which is indistinguishable from "no QR in shot". That is precisely the shape
 * of bug that has to be asserted rather than reviewed.
 *
 * So these tests feed [packLuminance] the buffer layouts a real `YUV_420_888`
 * Y plane arrives in, and then put the result through the very same
 * `PlanarYUVLuminanceSource` → `HybridBinarizer` → `QRCodeReader` chain that
 * `QrAnalyzer` uses, with the same `TRY_HARDER` hint. The assertion is the one
 * that matters to a person holding a phone: **the pairing URL comes back out**.
 *
 * It lives in `androidDeviceTest` rather than in a host test because the code
 * under test is `androidMain` — which this module compiles only for Android —
 * and because the emulator job already exists to run exactly this kind of
 * claim. Nothing here needs the camera: the frames are synthesised, so the test
 * is deterministic and needs no permission.
 */
@RunWith(AndroidJUnit4::class)
class QrDecodeTest {

    /**
     * A realistic payload: what `fleet-hub pair` actually puts in the QR, code
     * and all. Decoding a short string is a much easier problem than decoding
     * this one, and the easy version is not the one the app has to solve.
     */
    private val pairUrl = "https://fleet.example.com/pair#ABCDEFGH"

    // ---- the packer, against the layouts a real Y plane arrives in ----

    /**
     * The tightly packed case: `rowStride == width`, which is what a device
     * whose width is already a multiple of the alignment produces.
     */
    @Test
    fun a_tightly_packed_frame_decodes() {
        val frame = qrFrame(pairUrl)
        assertEquals(pairUrl, decode(frame.pack(rowStride = frame.width)))
    }

    /**
     * The padded case, and the one that matters: most devices align each row of
     * the Y plane, so `rowStride` is larger than `width` and every row carries
     * bytes that are not pixels.
     *
     * If those were copied through as image data the picture would shear a
     * little further on each row and ZXing would find no finder patterns at
     * all. The failure is silent, so this test is the only thing that says the
     * padding is being skipped.
     */
    @Test
    fun a_row_padded_frame_decodes() {
        val frame = qrFrame(pairUrl)
        for (padding in listOf(1, 8, 16, 61, 128)) {
            val packed = frame.pack(rowStride = frame.width + padding)
            assertEquals(
                pairUrl,
                decode(packed),
                "a Y plane padded by $padding bytes a row must still decode",
            )
        }
    }

    /**
     * The same frame, packed as if the stride were the width when it is not —
     * the bug this code is written to avoid.
     *
     * Without it the padded test above could pass for the wrong reason: if the
     * padding happened not to matter, both packings would decode and the test
     * would be asserting nothing. This pins that the distinction is real and
     * that getting it wrong genuinely costs the decode.
     */
    @Test
    fun ignoring_the_row_stride_is_what_breaks_it() {
        val frame = qrFrame(pairUrl)
        val strided = frame.bytes(rowStride = frame.width + 16)
        // Read it back as though every row were `width` long: the shear.
        val sheared = ByteArray(frame.width * frame.height)
        ByteBuffer.wrap(strided).get(sheared, 0, sheared.size)

        assertNull(
            decode(sheared),
            "if a sheared frame still decoded, the stride handling would be untested",
        )
    }

    /**
     * A frame that ends early must not leave the previous frame's rows behind.
     *
     * The luminance array is reused across frames for the sake of the garbage
     * collector, so a short read would otherwise hand ZXing a composite of two
     * images — the tail of an old frame under the head of a new one. Review
     * N-A2. The rows that were not filled are zeroed, which reads as a black
     * band: an honest half-frame rather than a convincing lie.
     */
    @Test
    fun a_short_frame_is_zeroed_rather_than_left_stale() {
        val frame = qrFrame(pairUrl)
        val reused = ByteArray(frame.width * frame.height)

        // Frame one: complete, and it decodes.
        packLuminance(
            ByteBuffer.wrap(frame.bytes(frame.width)),
            frame.width,
            frame.height,
            frame.width,
            reused,
        )
        assertEquals(pairUrl, decode(reused.copyOf()))

        // Frame two: cut off half way down.
        val half = frame.height / 2
        val truncated = frame.bytes(frame.width).copyOf(frame.width * half)
        packLuminance(
            ByteBuffer.wrap(truncated),
            frame.width,
            frame.height,
            frame.width,
            reused,
        )

        val tail = reused.copyOfRange(frame.width * half, reused.size)
        assertTrue(
            tail.all { it == 0.toByte() },
            "every row the short frame did not fill must be zeroed, not left from the last frame",
        )
        assertNull(decode(reused), "half a QR is not a QR")
    }

    /**
     * The padded path's last row.
     *
     * A real `YUV_420_888` Y plane is `rowStride * (height - 1) + width` bytes:
     * the final row carries no padding because there is nothing after it to
     * align. A packer that insisted on a whole stride for every row would drop
     * that last row, and a QR whose bottom edge is missing does not decode.
     */
    @Test
    fun the_last_row_of_a_padded_plane_carries_no_padding() {
        val frame = qrFrame(pairUrl)
        val rowStride = frame.width + 32
        val full = frame.bytes(rowStride)
        // Trim the final row's padding, exactly as the camera delivers it.
        val realistic = full.copyOf(rowStride * (frame.height - 1) + frame.width)

        val into = ByteArray(frame.width * frame.height)
        packLuminance(
            ByteBuffer.wrap(realistic),
            frame.width,
            frame.height,
            rowStride,
            into,
        )
        assertEquals(
            pairUrl,
            decode(into),
            "the last row arrives without its padding and must still be taken",
        )
    }

    /**
     * An all-grey frame — the camera pointed at nothing — decodes to nothing and
     * does not throw.
     *
     * `QrAnalyzer` swallows `NotFoundException` because it is the overwhelmingly
     * common case, thirty times a second. This pins that the common case really
     * is that exception and not something the analyzer would let through.
     */
    @Test
    fun a_frame_with_no_code_in_it_decodes_to_nothing() {
        val blank = ByteArray(240 * 240) { (-1).toByte() }
        assertNull(decode(blank, width = 240, height = 240))
    }

    /** A buffer shorter than a single row fills nothing and leaves nothing stale. */
    @Test
    fun a_buffer_shorter_than_one_row_is_all_zeroes() {
        val into = ByteArray(64 * 64) { 7 }
        packLuminance(ByteBuffer.wrap(ByteArray(10)), 64, 64, 64 + 4, into)
        assertTrue(into.all { it == 0.toByte() }, "nothing was read, so nothing may survive")
    }

    /** Two different payloads really do produce different frames — a guard on the fixture. */
    @Test
    fun the_fixture_encodes_what_it_is_given() {
        val one = qrFrame("https://fleet.example.com/pair#ABCDEFGH")
        val two = qrFrame("https://fleet.example.com/pair#HGFEDCBA")
        assertNotEquals(
            decode(one.pack(one.width)),
            decode(two.pack(two.width)),
            "if both frames decoded to the same thing the other tests would prove nothing",
        )
        assertEquals("https://fleet.example.com/pair#HGFEDCBA", decode(two.pack(two.width)))
    }

    // ---- the fixture ----

    /**
     * A QR code as a luminance image, the way a camera would see one: black
     * modules on white, with a quiet zone, scaled up so the modules are several
     * pixels across.
     */
    private class Frame(val width: Int, val height: Int, private val pixels: ByteArray) {

        /** The image as it would sit in a plane with this [rowStride]. */
        fun bytes(rowStride: Int): ByteArray {
            val out = ByteArray(rowStride * height)
            for (y in 0 until height) {
                pixels.copyInto(out, y * rowStride, y * width, (y + 1) * width)
            }
            return out
        }

        /** The image through [packLuminance], as the analyzer would pack it. */
        fun pack(rowStride: Int): ByteArray {
            val into = ByteArray(width * height)
            packLuminance(ByteBuffer.wrap(bytes(rowStride)), width, height, rowStride, into)
            return into
        }
    }

    private fun qrFrame(text: String, scale: Int = 4, quietZone: Int = 4): Frame {
        val matrix: BitMatrix = QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            // The writer's own size hints are honoured loosely; the scaling
            // below is what actually controls the module size, so the matrix is
            // asked for at its natural size with the quiet zone we want.
            1,
            1,
            mapOf(EncodeHintType.MARGIN to quietZone),
        )
        val width = matrix.width * scale
        val height = matrix.height * scale
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                // A set module is black (0); the background is white (255).
                val dark = matrix.get(x / scale, y / scale)
                pixels[y * width + x] = if (dark) 0 else (-1).toByte()
            }
        }
        return Frame(width, height, pixels)
    }

    /**
     * The analyzer's own decode chain, byte for byte: the same luminance source,
     * the same binarizer, the same reader, the same hint.
     */
    private fun decode(
        luminance: ByteArray,
        width: Int = sizeOf(luminance),
        height: Int = sizeOf(luminance),
    ): String? {
        val source = PlanarYUVLuminanceSource(
            luminance, width, height, 0, 0, width, height, false,
        )
        val reader = QRCodeReader()
        return try {
            reader.decode(
                BinaryBitmap(HybridBinarizer(source)),
                mapOf(DecodeHintType.TRY_HARDER to true),
            ).text
        } catch (_: NotFoundException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    /** The frames here are square, so one side is the square root. */
    private fun sizeOf(luminance: ByteArray): Int {
        var side = 0
        while ((side + 1) * (side + 1) <= luminance.size) side += 1
        return side
    }
}
