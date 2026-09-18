package dev.claudefleet.mobile.ui.scan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier

/**
 * No scanner on the JVM.
 *
 * The JVM target exists so `commonTest` runs on the host without a device or an
 * emulator, not because anyone runs this app on a desktop. Answering false here
 * makes the Pair screen a plain form — which is exactly the shape it takes on a
 * phone whose camera permission was refused, so the path is not untrodden.
 */
actual fun qrScannerSupported(): Boolean = false

@Composable
actual fun QrScannerView(
    onScanned: (String) -> Unit,
    onUnavailable: (String) -> Unit,
    modifier: Modifier,
) {
    LaunchedEffect(Unit) { onUnavailable(CAMERA_UNAVAILABLE) }
}
