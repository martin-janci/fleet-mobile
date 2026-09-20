package dev.claudefleet.mobile.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.claudefleet.mobile.App
import dev.claudefleet.mobile.AppContainer
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
            http = HttpClient(OkHttp),
            appVersion = BuildConfig.VERSION_NAME,
            // The build decides, not the link. A release build fills the Pair
            // screen's fields and waits for a tap; a debug build submits, so a
            // dev machine can be set up with no hands. See `data/PairLink.kt`.
            autoPairFromLink = BuildConfig.DEBUG,
        )
    }

    /**
     * A `claudefleet:` URL, from a cold start or from an already-running app.
     *
     * Both are needed and they are different callbacks: `am start` on a dead
     * process delivers the URL to `onCreate`'s intent, and on a live one to
     * `onNewIntent`. Handling only the first works right up until somebody
     * pairs a second time.
     */
    private fun deliver(intent: Intent?) {
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
        deliver(intent)
        setContent { App(container) }
    }
}
