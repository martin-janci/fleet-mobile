package dev.claudefleet.mobile.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        super.onCreate(savedInstanceState)
        setContent { App(container) }
    }
}
