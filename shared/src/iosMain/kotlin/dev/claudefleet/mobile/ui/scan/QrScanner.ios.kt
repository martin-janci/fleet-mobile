@file:OptIn(ExperimentalForeignApi::class)

package dev.claudefleet.mobile.ui.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
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
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIView
import platform.darwin.NSObject
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

    DisposableEffect(session) {
        onDispose { if (session.isRunning()) session.stopRunning() }
    }

    UIKitView(
        modifier = modifier,
        factory = {
            val view = UIView(frame = CGRectZero.readValue())
            val started = session.startCapturing(delegate)
            if (!started) {
                unavailable(CAMERA_UNAVAILABLE)
                return@UIKitView view
            }
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
        onRelease = {
            if (session.isRunning()) session.stopRunning()
        },
    )
}

/**
 * Build the capture graph and start it, or answer false.
 *
 * Every step is a question iOS can answer no to — no camera, a camera another
 * process holds, a session that refuses the output — so each one is checked
 * rather than forced. `canAddInput`/`canAddOutput` before `addInput`/`addOutput`
 * is not defensive style: adding an output a session will not take raises an
 * Objective-C exception, which on Kotlin/Native is not catchable.
 */
private fun AVCaptureSession.startCapturing(
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
