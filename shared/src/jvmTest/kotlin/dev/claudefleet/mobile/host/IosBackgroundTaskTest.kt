package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The background check's iOS half: nothing it hands to Swift may throw, and
 * the notifier must be the one the app container holds.
 */
class IosBackgroundTaskTest {

    private val main: String by lazy { Repo.file("shared/src/iosMain/kotlin/dev/claudefleet/mobile/MainViewController.kt").readText() }
    private val refresh: String by lazy { Repo.file("shared/src/iosMain/kotlin/dev/claudefleet/mobile/notify/NeedsYouRefresh.kt").readText() }

    @Test
    fun the_task_identifier_is_declared_once_in_kotlin() {
        assertTrue("const val NEEDS_YOU_TASK = \"dev.claudefleet.mobile.needs-you\"" in refresh)
    }

    @Test
    fun swift_gets_a_handle_not_a_suspend_function() {
        assertTrue("fun startNeedsYouCheck(onDone: (Boolean) -> Unit): NeedsYouRun" in main)
        assertTrue("suspend fun" !in main, "an exported suspend function must start on the main thread and cannot be cancelled from Swift")
        assertTrue("@Throws" !in main, "nothing handed to Swift may throw")
    }

    @Test
    fun the_container_holds_the_ios_notifier() {
        assertTrue("notifier = iosNotifier" in main)
        assertTrue("fun onOpenSession(sessionId: Long)" in main)
        assertTrue("fun scheduleNeedsYouRefreshIfEnabled()" in main)
    }

    @Test
    fun the_check_lets_only_cancellation_end_the_job_abnormally() {
        val body = main.substringAfter("fun startNeedsYouCheck(").substringBefore("fun scheduleNeedsYouRefreshIfEnabled")
        assertTrue(
            "catch (e: CancellationException)" in body,
            "an unhandled exception in a launched coroutine terminates a Kotlin/Native process; only cancellation may escape the check",
        )
    }
}
