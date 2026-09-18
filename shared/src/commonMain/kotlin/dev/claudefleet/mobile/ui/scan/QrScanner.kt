package dev.claudefleet.mobile.ui.scan

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The camera scanner, which is the second — and last — `expect`/`actual` pair
 * in the app, after secure storage.
 *
 * It is deliberately the *whole* scanner rather than a thin camera wrapper:
 * CameraX with ZXing on Android and an `AVCaptureSession` on iOS have nothing
 * in common but the string they produce, and a shared abstraction over the two
 * would be a layer with one implementation of each method.
 *
 * **Nothing here is required for the app to work.** Every screen that shows a
 * scanner also shows a field to type the code into, always, not as a fallback
 * that appears when this fails. A phone with no camera, or one where the
 * permission was refused, pairs by typing — see `PairScreen`.
 */
expect fun qrScannerSupported(): Boolean

/**
 * A live camera preview that reports every QR it decodes.
 *
 * [onScanned] is called **once per frame** for as long as a code is in view —
 * thirty times a second, with the same string. De-duplicating is the caller's,
 * because only the caller knows whether the last one was acted on;
 * [dev.claudefleet.mobile.ui.PairViewModel] does it and says why.
 *
 * [onUnavailable] is called with a sentence written for a person when the
 * camera cannot be used: the permission was refused, there is none, or the
 * capture session would not start. It is never an exception's text — a camera
 * failure can name a device path, and this string goes on the screen.
 */
@Composable
expect fun QrScannerView(
    onScanned: (String) -> Unit,
    onUnavailable: (String) -> Unit,
    modifier: Modifier,
)

/** What [onUnavailable] is told, kept identical across the platforms. */
internal const val CAMERA_PERMISSION_REFUSED: String =
    "the camera permission was refused. Type the 8-character code instead — " +
        "it is printed under the QR."

internal const val CAMERA_UNAVAILABLE: String =
    "the camera could not be started. Type the 8-character code instead — " +
        "it is printed under the QR."
