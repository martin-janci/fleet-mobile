@file:OptIn(ExperimentalForeignApi::class)

package dev.claudefleet.mobile.ui.pick

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.uikit.LocalUIViewController
import dev.claudefleet.mobile.model.ATTACH_MAX_BYTES
import dev.claudefleet.mobile.model.PickedFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSDataReadingMappedIfSafe
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLFileSizeKey
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIAdaptivePresentationControllerDelegateProtocol
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIPresentationController
import platform.UIKit.presentationController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.posix.memcpy

actual fun filePickerSupported(): Boolean = true

/**
 * Every delegate with a picker currently on screen.
 *
 * `UIDocumentPickerViewController.delegate` is **weak**, so something must hold
 * a strong reference for as long as the sheet is up. `remember` alone is not
 * that something: it is strong only while the composable is in the composition,
 * and the composition can go while the sheet is still presented — a hub event
 * arriving over the socket can drive navigation underneath a modal, the slot is
 * discarded, Kotlin/Native nils the weak `delegate`, and the picker then closes
 * reporting to nobody while the caller's spinner spins forever.
 *
 * So the strong reference is here instead, keyed on nothing and held for
 * exactly the presentation: added when the picker is launched and removed the
 * moment the delegate reports. Every mutation happens on the main queue — the
 * launch lambda runs there and so do both UIKit callbacks — so the plain
 * `mutableSetOf` needs no synchronisation.
 */
private val presenting = mutableSetOf<FilePickerDelegate>()

/**
 * `UIDocumentPickerViewController` in open mode, presented over whatever is on
 * top of the Compose host.
 *
 * **Unrun**, like every other line of `iosMain` here: this compiles for
 * `iosArm64` and `iosSimulatorArm64` and has never executed, because linking
 * the framework and running it need macOS.
 */
@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit {
    val host = LocalUIViewController.current
    // The lambda the delegate calls is read through a state, so a
    // recomposition with a new [onPicked] is seen by the delegate that is
    // already sitting behind a presented picker. Mutating a field on the
    // delegate during composition would do the same job by writing to it,
    // which is a side effect in a place that must not have one.
    val picked by rememberUpdatedState(onPicked)

    // `remember` keeps one delegate for the life of the composable, so a second
    // pick reuses the first one's wiring. It is *not* what keeps the delegate
    // alive behind a presented sheet — see [presenting].
    val delegate = remember { FilePickerDelegate { files -> picked(files) } }

    return remember(host, delegate) {
        {
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeItem),
                // The file is copied into this app's own temp directory, so
                // what comes back is already ours to read: no
                // `startAccessingSecurityScopedResource` dance, and no URL
                // that goes stale the moment the picker closes.
                asCopy = true,
            )
            picker.delegate = delegate
            picker.allowsMultipleSelection = true
            // Swipe-to-dismiss. Whether UIKit synthesises
            // `documentPickerWasCancelled` for an interactive dismissal of the
            // sheet is version-dependent and has never been checked on a
            // device from here, so the adaptive-presentation delegate is wired
            // up as well and reports the same cancellation. Two routes to one
            // report, and [FilePickerDelegate.report] makes sure only the
            // first of them is delivered.
            picker.presentationController?.delegate = delegate
            delegate.arm()
            // Present from whatever is on top: the attach control lives in a
            // sheet that may itself be presented over the Compose host.
            var top = host
            while (true) top = top.presentedViewController ?: break
            top.presentViewController(picker, animated = true, completion = null)
        }
    }
}

/**
 * Both halves of the contract: what was picked, and the fact that nothing was.
 *
 * A named class rather than an anonymous object for the same reason
 * `QrMetadataDelegate` is one — an `object :` expression inside a `remember`
 * captures the enclosing composable's scope, and this needs to capture exactly
 * one thing.
 */
private class FilePickerDelegate(
    private val onResult: (List<PickedFile>) -> Unit,
) : NSObject(), UIDocumentPickerDelegateProtocol, UIAdaptivePresentationControllerDelegateProtocol {

    /**
     * False only between [arm] and the one report that follows it.
     *
     * Three things can end a presentation — a pick, a tap on Cancel, and a
     * swipe down — and on some iOS versions two of them fire. The caller is
     * promised exactly one callback per launch, so the second is dropped. It
     * is reset by [arm] rather than left latched, or the *second* pick of the
     * composable's life would be the one that went silent.
     */
    private var reported = true

    /** Called as the picker is presented: hold this delegate, and expect a report. */
    fun arm() {
        reported = false
        presenting += this
    }

    private fun report(files: List<PickedFile>) {
        if (reported) return
        reported = true
        presenting -= this
        onResult(files)
    }

    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        report(didPickDocumentsAtURLs.filterIsInstance<NSURL>().mapNotNull(::read))
    }

    /** Cancelling must still report, so the caller can stop waiting. */
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        report(emptyList())
    }

    /** The sheet was swiped away rather than cancelled. Same answer. */
    override fun presentationControllerDidDismiss(presentationController: UIPresentationController) {
        report(emptyList())
    }
}

/**
 * The bytes of one picked URL, or null if it is too big or Foundation would
 * not read it.
 *
 * The size is asked for *before* anything is read, so a file over the ceiling
 * costs nothing but a stat. Where the file system will not say — a provider
 * that does not publish `NSURLFileSizeKey` — the fallback is a **memory-mapped**
 * read: `NSDataReadingMappedIfSafe` gives an `NSData` backed by the file rather
 * than by a full copy in the heap, so `length` can be checked against the
 * ceiling before a single byte is copied into Kotlin.
 *
 * `asCopy = true` put the file in this app's temp directory, so it is already
 * ours and needs no security-scoped access around it — and it is ours to
 * delete, which the `finally` does: the bytes live in the [PickedFile] from
 * here on, and leaving the copy behind would mean every pick grows `tmp/` until
 * iOS decides to purge it.
 */
private fun read(url: NSURL): PickedFile? {
    try {
        val declared = url.fileSize()
        if (declared != null && declared > ATTACH_MAX_BYTES) return null

        val data: NSData = NSData.dataWithContentsOfURL(url, NSDataReadingMappedIfSafe, null) ?: return null
        val length = data.length
        if (length.toLong() > ATTACH_MAX_BYTES) return null

        // One source for the size: `length.convert()` rather than a separate
        // `toInt()`, so the array and the `memcpy` cannot disagree.
        val bytes = ByteArray(length.convert())
        // `addressOf(0)` on an empty array throws, and an empty file is a
        // thing that exists.
        if (length > 0uL) {
            bytes.usePinned { memcpy(it.addressOf(0), data.bytes, length) }
        }
        return PickedFile(
            name = url.lastPathComponent ?: "file",
            size = bytes.size.toLong(),
            bytes = bytes,
        )
    } finally {
        NSFileManager.defaultManager.removeItemAtURL(url, null)
    }
}

/** What the file system says this is, or null if it will not say. */
private fun NSURL.fileSize(): Long? =
    (resourceValuesForKeys(listOf(NSURLFileSizeKey), null)?.get(NSURLFileSizeKey) as? NSNumber)
        ?.longLongValue
