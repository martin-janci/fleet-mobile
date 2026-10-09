package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable

/** The JVM target exists for the tests; it has no fingerprint to ask for, so the lock is not offered. */
@Composable
internal actual fun rememberBiometricGate(): BiometricGate = NoBiometricGate
