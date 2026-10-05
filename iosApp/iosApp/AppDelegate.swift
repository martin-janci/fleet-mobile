import BackgroundTasks
import Shared
import UIKit
import UserNotifications

/// The two things iOS will only take from an app delegate, and nothing else.
///
/// 1. Background-task registration, which must happen before launch finishes.
///    The check it runs is shared Kotlin (`NeedsYouCheck`); this file only
///    hands iOS's thirty seconds to it and takes them back when they run out.
/// 2. The notification delegate: no banner while the app is on screen (the
///    list already says it), and a tap opens the session it was about.
final class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Same identifier as NEEDS_YOU_TASK in NeedsYouRefresh.kt and
        // BGTaskSchedulerPermittedIdentifiers in Info.plist; IosBackgroundTaskTest
        // holds all three equal. A mismatch with the plist crashes here.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: "dev.claudefleet.mobile.needs-you", using: nil) { task in
            AppDelegate.run(task)
        }
        UNUserNotificationCenter.current().delegate = self
        return true
    }

    private static func run(_ task: BGTask) {
        // Ask for the next one first, so a check that runs out of time does
        // not end the chain.
        MainViewControllerKt.scheduleNeedsYouRefreshIfEnabled()
        let run = MainViewControllerKt.startNeedsYouCheck { finished in
            task.setTaskCompleted(success: finished.boolValue)
        }
        task.expirationHandler = { run.cancel() }
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if let id = response.notification.request.content.userInfo["sessionId"] as? NSNumber {
            MainViewControllerKt.onOpenSession(sessionId: id.int64Value)
        }
        completionHandler()
    }
}
