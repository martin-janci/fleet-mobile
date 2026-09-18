package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The system back gesture reaches the navigator.
 *
 * A source scan, because the alternative is nothing. `Navigator.back()` is
 * tested properly in `NavigatorTest` and guarded by a mutation; what could not
 * be tested was whether anything *called* it for the system gesture, and for
 * three tasks nothing did. `Navigator.back()` returns false on a tab
 * specifically so the platform can have the gesture instead — designed,
 * documented, tested, mutation-guarded, and wired to nothing. Back out of an
 * open session finished the activity and left the app.
 *
 * Whether the gesture actually arrives is a question for a device. Whether
 * anything is listening for it is a question for this file.
 */
class TheBackGestureReachesTheNavigatorTest {

    private val app: String by lazy {
        Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt").readText()
    }

    @Test
    fun something_handles_the_system_back_gesture() {
        assertTrue(
            "BackHandler(" in app,
            "no BackHandler: the system back gesture out of a session leaves the app",
        )
    }

    /**
     * It is the multiplatform one.
     *
     * `androidx.activity.compose.BackHandler` would compile — `activity-compose`
     * is on the Android classpath for the scanner's permission launcher — and
     * would then exist only on Android, in a file in `commonMain` that no longer
     * compiles for iOS. `androidx.compose.ui.backhandler.BackHandler` is
     * published for both iOS targets as well.
     */
    @Test
    fun it_is_the_multiplatform_back_handler() {
        assertTrue(
            "import androidx.compose.ui.backhandler.BackHandler" in app,
            "BackHandler must be Compose Multiplatform's, not androidx.activity's",
        )
        assertTrue(
            "androidx.activity.compose.BackHandler" !in app,
            "the androidx.activity BackHandler exists only on Android",
        )
    }

    /**
     * Enabled on a session and nowhere else, which is the navigator's contract
     * expressed where the platform can see it.
     *
     * On a tab the handler must be *disabled* rather than enabled-and-ignoring:
     * a disabled handler lets the gesture reach the system, which is how Android
     * closes the app from the home tab. A handler that swallowed it and returned
     * false would leave the person unable to leave the app by gesture at all.
     */
    @Test
    fun it_is_enabled_only_on_a_session() {
        val call = Regex("""BackHandler\(enabled = ([^)]+)\)""").find(app)
            ?: fail("BackHandler is not called with an explicit `enabled`")

        assertEquals("screen is Screen.Session", call.groupValues[1].trim())
        assertTrue(
            Regex("""BackHandler\([^)]*\)\s*\{\s*nav\.back\(\)\s*\}""").containsMatchIn(app),
            "the handler must call nav.back() and nothing else",
        )
    }

    /** One handler. Two would fight over the gesture in an order nobody picked. */
    @Test
    fun there_is_exactly_one() {
        val callers = Repo.shipped.filter { "BackHandler(" in it.readText() }.map { it.name }

        assertEquals(listOf("App.kt"), callers.sorted())
    }
}
