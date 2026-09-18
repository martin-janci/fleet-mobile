package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every error banner can be put away.
 *
 * Review N-B1 found that two of five screens had a Dismiss and three did not,
 * and the three without were the ones where an error sits longest: the banner
 * holds until the *next* call succeeds, and against a hub that is down that is
 * never. The rows behind it are still the last good picture, so the banner is
 * not dangerous — it is just an error a person has read and cannot put away,
 * which is how people learn to stop reading banners.
 *
 * A source scan because a composable cannot be rendered here. The view models'
 * `dismissError` is tested properly in `commonTest`; what that cannot show is
 * whether a screen ever offers the button.
 */
class EveryErrorBannerCanBeDismissedTest {

    /** Every screen that draws an `ErrorBanner`, and what it passes. */
    private val callSites: Map<String, String> by lazy {
        Repo.shipped
            .filter { it.name.endsWith("Screen.kt") }
            .mapNotNull { file ->
                val call = CALL.find(file.readText()) ?: return@mapNotNull null
                file.name to call.value
            }
            .toMap()
            .also { assertTrue(it.isNotEmpty(), "no ErrorBanner call sites found at all") }
    }

    @Test
    fun all_five_screens_draw_one() {
        assertEquals(
            listOf(
                "HostsScreen.kt",
                "PairScreen.kt",
                "SessionScreen.kt",
                "SessionsScreen.kt",
                "SettingsScreen.kt",
            ),
            callSites.keys.sorted(),
        )
    }

    @Test
    fun every_one_of_them_passes_a_dismiss() {
        val without = callSites.filterValues { "onDismiss" !in it }.keys.sorted()

        assertEquals(emptyList(), without, "an error banner with no way to close it")
    }

    private companion object {
        val CALL = Regex("""ErrorBanner\([^)]*\)""")
    }
}
