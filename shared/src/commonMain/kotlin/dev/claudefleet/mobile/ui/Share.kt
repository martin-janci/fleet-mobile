package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable

/**
 * Hand plain text to the platform's share sheet — Android's chooser, iOS's
 * `UIActivityViewController` — for the Today sheet's **Share**. What leaves
 * the phone is exactly the string given; nothing here reads or rewrites it.
 */
@Composable
internal expect fun rememberShareText(): (String) -> Unit
