package dev.claudefleet.mobile.ui

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * The system's BiometricPrompt — the platform's own (API 28+), so no extra
 * library and no `FragmentActivity`. Older phones are not offered the lock.
 * Needs `USE_BIOMETRIC`, which the app's manifest declares.
 */
@Composable
internal actual fun rememberBiometricGate(): BiometricGate {
    val context = LocalContext.current
    return remember(context) { AndroidBiometricGate(context) }
}

private class AndroidBiometricGate(private val context: Context) : BiometricGate {
    override val available: Boolean = canAsk(context)

    override fun ask(reason: String, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) askP(reason, onResult) else onResult(false)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun askP(reason: String, onResult: (Boolean) -> Unit) {
        val executor = ContextCompat.getMainExecutor(context)
        // Called once: a cancel can arrive both as the button and as an error.
        var answered = false
        val answer = { ok: Boolean ->
            if (!answered) {
                answered = true
                onResult(ok)
            }
        }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle("Orbit Fleet")
            .setSubtitle(reason)
            .setNegativeButton("Cancel", executor) { _, _ -> answer(false) }
            .build()
        prompt.authenticate(
            CancellationSignal(),
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = answer(true)

                // Too many tries, a cancel, no fingerprint enrolled: not passed.
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = answer(false)
            },
        )
    }
}

/** The phone has a fingerprint (or face) reader with something enrolled. */
private fun canAsk(context: Context): Boolean = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
        context.getSystemService(BiometricManager::class.java)
            ?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> canAskQ(context)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
    else -> false
}

/** Android 10's one-argument check, replaced (not removed) in 11. */
@RequiresApi(Build.VERSION_CODES.Q)
@Suppress("DEPRECATION")
private fun canAskQ(context: Context): Boolean =
    context.getSystemService(BiometricManager::class.java)?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
