package dev.claudefleet.mobile.ui.pick

import androidx.compose.runtime.Composable
import dev.claudefleet.mobile.model.PickedFile

/**
 * No picker off a device.
 *
 * The JVM target exists so `commonTest` runs on the host without a device or
 * an emulator, not because anyone runs this app on a desktop. Answering false
 * makes the attach control absent rather than broken — the same shape the
 * screen takes wherever a platform has no picker, so the path is not
 * untrodden. `QrScanner.jvm.kt` is the precedent.
 */
actual fun filePickerSupported(): Boolean = false

/**
 * Still calls back, with nothing.
 *
 * A launcher that answered by doing nothing would leave a caller's spinner
 * spinning, and that is exactly the promise the shared contract makes about a
 * cancelled pick. The stub keeps it.
 */
@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit = { onPicked(emptyList()) }
