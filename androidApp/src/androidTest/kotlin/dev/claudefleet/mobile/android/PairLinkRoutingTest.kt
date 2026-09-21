package dev.claudefleet.mobile.android

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Does a `claudefleet:` URL actually reach this app?
 *
 * Everything else about the deep link is already tested somewhere: what the URL
 * means is `PairLinkTest` in `commonTest`, and that the manifest *contains* an
 * intent-filter is `PairLinkWiringTest`, a source scan. Neither answers the
 * question that matters, which is whether **Android** routes the URL here given
 * that manifest — a claim about the merged manifest and the platform's own
 * resolution, and therefore one only a device can settle.
 *
 * It was worth not assuming. A scheme declared with the wrong element, or in an
 * intent-filter missing `DEFAULT`, or shadowed by another filter, passes a
 * source scan and routes nothing.
 */
@RunWith(AndroidJUnit4::class)
class PairLinkRoutingTest {

    private val pairUrl = "claudefleet:http://127.0.0.1:1/pair#ABCDEFGH"

    private fun viewIntent(uri: String) = Intent(Intent.ACTION_VIEW, Uri.parse(uri))

    /**
     * The real `PackageManager`, against the real merged manifest: an
     * implicit `VIEW` intent for our scheme resolves to *this* activity.
     */
    @Test
    fun the_manifest_routes_our_scheme_to_the_main_activity() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val matches = context.packageManager
            .queryIntentActivities(viewIntent(pairUrl), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.name }

        assertTrue(
            "a claudefleet: URL must resolve to MainActivity; resolved to $matches",
            matches.any { it.endsWith("MainActivity") },
        )
    }

    /**
     * And nothing else is claimed.
     *
     * `BROWSABLE` is deliberately absent so an arbitrary web page cannot launch
     * the app with a URL of its choosing, and the app must not have quietly
     * become the handler for `http`/`https` — which is what an over-broad
     * `<data>` element does, and which would put this app in the chooser for
     * every link on the phone.
     */
    @Test
    fun the_app_does_not_claim_the_web() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        for (url in listOf("https://fleet.example.com/pair#ABCDEFGH", "http://example.com")) {
            val ours = context.packageManager
                .queryIntentActivities(viewIntent(url), PackageManager.MATCH_DEFAULT_ONLY)
                .map { it.activityInfo.packageName }
                .filter { it == context.packageName }

            assertEquals("$url must not resolve to this app", emptyList<String>(), ours)
        }
    }

    /**
     * A cold start carries the URL in.
     *
     * `am start` on a dead process delivers to `onCreate`'s intent. The
     * assertion is deliberately modest — that the activity starts and holds the
     * URL — because what happens *next* is the Compose test's business; what is
     * being established here is that the intent survives the launch.
     */
    @Test
    fun a_cold_start_arrives_with_the_url() {
        val intent = viewIntent(pairUrl).setClassName(
            ApplicationProvider.getApplicationContext<android.content.Context>(),
            MainActivity::class.java.name,
        )

        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(Intent.ACTION_VIEW, activity.intent.action)
                assertEquals(pairUrl, activity.intent.data?.toString())
            }
        }
    }

    /**
     * And so does a second link to an already-running app — to the *same*
     * activity.
     *
     * This is the case `onNewIntent` exists for, and the one an agent hits when
     * it re-runs a setup script: the app is already up and a second
     * `claudefleet:` URL arrives. Whether that reaches `onNewIntent` at all is
     * not up to the override — it is up to the manifest's launch mode. Under
     * the default `standard`, Android stacks a second `MainActivity` on the
     * first, and the override never runs; the app still pairs, but back now
     * returns to a stale Pair screen holding the previous code.
     *
     * So the assertion is on the instance, not just the URL: one activity, and
     * it is holding the newest link.
     */
    @Test
    fun a_second_link_reaches_the_same_activity() {
        val second = "claudefleet:http://127.0.0.2:1/pair#HGFEDCBA"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = viewIntent(pairUrl).setClassName(context, MainActivity::class.java.name)

        val scenario = ActivityScenario.launch<MainActivity>(first)
        try {
            lateinit var original: MainActivity
            scenario.onActivity { original = it }

            // Through the platform, with no SINGLE_TOP flag of our own: exactly
            // what `am start` does, so the manifest's launch mode is what
            // decides where it lands.
            scenario.onActivity {
                it.startActivity(viewIntent(second).setClassName(context, MainActivity::class.java.name))
            }

            awaitIntent(scenario, second)
            scenario.onActivity { current ->
                assertTrue(
                    "a second link must reuse the activity (needs launchMode=singleTop), " +
                        "but a new instance was created",
                    current === original,
                )
            }
        } finally {
            // Finished directly, and `close()` is never called.
            //
            // `ActivityScenario.close()` drives the activity to DESTROYED and
            // waits for it; after a self-sent VIEW intent the task settles with
            // the activity PAUSED rather than RESUMED, which the scenario
            // cannot unwind — it spent 47 seconds failing to. Nothing about
            // that is a property of the app: by the time this runs, the
            // delivery the test is about has already been asserted. Finishing
            // the activity is what actually ends it, and leaves nothing behind
            // for the next test.
            scenario.onActivity { it.finishAndRemoveTask() }
        }
    }

    /** Spins until the activity holds [url], because delivery is asynchronous. */
    private fun awaitIntent(scenario: ActivityScenario<MainActivity>, url: String) {
        val deadline = System.currentTimeMillis() + 5_000
        var seen: String? = null
        while (System.currentTimeMillis() < deadline) {
            scenario.onActivity { seen = it.intent.data?.toString() }
            if (seen == url) return
            Thread.sleep(50)
        }
        assertEquals("the newest link must be the one the activity holds", url, seen)
    }
}
