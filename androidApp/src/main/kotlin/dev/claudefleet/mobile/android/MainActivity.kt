package dev.claudefleet.mobile.android

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
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15 draws an app targeting SDK 35 edge to edge whether or not
        // it asked to be, so asking makes the layout the same on every release
        // the app supports rather than changing shape at API 35. The insets are
        // then applied once, in `App`.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { App(container) }
    }
}
