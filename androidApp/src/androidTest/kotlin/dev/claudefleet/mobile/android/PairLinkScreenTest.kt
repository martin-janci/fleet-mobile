package dev.claudefleet.mobile.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole chain, end to end: a URL goes in, the Pair screen comes out filled.
 *
 * Every link in this chain is already unit-tested in isolation —
 * `pairLinkPayload` splits the URL, `PairTarget.parse` validates it,
 * `PairViewModel.onPairLink` moves it into state — and the manifest routing is
 * `PairLinkRoutingTest`. What no test has ever done is *run* them together with
 * a real Compose hierarchy on the other end, because until now this repo had
 * rendered no Compose anywhere. A screen that never recomposed on the state
 * change, or read the wrong field, would have passed everything.
 *
 * The URL points at `127.0.0.1:1`, which refuses instantly. A debug build
 * auto-submits the pair (that is what `autoPairFromLink` is for and this is a
 * debug build), and the assertion is about the fields the link filled, not
 * about the request it then makes — so the test should not wait on a network.
 */
@RunWith(AndroidJUnit4::class)
class PairLinkScreenTest {

    /**
     * Empty, not `createAndroidComposeRule`: the activity has to be launched
     * with an intent of our own, so the rule attaches to whatever is resumed
     * instead of starting it.
     */
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun a_link_fills_the_pair_screen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("claudefleet:http://127.0.0.1:1/pair#ABCDEFGH"))
            .setClassName(context, MainActivity::class.java.name)

        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("ABCDEFGH").fetchSemanticsNodes().isNotEmpty()
            }

            // The code, and the hub it belongs to: half a link is the failure
            // this catches — a code with no hub cannot pair, and the two
            // halves come from different branches of the parser.
            compose.onNodeWithText("ABCDEFGH").assertIsDisplayed()
            compose.onNodeWithText("http://127.0.0.1:1").assertIsDisplayed()
        }
    }
}
