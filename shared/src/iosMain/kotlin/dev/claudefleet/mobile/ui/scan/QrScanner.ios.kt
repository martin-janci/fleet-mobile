@file:OptIn(ExperimentalForeignApi::class)

package dev.claudefleet.mobile.ui.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureSessionPresetHigh
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIView
import platform.darwin.NSObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue

/** Every iPhone this app runs on has a camera; the permission is asked at use. */
actual fun qrScannerSupported(): Boolean = true

/**
 * `AVCaptureSession` with an `AVCaptureMetadataOutput`, which decodes QR codes
 * in hardware — iOS needs no decoding library at all, because the platform
 * already is one.
 *
 * The preview is an `AVCaptureVideoPreviewLayer` on a plain `UIView`, hosted in
 * Compose through `UIKitView`.
 *
 * **Unrun.** This compiles for `iosArm64` and `iosSimulatorArm64` and has never
 * executed: linking the framework and running it need macOS, and no Mac has
 * touched this repository. In particular nobody has confirmed that
 * `NSCameraUsageDescription` is present in the host app's `Info.plist` — without
 * it iOS terminates the process rather than refusing the permission.
 */
@Composable
actual fun QrScannerView(
    onScanned: (String) -> Unit,
    onUnavailable: (String) -> Unit,
    modifier: Modifier,
) {
    val scanned by rememberUpdatedState(onScanned)
    val unavailable by rememberUpdatedState(onUnavailable)

    val delegate = remember { QrMetadataDelegate { code -> scanned(code) } }
    val session = remember { AVCaptureSession() }

    // The one place the session is stopped. `UIKitView`'s `onRelease` is the
    // other candidate and was doing it too, which was not harmful -- the second
    // call is a no-op -- but two owners of a teardown is how one of them later
    // grows a condition the other does not have. This one is the reliable
    // half: it runs whenever the composable leaves, including on the paths
    // where the view is never released.
    //
    // Off the main queue for the same reason as `startRunning` below:
    // `stopRunning` blocks until the graph has torn down.
    DisposableEffect(session) {
        onDispose { session.stopInBackground() }
    }

    // Null while the answer is not yet known, which is the state the very first
    // use is in: iOS only shows the permission sheet when the app asks.
    //
    // Without this the shared contract was a lie on this platform. It says
    // `onUnavailable` fires when "the permission was refused", and
    // [CAMERA_PERMISSION_REFUSED] exists and is kept identical across the two
    // platforms — and the only caller was the Android actual. On iOS a denied
    // permission does not stop `AVCaptureSession` starting: it starts, delivers
    // nothing, and the viewfinder is simply black with no explanation and no
    // hint that the eight characters under the QR would work.
    var permitted by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        when (AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)) {
            AVAuthorizationStatusAuthorized -> permitted = true
            AVAuthorizationStatusNotDetermined ->
                // Asking is what shows the sheet, and the callback arrives on an
                // arbitrary queue — assigning Compose state is safe, drawing is
                // not, and nothing is drawn here.
                AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted ->
                    permitted = granted
                }
            // Denied and Restricted. Restricted is a managed device and no
            // amount of asking changes it, so both get the same sentence: the
            // code can always be typed.
            else -> permitted = false
        }
    }

    LaunchedEffect(permitted) {
        if (permitted == false) unavailable(CAMERA_PERMISSION_REFUSED)
    }

    // Nothing is composed until the answer is yes. Building the capture graph
    // while the sheet is up would start a session that cannot see anything.
    if (permitted != true) return

    // Why the start is not in `factory`, where it reads more naturally:
    //
    //  - `AVCaptureSession.startRunning` is documented as a *blocking* call, and
    //    `factory` runs on the main thread during layout. On a cold camera it
    //    takes long enough to drop frames, and the person sees the app freeze
    //    at the moment they tapped "Scan the QR code".
    //  - the failure path called `unavailable(...)` from inside `factory`,
    //    which writes Compose state during composition. That is the recipe for
    //    a recomposition loop, and it is reached exactly when something is
    //    already wrong -- another app holding the camera -- so the bug would
    //    have shown up only on the unhappy path.
    //
    // So the composable does the cheap part and the effect does the slow part.
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        val started = withContext(Dispatchers.Default) { session.startCapturing(delegate) }
        if (!started) failed = true
    }

    LaunchedEffect(failed) {
        if (failed) unavailable(CAMERA_UNAVAILABLE)
    }

    UIKitView(
        modifier = modifier,
        factory = {
            val view = UIView(frame = CGRectZero.readValue())
            // The preview layer can be built before the session runs -- it
            // shows nothing until there are frames, and then it shows them.
            val layer = AVCaptureVideoPreviewLayer(session = session)
            layer.videoGravity = AVLayerVideoGravityResizeAspectFill
            view.layer.addSublayer(layer)
            // The layer does not follow its host's bounds on its own, and the
            // host has none until it is laid out; `update` below resizes it.
            view.setClipsToBounds(true)
            view
        },
        update = { view ->
            (view.layer.sublayers?.firstOrNull() as? AVCaptureVideoPreviewLayer)?.setFrame(view.bounds)
        },
    )
}

/** `stopRunning` blocks, so it does not belong on the queue that draws. */
private fun AVCaptureSession.stopInBackground() {
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0uL)) {
        if (isRunning()) stopRunning()
    }
}

/**
 * Build the capture graph and start it, or answer false.
 *
 * Every step is a question iOS can answer no to — no camera, a camera another
 * process holds, a session that refuses the output — so each one is checked
 * rather than forced. `canAddInput`/`canAddOutput` before `addInput`/`addOutput`
 * is not defensive style: adding an output a session will not take raises an
 * Objective-C exception, which on Kotlin/Native is not catchable.
 *
 * `internal` rather than private so `QrScannerCaptureTest` can run it on the
 * simulator, which has no camera and therefore takes the very first `return
 * false` -- the only branch here that can be reached without hardware, and
 * enough to prove the AVFoundation binding links and does not trap.
 *
 * Callers must be off the main queue: `startRunning` blocks.
 */
internal fun AVCaptureSession.startCapturing(
    delegate: AVCaptureMetadataOutputObjectsDelegateProtocol,
): Boolean {
    val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo) ?: return false
    val input = AVCaptureDeviceInput.deviceInputWithDevice(device, null) ?: return false

    beginConfiguration()
    if (canSetSessionPreset(AVCaptureSessionPresetHigh)) {
        sessionPreset = AVCaptureSessionPresetHigh
    }
    if (!canAddInput(input)) {
        commitConfiguration()
        return false
    }
    addInput(input)

    val output = AVCaptureMetadataOutput()
    if (!canAddOutput(output)) {
        commitConfiguration()
        return false
    }
    addOutput(output)
    // The metadata types can only be set *after* the output is attached — before
    // that the session does not yet know which ones this camera supports.
    output.setMetadataObjectsDelegate(delegate, dispatch_get_main_queue())
    output.metadataObjectTypes = listOf(AVMetadataObjectTypeQRCode)
    commitConfiguration()

    startRunning()
    return true
}

/**
 * One decoded QR per callback, on the main queue.
 *
 * Like the Android analyzer this fires for every frame a code is in view; the
 * de-duplication is the view model's.
 */
private class QrMetadataDelegate(
    private val onCode: (String) -> Unit,
) : NSObject(), AVCaptureMetadataOutputObjectsDelegateProtocol {

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        didOutputMetadataObjects
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { it.stringValue }
            ?.let(onCode)
    }
}
