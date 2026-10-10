package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable

/** No mic in the composer on iOS yet: the keyboard's own dictation key does it (see the expect). */
@Composable
internal actual fun rememberDictation(onText: (String) -> Unit): (() -> Unit)? = null
