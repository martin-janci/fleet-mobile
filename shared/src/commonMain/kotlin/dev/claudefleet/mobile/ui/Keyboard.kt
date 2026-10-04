package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable

/**
 * Whether the on-screen keyboard is up — read, never applied: `App` pads for
 * the keyboard once, at the root, and `KeyboardInsetsTest` keeps it that way.
 *
 * Platform code because the question is: `WindowInsets.isImeVisible` exists
 * on Android and iOS but not on the desktop JVM this module also targets for
 * its tests, where there is no soft keyboard and the answer is always false.
 */
@Composable
internal expect fun keyboardVisible(): Boolean
