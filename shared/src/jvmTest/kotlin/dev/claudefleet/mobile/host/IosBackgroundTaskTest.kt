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

    private val plist: String by lazy { Repo.file("iosApp/iosApp/Info.plist").readText() }
    private val delegate: String by lazy { Repo.file("iosApp/iosApp/AppDelegate.swift").readText() }
    private val app: String by lazy { Repo.file("iosApp/iosApp/iOSApp.swift").readText() }
    private val project: String by lazy { Repo.file("iosApp/iosApp.xcodeproj/project.pbxproj").readText() }

    @Test
    fun the_identifier_is_the_same_in_kotlin_swift_and_the_plist() {
        val id = "dev.claudefleet.mobile.needs-you"
        assertTrue(Regex("""<key>BGTaskSchedulerPermittedIdentifiers</key>\s*<array>\s*<string>${Regex.escape(id)}</string>""").containsMatchIn(plist))
        assertTrue("forTaskWithIdentifier: \"$id\"" in delegate)
    }

    @Test
    fun background_fetch_is_declared_and_push_is_not() {
        assertTrue(Regex("""<key>UIBackgroundModes</key>\s*<array>\s*<string>fetch</string>""").containsMatchIn(plist))
        assertTrue("remote-notification" !in plist)
        val entitlements = Repo.root.walkTopDown().filter { it.extension == "entitlements" }.toList()
        assertTrue(entitlements.none { "aps-environment" in it.readText() }, "push is the next sub-project, not this one")
    }

    @Test
    fun the_delegate_registers_at_launch_and_routes_taps() {
        assertTrue("didFinishLaunchingWithOptions" in delegate)
        assertTrue("UNUserNotificationCenter.current().delegate = self" in delegate)
        assertTrue("userInfo[\"sessionId\"]" in delegate, "the key is NEEDS_YOU_SESSION_KEY")
        assertTrue("MainViewControllerKt.onOpenSession" in delegate)
        assertTrue("task.expirationHandler = { run.cancel() }" in delegate)
        assertTrue("completionHandler([])" in delegate, "no banner while the app is on screen")
    }

    @Test
    fun the_app_uses_the_delegate_and_schedules_on_background() {
        assertTrue("@UIApplicationDelegateAdaptor(AppDelegate.self)" in app)
        assertTrue("MainViewControllerKt.scheduleNeedsYouRefreshIfEnabled()" in app)
    }

    @Test
    fun the_delegate_is_compiled_into_the_app() {
        assertTrue("/* AppDelegate.swift in Sources */ = {isa = PBXBuildFile;" in project)
        val appSources = project.substringAfter("5FE0A10000000000000000AD /* Sources */ = {").substringBefore("};")
        assertTrue("AppDelegate.swift in Sources" in appSources)
    }
}
