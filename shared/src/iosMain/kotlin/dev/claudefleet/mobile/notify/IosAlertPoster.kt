package dev.claudefleet.mobile.notify

import platform.Foundation.NSNumber
import platform.Foundation.numberWithLongLong
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationActionOptionNone
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/**
 * "Needs you" notifications through `UNUserNotificationCenter`: one per
 * session (its identifier replaces an older one), all in one thread so iOS
 * groups them, each carrying its session for the tap.
 *
 * The center is looked up per call, never at construction: a process with no
 * app bundle (a bare Kotlin/Native test binary) has no center, and asking for
 * one there is a crash.
 */
class IosAlertPoster : AlertPoster {
    private val center: UNUserNotificationCenter get() = UNUserNotificationCenter.currentNotificationCenter()

    override fun post(alert: NeedsYouAlert) {
        val c = needsYouContent(alert)
        registerCategories()
        val content = UNMutableNotificationContent().apply {
            setTitle(c.title)
            setBody(c.body)
            setThreadIdentifier(c.thread)
            setCategoryIdentifier(c.category)
            setUserInfo(mapOf<Any?, Any?>(NEEDS_YOU_SESSION_KEY to NSNumber.numberWithLongLong(c.sessionId)))
            // An update (the question arriving after the status) replaces the
            // notification without a second sound.
            if (!alert.quiet) setSound(UNNotificationSound.defaultSound)
        }
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(c.id, content, null), withCompletionHandler = null)
    }

    /**
     * The category's buttons (redesign 14.8): Answer (or Open) brings the app
     * up at the question; Later only puts the notification away. Neither runs
     * without the app, and neither answers — iOS has no Approve here.
     * Every category at once, since a registration replaces the last.
     */
    private fun registerCategories() {
        val categories = needsYouCategories().map { (id, buttons) ->
            val actions = buttons.map { a ->
                UNNotificationAction.actionWithIdentifier(
                    a.id,
                    a.label,
                    if (a.kind == NotifyActionKind.Open) UNNotificationActionOptionForeground else UNNotificationActionOptionNone,
                )
            }
            UNNotificationCategory.categoryWithIdentifier(id, actions, emptyList<String>(), UNNotificationCategoryOptionNone)
        }
        center.setNotificationCategories(categories.toSet())
    }

    /** A routine run failed: one per run, in the same thread; a tap opens the app. */
    override fun postRoutine(alert: RoutineFailedAlert) {
        val content = UNMutableNotificationContent().apply {
            setTitle(alert.title)
            setBody(alert.text)
            setThreadIdentifier(NEEDS_YOU_THREAD)
            setSound(UNNotificationSound.defaultSound)
        }
        center.addNotificationRequest(
            UNNotificationRequest.requestWithIdentifier("${NEEDS_YOU_ID_PREFIX}routine-${alert.runId}", content, null),
            withCompletionHandler = null,
        )
    }

    override fun withdraw(sessionId: Long) {
        center.removeDeliveredNotificationsWithIdentifiers(listOf(needsYouId(sessionId)))
    }

    /** Every delivered "needs you" notification — when the person turns alerts off. */
    fun withdrawAll() {
        center.getDeliveredNotificationsWithCompletionHandler { delivered ->
            val ids = delivered.orEmpty()
                .mapNotNull { (it as? UNNotification)?.request?.identifier }
                .filter { it.startsWith(NEEDS_YOU_ID_PREFIX) }
            if (ids.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(ids)
        }
    }
}
