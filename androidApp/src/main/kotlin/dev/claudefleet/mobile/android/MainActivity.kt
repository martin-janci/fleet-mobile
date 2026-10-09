package dev.claudefleet.mobile.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.claudefleet.mobile.App
import dev.claudefleet.mobile.AppContainer

/**
 * Thin Android host: all UI lives in `:shared`.
 *
 * The two things the shared code cannot build for itself — the secure store,
 * which needs a `Context`, and the Ktor engine, which is per-platform — are
 * constructed once per process, in [FleetApplication].
 *
 * It asks for nothing. The camera permission belongs to the scanner and is
 * requested when the scanner opens; an activity that asked for it here would be
 * asking every time the app started, including for the people who never scan
 * anything.
 */
class MainActivity : ComponentActivity() {
    /** The process's container, not the activity's: see [FleetApplication]. */
    private val container: AppContainer get() = (application as FleetApplication).container

    private val notifier: AndroidBackgroundNotifier get() = (application as FleetApplication).notifier

    /**
     * A `claudefleet:` URL, from a cold start or from an already-running app.
     *
     * Both are needed and they are different callbacks: `am start` on a dead
     * process delivers the URL to `onCreate`'s intent, and on a live one to
     * `onNewIntent`. Handling only the first works right up until somebody
     * pairs a second time.
     *
     * Delivered once. The activity's own intent is marked delivered (and loses
     * its session id) afterwards, because `getIntent()` is what a later look
     * at it reads, and an intent still carrying them re-opened that old
     * session (or refilled the Pair screen with a spent code) on top of
     * wherever the person had gone since. The URL itself stays on the intent,
     * marked: `PairLinkRoutingTest` reads it to see that a link arrived.
     */
    private fun deliver(intent: Intent?) {
        if (intent == null || intent.getBooleanExtra(EXTRA_DELIVERED, false)) return
        // A tapped "needs you" notification: open that session.
        intent.getLongExtra(NeedsYouService.EXTRA_SESSION_ID, -1L).takeIf { it >= 0 }?.let(container::onOpenSession)
        intent.takeIf { it.action == Intent.ACTION_VIEW }?.data?.let {
            container.onPairLink(it.toString())
        }
        setIntent(consumed(intent))
    }

    /** [intent] with what [deliver] reads spent, so it cannot be delivered twice. */
    private fun consumed(intent: Intent): Intent = Intent(intent).apply {
        removeExtra(NeedsYouService.EXTRA_SESSION_ID)
        putExtra(EXTRA_DELIVERED, true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
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
        // Only a fresh launch carries news. A recreation (savedInstanceState)
        // or a return from Recents (LAUNCHED_FROM_HISTORY) hands back the
        // intent the task started with, which was delivered long ago.
        val fresh = savedInstanceState == null &&
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        if (fresh) deliver(intent)
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

    private companion object {
        /** Set on the activity's intent once [deliver] has handed it over. */
        const val EXTRA_DELIVERED = "dev.claudefleet.mobile.DELIVERED"
    }
}
