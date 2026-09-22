package dev.claudefleet.mobile

import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import dev.claudefleet.mobile.store.KeychainSecrets
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlin.native.Platform
import platform.Foundation.NSBundle
import platform.UIKit.UIViewController

/**
 * The iOS entry point: the whole shared app inside one `UIViewController`.
 *
 * **`ComposeUIViewController` is not one way of hosting this — it is the only
 * one.** On iOS, Compose Multiplatform installs `LocalLifecycleOwner`, the
 * window insets and the frame clock from inside this controller and nowhere
 * else. `App` uses `LifecycleStartEffect` to start and stop the event stream and
 * `WindowInsets.safeDrawing` to clear the notch and the home indicator, so a
 * host that reached the composables by any other route would not render badly —
 * it would throw on the missing lifecycle owner and never draw at all. The Swift
 * side must therefore wrap *this function's return value* in a
 * `UIViewControllerRepresentable`, which is what `iosApp/iosApp/ContentView.swift`
 * does.
 *
 * The two things the shared code cannot build for itself are constructed here,
 * exactly as `MainActivity` does on Android: the secure store (the Keychain, no
 * context needed) and the Ktor engine (Darwin).
 *
 * **Linked, never run.** This file is compiled into `Shared.framework` and
 * linked into `iosApp` by both a local `xcodebuild … build` and the `macos`
 * job in `.github/workflows/ci.yml` — neither one launches the result, and
 * there is deliberately no simulator runtime in CI, only the SDK. See
 * `README.md` → *What a Mac still has to check* for everything that still
 * needs a real run.
 */
fun MainViewController(): UIViewController = ComposeUIViewController(
    configure = {
        // Compose raises the prompt box; nothing else may.
        //
        // The default here is `OnFocusBehavior.FocusableAboveKeyboard`, which
        // translates the WHOLE Compose scene upwards so a focused text field
        // clears the keyboard. `App` already pads for the keyboard — its root
        // `WindowInsets.safeDrawing` contains the IME inset — so the default
        // made the prompt box travel roughly twice the keyboard's height, and
        // took the session's top bar off the top of the screen with it.
        //
        // `ContentView.swift` documents this hazard and turns off *SwiftUI's*
        // keyboard avoidance with `.ignoresSafeArea(.all)`. It cannot reach
        // this one: Compose Multiplatform's own avoidance is configured here,
        // in Kotlin, and is invisible from Swift. Android's half of the same
        // property is `android:windowSoftInputMode="adjustResize"` in the
        // manifest; `KeyboardInsetsTest` gates both.
        onFocusBehavior = OnFocusBehavior.DoNothing
    },
) {
    App(iosContainer)
}

/**
 * A `claudefleet:` URL from SwiftUI's `onOpenURL`.
 *
 * Top-level so Swift reaches it as `MainViewControllerKt.onPairLink(uri:)`,
 * the same shape `ContentView` already uses for the controller itself. It goes
 * to the one container rather than to a screen, because the URL can arrive
 * before any screen exists — a cold launch from `simctl openurl` delivers it
 * while Compose is still starting — and [AppContainer] holds it until the Pair
 * screen asks.
 */
fun onPairLink(uri: String) {
    iosContainer.onPairLink(uri)
}

/**
 * Built once for the process, not once per controller.
 *
 * SwiftUI may make and discard a `UIViewControllerRepresentable`'s controller
 * more than once, and each rebuild would otherwise open a second Keychain
 * handle, a second Ktor engine and — because `AppContainer` owns `AppSession` —
 * a second copy of the paired state, which would then disagree with the first.
 */
@OptIn(kotlin.experimental.ExperimentalNativeApi::class)
private val iosContainer: AppContainer by lazy {
    AppContainer(
        secrets = KeychainSecrets(),
        http = HttpClient(Darwin),
        appVersion = iosAppVersion(),
        // The build decides, not the link. `Platform.isDebugBinary` is
        // Kotlin/Native's own answer to `BuildConfig.DEBUG`, so this needs no
        // flag passed down from Swift — and it is opted into on this one
        // property rather than repo-wide, the same rule `App.kt` follows for
        // `BackHandler`: an opt-in is a promise to re-read the call when the
        // API changes, and a module-wide flag is a promise nobody is reminded
        // of. A release build fills the Pair screen's fields and waits.
        autoPairFromLink = Platform.isDebugBinary,
    )
}

/**
 * The version the Settings screen shows, read from the bundle so it is whatever
 * the Xcode project was built with rather than a number checked into Kotlin that
 * someone would have to remember to bump.
 */
private fun iosAppVersion(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
        ?: "unknown"
