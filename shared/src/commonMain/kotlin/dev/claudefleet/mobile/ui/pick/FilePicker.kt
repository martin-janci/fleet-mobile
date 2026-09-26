package dev.claudefleet.mobile.ui.pick

import androidx.compose.runtime.Composable
import dev.claudefleet.mobile.model.PickResult
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
 * The bytes come back in memory. That is deliberate and bounded, and the
 * **per-file** bound — and only that one — is enforced **here, at the
 * picker**, not downstream: each actual asks the
 * platform how big a file is before opening it and skips anything over
 * [dev.claudefleet.mobile.model.ATTACH_MAX_BYTES] (10 MB) without reading a
 * byte of it — `OpenableColumns.SIZE` on Android, `NSURLFileSizeKey` on iOS.
 * Where a provider will not say, the read itself is capped and abandoned at
 * the ceiling. This matters because the picker accepts every MIME type: the
 * caller's own `checkBudget` can only inspect a size that exists *because the
 * bytes already do*, so without the check above it a 2 GB video would be one
 * tap from being read into memory on the main thread.
 *
 * [dev.claudefleet.mobile.model.ATTACH_MAX_TOTAL] (25 MB) is bounded here
 * too, but only **within one pick**: each actual spends a budget as it walks
 * the selection and reports the remainder as
 * [dev.claudefleet.mobile.model.SkipReason.OverTotal] instead of reading it.
 * That is what stops a multi-select of twenty 10 MB files from putting 200 MB
 * in the heap on the main thread — an OOM and an ANR on a mid-range phone —
 * before anything downstream is even asked. One pick now costs at most the
 * total plus whatever the file that crossed it had read: ~35 MB.
 *
 * The *running* total across picks is still the composer's, and has to be:
 * this function cannot see what an earlier pick already queued.
 * `SessionViewModel.attach` runs `checkBudget` over the queue plus the new
 * batch and refuses the batch whole.
 *
 * **A file skipped for size is named, not dropped silently.** It comes back
 * in [dev.claudefleet.mobile.model.PickResult.skipped] with whatever size the
 * platform declared, and the composer turns it into the sentence — *"screen
 * recording.mov is 240 MB — the limit is 10 MB"* — because the composer is
 * what has a screen to say it on. That is why the callback carries a
 * [dev.claudefleet.mobile.model.PickResult] rather than a bare list: a list
 * can only be short, and a short list is indistinguishable from a cancelled
 * pick.
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
 * **A wholly empty [dev.claudefleet.mobile.model.PickResult] — no files and
 * nothing skipped — is the cancellation**, and the only thing that is. A pick
 * where nothing survived comes back with an empty
 * [dev.claudefleet.mobile.model.PickResult.files] and one
 * [dev.claudefleet.mobile.model.SkippedFile] per casualty, so the composer can
 * say what happened instead of leaving the person to wonder whether their tap
 * registered.
 *
 * Multi-select on both platforms. The bytes are read before [onPicked] is
 * called, so what arrives is the file, not a handle to it that may have
 * expired by Send.
 */
@Composable
expect fun rememberFilePicker(onPicked: (PickResult) -> Unit): () -> Unit
