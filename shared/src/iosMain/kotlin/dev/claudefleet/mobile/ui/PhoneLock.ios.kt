@file:OptIn(ExperimentalForeignApi::class)

package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.cinterop.ExperimentalForeignApi
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthenticationWithBiometrics
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * LocalAuthentication: Touch ID or Face ID, whichever the phone has. Face ID
 * needs `NSFaceIDUsageDescription` in `Info.plist`, which the app declares.
 * A fresh `LAContext` per ask, so one pass is never reused for the next ask.
 * The answer arrives on a private queue and goes back to the main one.
 */
@Composable
internal actual fun rememberBiometricGate(): BiometricGate = remember { IosBiometricGate() }

private class IosBiometricGate : BiometricGate {
    override val available: Boolean =
        LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, error = null)

    override fun ask(reason: String, onResult: (Boolean) -> Unit) {
        LAContext().evaluatePolicy(LAPolicyDeviceOwnerAuthenticationWithBiometrics, localizedReason = reason) { ok, _ ->
            dispatch_async(dispatch_get_main_queue()) { onResult(ok) }
        }
    }
}
