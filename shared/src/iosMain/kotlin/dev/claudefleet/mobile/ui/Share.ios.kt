package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import platform.UIKit.UIActivityViewController
import platform.UIKit.popoverPresentationController

@Composable
internal actual fun rememberShareText(): (String) -> Unit {
    val host = LocalUIViewController.current
    return remember(host) {
        { text ->
            // Present from whatever is on top: the Today sheet may itself be
            // presented over the Compose host.
            var top = host
            while (true) top = top.presentedViewController ?: break
            val sheet = UIActivityViewController(activityItems = listOf(text), applicationActivities = null)
            // An iPad shows the sheet as a popover, which needs an anchor or
            // UIKit throws.
            sheet.popoverPresentationController?.sourceView = top.view
            top.presentViewController(sheet, animated = true, completion = null)
        }
    }
}
