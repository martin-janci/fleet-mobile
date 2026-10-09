package dev.claudefleet.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.claudefleet.mobile.ui.theme.Fleet

/**
 * The phone's own fingerprint (or face) check, for This phone's lock
 * (redesign 14.11, MobileSettings "Lock with fingerprint"). The check is the
 * system's: the app never sees a fingerprint, only whether it passed.
 */
interface BiometricGate {
    /** The phone can ask: it has the hardware and something enrolled. */
    val available: Boolean

    /** Ask, saying [reason]; [onResult] is called once, on the main thread, with whether it passed. */
    fun ask(reason: String, onResult: (Boolean) -> Unit)
}

/** A platform with nothing to ask: the lock is not offered. */
object NoBiometricGate : BiometricGate {
    override val available: Boolean get() = false

    override fun ask(reason: String, onResult: (Boolean) -> Unit) = onResult(false)
}

/** Android's BiometricPrompt, iOS's LocalAuthentication, nothing on the desktop JVM. */
@Composable
internal expect fun rememberBiometricGate(): BiometricGate

/**
 * Whether the lock covers the app: it is on, not yet passed since the app
 * opened, and the phone can still ask. A phone whose fingerprints were all
 * removed is not locked out of its own app.
 */
internal fun lockShown(lockOn: Boolean, unlocked: Boolean, available: Boolean): Boolean =
    lockOn && available && !unlocked

/** Whether an answer to a session's question waits for the check first. */
internal fun answerAsksFirst(lockOn: Boolean, available: Boolean): Boolean = lockOn && available

/**
 * [action], once the check passes — or at once when the lock is off or the
 * phone cannot ask.
 */
internal fun BiometricGate.guard(lockOn: Boolean, reason: String, action: () -> Unit) {
    if (answerAsksFirst(lockOn, available)) ask(reason) { ok -> if (ok) action() } else action()
}

/**
 * What covers the app while it is locked: the check is asked at once, and
 * Unlock asks again after a cancel. Opaque, and it takes every touch, so
 * nothing behind it can be read or tapped.
 */
@Composable
internal fun PhoneLockScreen(gate: BiometricGate, onUnlocked: () -> Unit) {
    val o = Fleet.colors
    val unlock = { gate.ask("Unlock Orbit Fleet") { ok -> if (ok) onUnlocked() } }
    LaunchedEffect(gate) { unlock() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(o.bg)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Orbit Fleet is locked", color = o.fg, style = Fleet.type.textLg)
        Text("Use your fingerprint or face to open it.", color = o.fgMuted, style = Fleet.type.textSm)
        Button(onClick = unlock) { Text("Unlock") }
    }
}
