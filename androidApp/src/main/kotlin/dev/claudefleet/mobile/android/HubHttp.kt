package dev.claudefleet.mobile.android

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dispatcher

/**
 * How many calls to the hub may be in flight at once. OkHttp's default is 5
 * per host, and the app holds some of those open on purpose: the `/events`
 * stream for as long as it is connected, and an answer's `wait_for_session`
 * long poll for up to 30 s. With a conversation read, a capture and a
 * download beside them, a small, urgent call — an answer's key, a re-read of
 * the pane before it — queued behind the big ones it has nothing to do with.
 */
internal const val HUB_MAX_REQUESTS_PER_HOST: Int = 16

/** The app's one bare engine: OkHttp, with room for the long-lived calls. */
internal fun hubHttpClient(): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            dispatcher(Dispatcher().apply { maxRequestsPerHost = HUB_MAX_REQUESTS_PER_HOST })
        }
    }
}
