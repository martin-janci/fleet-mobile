package dev.claudefleet.mobile.notify

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS asks once; after a refusal it answers no without asking, and only the
 * Settings app can change that — which is what the note under the switch says.
 * The answer arrives on an arbitrary queue and goes back to the main one,
 * where Compose state may be written.
 */
@Composable
actual fun rememberNotificationPermission(): (onResult: (Boolean) -> Unit) -> Unit =
    remember<(onResult: (Boolean) -> Unit) -> Unit> {
        { onResult ->
            UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
                UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
            ) { granted, _ -> dispatch_async(dispatch_get_main_queue()) { onResult(granted) } }
        }
    }
