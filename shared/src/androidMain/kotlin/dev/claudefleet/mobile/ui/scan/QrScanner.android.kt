package dev.claudefleet.mobile.ui.scan

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Android always builds the scanner in; whether it can *run* is a permission
 * question answered at the moment it is shown, not a build-time one.
 */
actual fun qrScannerSupported(): Boolean = true

/**
 * CameraX for the preview and the frames, ZXing for the decode.
 *
 * **Not ML Kit**, and the reason is measured rather than stylistic.
 * `com.google.mlkit:barcode-scanning` pulls `play-services-basement`,
 * `play-services-base`, `play-services-tasks`,
 * `play-services-mlkit-barcode-scanning`, three Firebase encoder artifacts and
 * `transport-backend-cct` — Google's Cloud Client Telemetry uploader — and took
 * the debug APK from 14 MB to 40 MB. A phone paired to a self-hosted fleet hub
 * is precisely the phone that should not have to carry a Google telemetry client
 * in order to read one QR code, and may well not have Play Services at all.
 * ZXing is pure Java, a few hundred kilobytes, and talks to nobody.
 */
@Composable
actual fun QrScannerView(
    onScanned: (String) -> Unit,
    onUnavailable: (String) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    // Callbacks are read through `rememberUpdatedState` because the CameraX
    // binding below outlives a recomposition: capturing the first lambda would
    // send every later frame to a view model that has already been replaced.
    val scanned by rememberUpdatedState(onScanned)
    val unavailable by rememberUpdatedState(onUnavailable)

    val owner = remember(context) { context.findLifecycleOwner() }
    var granted by remember(context) { mutableStateOf(context.hasCameraPermission()) }
    var asked by remember(context) { mutableStateOf(false) }

    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) unavailable(CAMERA_PERMISSION_REFUSED)
    }

    LaunchedEffect(granted, asked) {
        if (!granted && !asked) {
            asked = true
            request.launch(Manifest.permission.CAMERA)
        }
    }

    if (owner == null) {
        // Compose hosted somewhere with no lifecycle to bind the camera to. Not
        // expected — `MainActivity` is a `ComponentActivity` — but reporting it
        // leaves the manual field usable instead of throwing inside CameraX on a
        // thread nobody is catching.
        LaunchedEffect(Unit) { unavailable(CAMERA_UNAVAILABLE) }
        return
    }
    if (!granted) return

    // One analysis thread, shut down with the composable. `QrAnalyzer` is not
    // thread-safe and does not need to be: CameraX calls it on this executor.
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(executor) { onDispose { executor.shutdown() } }

    val analyzer = remember { QrAnalyzer { code -> scanned(code) } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener(
                {
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().build()
                        preview.setSurfaceProvider(view.surfaceProvider)
                        val analysis = ImageAnalysis.Builder()
                            // Decode the newest frame and drop the backlog: a
                            // queue of stale frames of the same QR is thirty
                            // redundant decodes, not thirty chances.
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                            .build()
                        analysis.setAnalyzer(executor, analyzer)
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            owner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    } catch (_: Throwable) {
                        // Deliberately not the throwable's message: a CameraX
                        // failure names device ids and vendor strings, and this
                        // string goes on the screen.
                        unavailable(CAMERA_UNAVAILABLE)
                    }
                },
                mainExecutor,
            )
            view
        },
    )
}

/**
 * One camera frame in, zero or one QR string out.
 *
 * Reads only the **Y plane** of the YUV frame, which is the luminance buffer
 * ZXing's [PlanarYUVLuminanceSource] wants — no colour conversion and no copy of
 * the chroma planes. The row stride is honoured rather than assumed equal to the
 * width: on plenty of devices it is not, and treating a padded buffer as tightly
 * packed shears the image and simply never decodes.
 */
