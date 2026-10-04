package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable

/** No soft keyboard on the desktop JVM, which only runs the tests. */
@Composable
internal actual fun keyboardVisible(): Boolean = false
