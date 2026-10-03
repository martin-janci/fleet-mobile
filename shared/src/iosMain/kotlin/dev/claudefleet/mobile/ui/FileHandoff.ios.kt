package dev.claudefleet.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIViewController
import platform.UIKit.popoverPresentationController

/**
 * iOS's half of the Files tab: the share sheet on the cached file's URL. It
 * is the one verb, because it already holds the others — *Save to Files*,
 * AirDrop, and every app that opens the type are rows in that sheet — so
 * offering separate Save / Open buttons would only open the same sheet three
 * ways.
 */
@Composable
internal actual fun rememberFileHandoff(): FileHandoff {
    val host = LocalUIViewController.current
    return remember(host) { IosFileHandoff(host) }
}

private class IosFileHandoff(private val host: UIViewController) : FileHandoff {
    override val cacheDir: String =
        (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).firstOrNull() as? String)
            ?: NSTemporaryDirectory()

    override val offered: List<Handoff> = listOf(Handoff.Share)

    override suspend fun perform(action: Handoff, path: String, name: String): String? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(path)) {
            throw HandoffFailed("The downloaded copy is gone from this phone. Tap the file again.")
        }
        // UIKit only from the main thread.
        withContext(Dispatchers.Main) {
            var top = host
            while (true) top = top.presentedViewController ?: break
            val url = NSURL.fileURLWithPath(path)
            val sheet = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
            // An iPad shows the sheet as a popover, which needs an anchor or
            // UIKit throws.
            sheet.popoverPresentationController?.sourceView = top.view
            top.presentViewController(sheet, animated = true, completion = null)
        }
        return null
    }
}
