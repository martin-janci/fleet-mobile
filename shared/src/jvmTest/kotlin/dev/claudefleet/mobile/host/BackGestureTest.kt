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
     * Enabled on the screens pushed over a tab — a session, the New session
     * form, a task (the Work view's detail), a session's worktree — and
     * nowhere else: the navigator's own contract, `nav.isPushed` (which knows
     * the layout: Hosts, Files and Settings are pushed over More on the New
     * bar), read directly
     * rather than spelled out again here where it could drift from it.
     *
     * On a tab the handler must be *disabled* rather than enabled-and-ignoring:
     * a disabled handler lets the gesture reach the system, which is how Android
     * closes the app from the home tab. A handler that swallowed it and returned
     * false would leave the person unable to leave the app by gesture at all.
     */
    @Test
    fun it_is_enabled_only_on_a_pushed_screen() {
        val call = Regex("""BackHandler\(enabled = ([^)]+\)?)\)""").find(app)
            ?: fail("BackHandler is not called with an explicit `enabled`")

        assertEquals("nav.isPushed(screen)", call.groupValues[1].trim())
        assertTrue(
            Regex("""BackHandler\(enabled = nav\.isPushed\(screen\)\)\s*\{\s*nav\.back\(\)\s*\}""").containsMatchIn(app),
            "the handler must call nav.back() and nothing else",
        )
    }

    /**
     * One file, and one handler per question. Two that answered the same
     * question would fight over the gesture in an order nobody picked. The
     * Settings tab's closes an open fleet settings page (drawn inside the
     * tab, so not a pushed screen); on the New layout the Orbit settings
     * (redesign 14.11) has its own in the other branch of the same `if`,
     * which also closes an open group, so only one of the two is ever composed. The worktree screen's answers another:
     * enabled only while a diff, commit or file is open over its tab, and
     * composed inside the screen — after `App`'s — so it is asked first and
     * closes what is open before `nav.back()` leaves the screen. A session's
     * Files tab on the New bar (redesign 14.4) is that worktree inside the
     * session, and its handler is the same shape: enabled only while the tab
     * shows an open diff, commit or file. The Company
     * screen's is the same shape: enabled only while one org's overview is
     * open over its list, and composed after `App`'s, so back closes the org
     * before it leaves the screen. The New session wizard's (redesign 14.6)
     * is enabled only on its Project or Review step, so back goes one step
     * back; on Where it is off and `nav.back()` leaves the form.
     */
    @Test
    fun there_is_exactly_one() {
        val callers = Repo.shipped.filter { "BackHandler(" in it.readText() }.map { it.name }
        assertEquals(listOf("App.kt"), callers.sorted())

        val app = Repo.shipped.single { it.name == "App.kt" }.readText()
        val enables = Regex("""BackHandler\(enabled = ([^)]+\)?)\)""").findAll(app).map { it.groupValues[1].trim() }.toList()
        assertEquals(
            listOf(
                "nav.isPushed(screen)",
                "companyState.openId != null",
                "fleetPageOpen || place != SettingsPlace.Home",
                "fleetPageOpen",
                "wizard && wizardStep.previous != null && !state.creating",
                "filesOpen",
                "state.views.isNotEmpty()",
            ),
            enables,
        )
    }
}