private class QrAnalyzer(private val onCode: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = QRCodeReader()
    private val hints = mapOf(DecodeHintType.TRY_HARDER to true)
    private var luminance = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            // The frame is decoded as it arrives, with no rotation pass. A QR's
            // three finder patterns are what ZXing's detector locates, and it
            // locates them at any orientation — and `PlanarYUVLuminanceSource`
            // does not implement `rotateCounterClockwise()` anyway: the base
            // class throws `UnsupportedOperationException` for sources that do
            // not override it, which this one does not.
            val source = image.luminanceSource() ?: return
            val code = decode(source)
            if (code != null) onCode(code)
        } finally {
            // Whatever happened, the frame has to be released or the pipeline
            // stalls after a handful of images.
            image.close()
        }
    }

    private fun decode(source: PlanarYUVLuminanceSource): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: NotFoundException) {
        // The overwhelmingly common case: this frame has no QR in it.
        null
    } catch (_: Exception) {
        // A checksum or format failure is a half-seen code, not an error worth
        // telling anyone about; the next frame is 33 ms away.
        null
    } finally {
        reader.reset()
    }

    /**
     * The Y plane as a tightly packed `width * height` buffer.
     *
     * **`pixelStride` is deliberately not read** (review N-A1). `YUV_420_888`
     * guarantees the Y plane's pixel stride is 1, which is the whole reason the
     * Y plane can be handed to ZXing as a luminance buffer at all; if it could
     * be 2, every second byte here would be padding and the image would be
     * garbage rather than merely sheared. It is written down because "the field
     * exists and is not read" looks like an oversight, and the next person
     * should be able to tell that it was considered.
     *
     * The packing itself is [packLuminance], which is where the stride and
     * short-buffer rules live and where they are tested. This method is only
     * the part that needs an [ImageProxy] to exist.
     */
    private fun ImageProxy.luminanceSource(): PlanarYUVLuminanceSource? {
        val plane = planes.firstOrNull() ?: return null
        val needed = width * height
        if (needed <= 0) return null
        // The array is reused across frames — this runs thirty times a second
        // and a fresh megabyte each time is work the collector does not need.
        if (luminance.size != needed) luminance = ByteArray(needed)
        packLuminance(plane.buffer, width, height, plane.rowStride, luminance)

        return PlanarYUVLuminanceSource(
            luminance,
            width,
            height,
            0,
            0,
            width,
            height,
            false,
        )
    }
}

/**
 * Copy one frame's Y plane out of [buffer] into [into] as a tightly packed
 * `width * height` luminance image.
 *
 * Split out of [QrAnalyzer] and made `internal` so it can be executed by a test.
 * Both of the rules below are ones whose failure mode is a viewfinder that
 * simply never decodes — no crash, no message, nothing to debug from — which is
 * the kind of thing that has to be asserted rather than read:
 *
 *  - **[rowStride] is honoured rather than assumed equal to [width].** On plenty
 *    of devices the Y plane is padded to a multiple of 16 or 64, and treating a
 *    padded buffer as tightly packed shears the image by a few pixels per row.
 *    ZXing then finds no finder patterns and reports nothing, frame after frame.
 *  - **[into] is reused across frames, so whatever this frame does not fill is
 *    zeroed** (review N-A2). A short buffer would otherwise leave the *previous*
 *    frame's rows in place and hand ZXing a composite of two images. A black
 *    band is an honest half-frame; a stale one is not.
 *
 * [into] must be at least `width * height` long; the caller sizes it.
 */
internal fun packLuminance(
    buffer: ByteBuffer,
    width: Int,
    height: Int,
    rowStride: Int,
    into: ByteArray,
) {
    val needed = width * height
    val filled: Int
    if (rowStride == width) {
        filled = minOf(needed, buffer.remaining())
        buffer.get(into, 0, filled)
    } else {
        // A padded buffer: take `width` bytes from the start of each row and
        // skip the padding. The last row of a `YUV_420_888` plane is commonly
        // `width` bytes rather than `rowStride`, which is why the amount taken
        // is whatever is left rather than a full stride.
        val row = ByteArray(rowStride)
        var rows = 0
        for (y in 0 until height) {
            val take = minOf(rowStride, buffer.remaining())
            if (take < width) break
            buffer.get(row, 0, take)
            row.copyInto(into, y * width, 0, width)
            rows = y + 1
        }
        filled = rows * width
    }
    if (filled < needed) into.fill(0, filled, needed)
}

/** `Context.getMainExecutor` is API 28; `minSdk` here is 26. */
private val mainExecutor: Executor = Executor { command ->
    Handler(Looper.getMainLooper()).post(command)
}

private fun Context.hasCameraPermission(): Boolean =
    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/**
 * The `LifecycleOwner` CameraX binds to, found by unwrapping the context.
 *
 * Rather than `LocalLifecycleOwner`, which changed package between Compose
 * Multiplatform releases and would pull a second lifecycle artifact into a
 * module that already resolves two flavours of AndroidX.
 */
private fun Context.findLifecycleOwner(): LifecycleOwner? {
    var context: Context? = this
    while (context != null) {
        if (context is LifecycleOwner) return context
        context = (context as? ContextWrapper)?.baseContext
    }
    return null
}
