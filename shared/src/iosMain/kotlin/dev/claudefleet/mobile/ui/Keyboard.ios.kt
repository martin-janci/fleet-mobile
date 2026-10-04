package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIKeyboardWillHideNotification
import platform.UIKit.UIKeyboardWillShowNotification

/**
 * UIKit's own keyboard notifications: Compose on iOS has no `isImeVisible`.
 * Starts false — a screen is entered with the keyboard down, and the first
 * focus raises it, which is a notification this hears.
 */
@Composable
internal actual fun keyboardVisible(): Boolean {
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val queue = NSOperationQueue.mainQueue
        val shown = center.addObserverForName(UIKeyboardWillShowNotification, null, queue) { _ -> visible = true }
        val hidden = center.addObserverForName(UIKeyboardWillHideNotification, null, queue) { _ -> visible = false }
        onDispose {
            center.removeObserver(shown)
            center.removeObserver(hidden)
        }
    }
    return visible
}
