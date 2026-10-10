package dev.claudefleet.mobile

import androidx.compose.ui.uikit.OnFocusBehavior
import androidx.compose.ui.window.ComposeUIViewController
import dev.claudefleet.mobile.notify.IosAlertPoster
import dev.claudefleet.mobile.notify.IosBackgroundNotifier
import dev.claudefleet.mobile.notify.NeedsYouCheck
import dev.claudefleet.mobile.notify.submitNeedsYouRefresh
import dev.claudefleet.mobile.net.ClientPlatform
import dev.claudefleet.mobile.store.IosPrefs
import dev.claudefleet.mobile.store.KeychainSecrets
import dev.claudefleet.mobile.ui.iosSystemBars
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * **Built for a device, not yet run on one.** The iOS app gets its first device run through
 * `README.md` → *What a Mac still has to check*; until that list says otherwise, treat
 * everything here as linked, not proven.
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
 * Whether a full-screen layout wants the status bar out of the way (redesign
 * 14.21). `ContentView.swift` passes a listener once and applies each answer
 * with `statusBar(hidden:)`; it hears the current one at once. Called on the
 * main thread.
 */
fun onSystemBarsHidden(listener: (Boolean) -> Unit) {
    iosSystemBars.listen(listener)
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
        prefs = IosPrefs(),
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
        notifier = iosNotifier,
        // `X-Fleet-Client` and `/update/check`: iOS has no side-load, so the
        // hub's decision is only shown (its artifact is `notify`).
        clientPlatform = ClientPlatform("ios", "aarch64"),
    )
}

/** The one notifier, shared by the container (Settings, the open app) and the background check. */
private val iosNotifier: IosBackgroundNotifier by lazy { IosBackgroundNotifier(IosAlertPoster()) }

/**
 * A tapped "needs you" notification, from `AppDelegate`'s notification
 * delegate. Held by the container until the paired screens take it — a tap can
 * start the app cold.
 */
fun onOpenSession(sessionId: Long) {
    iosContainer.onOpenSession(sessionId)
}

/** A failure notification's Retry (MobileControl): open the session; the app retries it there. */
fun onRetrySession(sessionId: Long) {
    iosContainer.onRetrySession(sessionId)
}

/** A mission's notification, its tap or Review grant: open that mission, its grant card first. */
fun onOpenMission(missionId: Long) {
    iosContainer.onOpenMission(missionId)
}

/** A running background check; `AppDelegate` cancels it when iOS's time is up. */
class NeedsYouRun internal constructor(private val job: Job) {
    fun cancel() {
        job.cancel()
    }
}

/**
 * Start one background check for `AppDelegate`'s `BGAppRefreshTask` handler.
 * [onDone] is called exactly once — true when the check finished, false when
 * it was cancelled — on an arbitrary thread; `setTaskCompleted` is safe from
 * any.
 *
 * A handle rather than a coroutine entry point on purpose: an exported one
 * must start on the main thread, and cancelling the Swift `Task` awaiting it
 * does not cancel the coroutine behind it.
 *
 * Only cancellation may end the job abnormally: on Kotlin/Native an unhandled
 * exception in a launched coroutine terminates the process, and the check
 * guards only its fetch — the `Prefs` and [AlertPoster] calls after it are not.
 */
fun startNeedsYouCheck(onDone: (Boolean) -> Unit): NeedsYouRun {
    val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        if (iosNotifier.enabled.value) {
            try {
                NeedsYouCheck(iosContainer.session, iosContainer.prefs, iosNotifier.alertPoster).once()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("needs-you: check failed: ${e.message}")
            }
        }
    }
    job.invokeOnCompletion { cause -> onDone(cause == null) }
    return NeedsYouRun(job)
}

/** Request the next check if alerts are on — from the task handler, and whenever the app goes to the background. */
fun scheduleNeedsYouRefreshIfEnabled() {
    if (iosNotifier.enabled.value) submitNeedsYouRefresh()?.let { println("needs-you: refresh not scheduled: $it") }
}

/**
 * The version the Settings screen shows, read from the bundle so it is whatever
 * the Xcode project was built with rather than a number checked into Kotlin that
 * someone would have to remember to bump.
 */
private fun iosAppVersion(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String
        ?: "unknown"
