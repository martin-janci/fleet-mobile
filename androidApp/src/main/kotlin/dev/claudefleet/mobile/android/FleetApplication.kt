package dev.claudefleet.mobile.android

import android.app.Application
import android.os.Build
import dev.claudefleet.mobile.AppContainer
import dev.claudefleet.mobile.net.ClientPlatform
import dev.claudefleet.mobile.store.AndroidPrefs
import dev.claudefleet.mobile.store.AndroidSecrets
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * The process, which owns the one [AppContainer] — as iOS's
 * `MainViewController.kt` already does, and for the same reason.
 *
 * It used to be the activity's. Every recreation the manifest's
 * `configChanges` does not cover (a font-size change, a locale change, the
 * system's own memory trims) then built a second container: a second
 * `AppSession` back on Splash, an empty draft store, the phone lock asking
 * again, and a new `HttpClient` the old activity never closed. Held here, a
 * new activity picks the same container back up.
 */
class FleetApplication : Application() {
    val notifier: AndroidBackgroundNotifier by lazy { AndroidBackgroundNotifier(this) }

    val container: AppContainer by lazy {
        AppContainer(
            secrets = AndroidSecrets(this),
            prefs = AndroidPrefs(getSharedPreferences("quick_replies", MODE_PRIVATE)),
            http = HttpClient(OkHttp),
            appVersion = BuildConfig.VERSION_NAME,
            // The build decides, not the link. A release build fills the Pair
            // screen's fields and waits for a tap; a debug build submits, so a
            // dev machine can be set up with no hands. See `data/PairLink.kt`.
            autoPairFromLink = BuildConfig.DEBUG,
            notifier = notifier,
            // The app's own update from GitHub releases (14.18). A debug build
            // has its own application id, so the signature check refuses a
            // release on it and says why.
            installer = AndroidAppInstaller(this),
            clientPlatform = ClientPlatform("android", androidArch(), BuildConfig.GIT_SHA),
        )
    }
}

/** The primary ABI in the hub's words (`aarch64`, `x86_64`, `armv7`, `x86`). */
internal fun androidArch(): String = when (val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()) {
    "arm64-v8a" -> "aarch64"
    "armeabi-v7a" -> "armv7"
    "x86_64" -> "x86_64"
    "x86" -> "x86"
    else -> abi.ifBlank { "unknown" }.replace(Regex("[^A-Za-z0-9_.+-]"), "-")
}
