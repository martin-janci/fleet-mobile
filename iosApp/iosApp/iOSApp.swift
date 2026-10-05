import SwiftUI
import Shared

/// The iOS host, and deliberately almost nothing.
///
/// Every screen, every view model and all of the networking live in `:shared`
/// and are written in Kotlin; this target exists to give them a bundle, an
/// `Info.plist` and a launch, plus the app delegate iOS requires for background
/// checks and notification taps (`AppDelegate.swift`). If a change to the app is
/// being made here rather than in `shared/src/commonMain`, it is probably in the
/// wrong place.
@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        // A pending request must exist whenever the app is away, even if the
        // last check's own resubmission failed.
        .onChange(of: phase) { newPhase in
            if newPhase == .background {
                MainViewControllerKt.scheduleNeedsYouRefreshIfEnabled()
            }
        }
    }
}
