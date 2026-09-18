import SwiftUI

/// The iOS host, and deliberately almost nothing.
///
/// Every screen, every view model and all of the networking live in `:shared`
/// and are written in Kotlin; this target exists to give them a bundle, an
/// `Info.plist` and a launch. If a change to the app is being made here rather
/// than in `shared/src/commonMain`, it is probably in the wrong place.
@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
