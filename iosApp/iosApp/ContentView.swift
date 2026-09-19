import SwiftUI
import UIKit
import Shared

/// The shared Compose UI, wrapped so SwiftUI can place it.
///
/// Wrapping `MainViewController()`'s return value is not optional — see that
/// function's doc comment in `MainViewController.kt` for why any other route
/// would fail outright rather than merely look wrong.
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
