package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable

/** The JVM target exists for the tests; it has no recogniser, so no mic. */
@Composable
internal actual fun rememberDictation(onText: (String) -> Unit): (() -> Unit)? = null
