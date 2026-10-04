package dev.claudefleet.mobile.notify

import androidx.compose.runtime.Composable

/**
 * Ask the system for leave to post notifications, then call back with the
 * answer — asked when the person turns notifications on, and at no other
 * moment. Android 13+ asks; anything older, and a platform with nothing to
 * ask, answers yes at once.
 */
@Composable
expect fun rememberNotificationPermission(): (onResult: (Boolean) -> Unit) -> Unit
