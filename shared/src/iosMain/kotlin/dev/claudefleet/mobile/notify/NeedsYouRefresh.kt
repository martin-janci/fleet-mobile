@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package dev.claudefleet.mobile.notify

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * The background check's identifier. Spelled the same in `AppDelegate.swift`
 * (which registers it) and in `Info.plist`'s `BGTaskSchedulerPermittedIdentifiers`
 * (without which registering it crashes); `IosBackgroundTaskTest` holds all three equal.
 */
const val NEEDS_YOU_TASK = "dev.claudefleet.mobile.needs-you"

/** iOS treats it as "not before"; when it actually runs is the system's call. */
private const val EARLIEST_SECONDS = 15.0 * 60

/**
 * Ask for the next background check. Null when the request was accepted, else
 * the scheduler's reason, which on a simulator is always "unavailable".
 * Submitting replaces any pending request with the same identifier.
 */
fun submitNeedsYouRefresh(): String? = memScoped {
    val request = BGAppRefreshTaskRequest(identifier = NEEDS_YOU_TASK)
    request.earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(EARLIEST_SECONDS)
    val error = alloc<ObjCObjectVar<NSError?>>()
    if (BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)) null
    else error.value?.localizedDescription ?: "the scheduler refused without saying why"
}

fun cancelNeedsYouRefresh() {
    BGTaskScheduler.sharedScheduler.cancelTaskRequestWithIdentifier(NEEDS_YOU_TASK)
}
