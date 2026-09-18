import SwiftUI
import UIKit
import Shared

/// The shared Compose UI, wrapped so SwiftUI can place it.
///
/// **`ComposeUIViewController` is not one way of doing this — it is the only
/// one.** On iOS, Compose Multiplatform installs `LocalLifecycleOwner`, the
/// window insets and the frame clock from inside the controller that
/// `MainViewController()` returns. The shared `App` uses `LifecycleStartEffect`
/// to subscribe to the hub's event stream on resume and drop it on background,
/// and `WindowInsets.safeDrawing` to clear the notch and the home indicator, so
/// a host that reached the composables by any other route would not merely look
/// wrong: there would be no lifecycle owner to resolve, and the app would fail
/// where it tried.
///
/// `updateUIViewController` is empty on purpose. Compose keeps its own state
/// inside the controller and observes the shared flows itself; there is no
/// SwiftUI state above it to push down.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            // The Compose view takes the whole window and insets itself, which
            // is the same arrangement as `enableEdgeToEdge()` on Android: the
            // shared `App` applies `WindowInsets.safeDrawing` once, at the top,
            // and that is the only place either platform pads for the system
            // bars. Letting SwiftUI inset the container instead would pad
            // twice and leave the app's own background not reaching the edges.
            //
            // `.all` includes `.keyboard`, which is also deliberate:
            // `safeDrawing` contains the IME inset, so Compose raises the
            // prompt box itself. If SwiftUI moved the whole view up as well,
            // the box would travel twice as far as the keyboard.
            .ignoresSafeArea(.all)
    }
}
