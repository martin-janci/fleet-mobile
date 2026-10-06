package dev.claudefleet.mobile.notify

import platform.Foundation.NSNumber
import platform.Foundation.numberWithLongLong
import platform.UserNotifications.UNMutableNotificationContent
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
        val content = UNMutableNotificationContent().apply {
            setTitle(c.title)
            setBody(c.body)
            setThreadIdentifier(c.thread)
            setUserInfo(mapOf<Any?, Any?>(NEEDS_YOU_SESSION_KEY to NSNumber.numberWithLongLong(c.sessionId)))
            setSound(UNNotificationSound.defaultSound)
        }
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(c.id, content, null), withCompletionHandler = null)
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
