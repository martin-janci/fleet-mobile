package dev.claudefleet.mobile.notify

import androidx.compose.runtime.Composable

/** Nothing to ask here: no background notifications on this platform. */
@Composable
actual fun rememberNotificationPermission(): (onResult: (Boolean) -> Unit) -> Unit = { it(true) }
