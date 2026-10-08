package dev.claudefleet.mobile.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.claudefleet.mobile.App
import dev.claudefleet.mobile.AppContainer
import dev.claudefleet.mobile.store.AndroidPrefs
import dev.claudefleet.mobile.store.AndroidSecrets
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

/**
 * Thin Android host: all UI lives in `:shared`.
 *
 * The two things the shared code cannot build for itself are constructed here —
 * the secure store, which needs a `Context`, and the Ktor engine, which is
 * per-platform.
 *
 * It asks for nothing. The camera permission belongs to the scanner and is
 * requested when the scanner opens; an activity that asked for it here would be
 * asking every time the app started, including for the people who never scan
 * anything.
 */
class MainActivity : ComponentActivity() {
    private val container by lazy {
        AppContainer(
            secrets = AndroidSecrets(applicationContext),
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
            installer = AndroidAppInstaller(applicationContext),
        )
    }

    private val notifier by lazy { AndroidBackgroundNotifier(applicationContext) }

    /**
     * A `claudefleet:` URL, from a cold start or from an already-running app.
     *
     * Both are needed and they are different callbacks: `am start` on a dead
     * process delivers the URL to `onCreate`'s intent, and on a live one to
     * `onNewIntent`. Handling only the first works right up until somebody
     * pairs a second time.
     */
    private fun deliver(intent: Intent?) {
        // A tapped "needs you" notification: open that session.
        intent?.getLongExtra(NeedsYouService.EXTRA_SESSION_ID, -1L)?.takeIf { it >= 0 }?.let(container::onOpenSession)
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let {
            container.onPairLink(it.toString())
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliver(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 draws an app targeting SDK 35 edge to edge whether or not
        // it asked to be, so asking makes the layout the same on every release
        // the app supports rather than changing shape at API 35. The insets are
        // then applied once, in `App`.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Started again at every launch while the person has it on: the
        // system may have stopped it, or the phone restarted.
        notifier.apply()
        deliver(intent)
        setContent { App(container) }
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.foreground = true
    }

    override fun onStop() {
        AppVisibility.foreground = false
        super.onStop()
    }
}
