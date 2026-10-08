package dev.claudefleet.mobile.android

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.platform.app.InstrumentationRegistry
import dev.claudefleet.mobile.ui.kit.LocalReducedMotion
import dev.claudefleet.mobile.ui.kit.OrbitPullToRefresh
import dev.claudefleet.mobile.ui.theme.FleetTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The phone's loaders against the real animation setting (redesign 10.11).
 *
 * The emulator job turns animations off (`disable-animations: true`), which
 * is exactly Android's "Remove animations": the animator scale at 0. So this
 * runs the reduced path as a person with that switch on gets it, then turns
 * animations back on while the screen is open and expects the turning Chase
 * on the next frames, and puts the setting back afterwards.
 *
 * The tags are `OrbitRefresh.kt`'s `CHASE_TAG` / `CHASE_REDUCED_TAG`, spelled
 * out because they are internal to `:shared`.
 */
class PhoneLoadersTest {

    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.targetContext.contentResolver
    private val originalScale: String? = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)

    @After
    fun restoreAnimationSetting() {
        setScale(originalScale ?: "1")
    }

    private fun setScale(value: String) {
        // The shell user may write global settings; the app may not. Reading
        // the stream to the end waits for the command to finish.
        instrumentation.uiAutomation
            .executeShellCommand("settings put global ${Settings.Global.ANIMATOR_DURATION_SCALE} $value")
            .use { fd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).readBytes() }
    }

    private fun chases(tag: String): Int =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun showRefreshing(reduced: Boolean? = null) {
        // The loops never settle, so the clock is driven by hand.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FleetTheme {
                CompositionLocalProvider(LocalReducedMotion provides reduced) {
                    OrbitPullToRefresh(isRefreshing = true, onRefresh = {}) {
                        Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    /** Polls a few frames for a change that arrives through the settings observer. */
    private fun frameUntil(condition: () -> Boolean): Boolean {
        repeat(100) {
            compose.mainClock.advanceTimeByFrame()
            if (condition()) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test
    fun nothing_shows_before_the_loader_delay() {
        showRefreshing(reduced = false)
        compose.mainClock.advanceTimeBy(300)
        assertEquals(0, chases("orbit-chase") + chases("orbit-chase-reduced"))
        compose.mainClock.advanceTimeBy(200)
        assertEquals(1, chases("orbit-chase"))
    }

    @Test
    fun animations_off_fade_the_chase_and_on_turn_it() {
        setScale("0")
        showRefreshing()
        compose.mainClock.advanceTimeBy(500)
        assertEquals("animations off draws the fade", 1, chases("orbit-chase-reduced"))
        assertEquals(0, chases("orbit-chase"))

        setScale("1")
        assertTrue("turning animations on mid-wait turns the Chase", frameUntil { chases("orbit-chase") == 1 })
        assertEquals(0, chases("orbit-chase-reduced"))
    }

    @Test
    fun this_phones_override_wins_over_the_system() {
        setScale("1")
        showRefreshing(reduced = true)
        compose.mainClock.advanceTimeBy(500)
        assertEquals(1, chases("orbit-chase-reduced"))
        assertEquals(0, chases("orbit-chase"))
    }
}
