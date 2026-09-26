package dev.claudefleet.mobile.ui.pick

import androidx.compose.runtime.Composable
import dev.claudefleet.mobile.model.PickedFile

/**
 * Picking a file to attach: the app's fourth `expect`/`actual` family, after
 * `Platform` (`platformName`, `epochSeconds`, `utcOffsetSeconds`), `QrScanner`
 * and `Share` (`rememberShareText`). Secure storage is deliberately *not* one —
 * `Secrets` is an interface each platform's host constructs and hands in.
 *
 * It is the *whole* picker rather than a thin file-system wrapper, for the
 * same reason `QrScanner` is: Android's `OpenMultipleDocuments` and iOS's
 * `UIDocumentPickerViewController` have nothing in common but the result.
 *
 * The bytes come back in memory. That is deliberate and bounded, and the bound
 * is enforced **here, at the picker**, not downstream: each actual asks the
 * platform how big a file is before opening it and skips anything over
 * [dev.claudefleet.mobile.model.ATTACH_MAX_BYTES] (10 MB) without reading a
 * byte of it — `OpenableColumns.SIZE` on Android, `NSURLFileSizeKey` on iOS.
 * Where a provider will not say, the read itself is capped and abandoned at
 * the ceiling. This matters because the picker accepts every MIME type: the
 * caller's own `checkBudget` can only inspect a size that exists *because the
 * bytes already do*, so without the check above it a 2 GB video would be one
 * tap from being read into memory on the main thread.
 *
 * **A file skipped for size is dropped silently.** It is simply absent from
 * the list, and this signature has no way to say which file went or why. That
 * is a known gap rather than an accepted one — telling the person *"screen
 * recording.mov is 240 MB — the limit is 10 MB"* is the composer's job, and it
 * is the composer that has the screen to say it on. Until it does, a person
 * who picks one large file sees the same thing as a person who cancelled.
 *
 * **On iOS this sees Files, not Photos.** The two are separate stores on an
 * iPhone, and `UIDocumentPickerViewController` browses only the first. A
 * screenshot — the single most likely thing anybody wants to attach from a
 * phone — lands in Photos, so it is not reachable here until someone saves it
 * to Files first. Android does not have the problem: its system picker lists
 * Photos among its providers, so a screenshot is one tap away.
 *
 * That asymmetry is a known, deliberate choice rather than an oversight. It is
 * survivable because it is purely a missing *source*: adding
 * `PHPickerViewController` later is additive — a second iOS entry point
 * producing the same [PickedFile] list through the same [rememberFilePicker]
 * contract, with nothing else in the design moving.
 */
expect fun filePickerSupported(): Boolean

/**
 * Remember a launcher. Calling the returned function opens the system picker;
 * [onPicked] fires with what came back, and with an empty list if the person
 * cancelled — so a caller can always stop showing a spinner. Exactly one call
 * per launch.
 *
 * **An empty list means "cancelled" *or* "nothing survived"** — every file
 * over the ceiling, every read the platform refused. The two are not
 * distinguishable here, on purpose: separating them means a richer result
 * type, and the contract this signature makes is the one the composer is
 * already written against. A caller must treat an empty list as "carry on with
 * no attachment", never as "the person definitely tapped Cancel".
 *
 * Multi-select on both platforms. The bytes are read before [onPicked] is
 * called, so what arrives is the file, not a handle to it that may have
 * expired by Send.
 */
@Composable
expect fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit
