package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What the Android host does across a recreation, a killed process, a
 * notification action and the app going to the background (review R09).
 *
 * Source scans, because every one of these lives in a host file no JVM test
 * runs, and each was found by reading rather than by a failing test: a
 * container built per activity, a notification intent replayed on every
 * `onCreate`, a Later that did nothing, and two polls that went on from a
 * phone in a pocket.
 */
class AppLifecycleTest {
    private val manifest by lazy { Repo.file("androidApp/src/main/AndroidManifest.xml").readText() }
    private val activity by lazy {
        Repo.file("androidApp/src/main/kotlin/dev/claudefleet/mobile/android/MainActivity.kt").readText()
    }
    private val application by lazy {
        Repo.file("androidApp/src/main/kotlin/dev/claudefleet/mobile/android/FleetApplication.kt").readText()
    }
    private val service by lazy {
        Repo.file("androidApp/src/main/kotlin/dev/claudefleet/mobile/android/NeedsYouService.kt").readText()
    }
    private val app by lazy { Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt").readText() }
    private val terminals by lazy { Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/Terminals.kt").readText() }
    private val sessionScreen by lazy { Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionScreen.kt").readText() }
    private val bars by lazy {
        Repo.file("shared/src/androidMain/kotlin/dev/claudefleet/mobile/ui/SystemBars.android.kt").readText()
    }

    /** Split screen, a fold, a font or display size, a language: none recreates the activity. */
    @Test
    fun the_activity_handles_every_configuration_change_compose_reads_itself() {
        val declared = Regex("""android:configChanges="([^"]+)"""").find(manifest)?.groupValues?.get(1)
            ?: fail("MainActivity declares no configChanges")
        val handled = declared.split('|').toSet()
        val needed = setOf(
            "orientation", "screenSize", "smallestScreenSize", "screenLayout", "density", "fontScale",
            "locale", "layoutDirection", "keyboard", "keyboardHidden", "navigation", "uiMode",
        )
        assertEquals(emptySet(), needed - handled, "configChanges is missing these; each one recreates the activity")
    }

    /** One container per process, as on iOS: a recreated activity picks the same one back up. */
    @Test
    fun the_container_belongs_to_the_process_not_the_activity() {
        assertTrue("""android:name=".FleetApplication"""" in manifest, "the Application subclass is not registered")
        assertTrue("class FleetApplication : Application()" in application)
        assertTrue("AppContainer(" in application, "FleetApplication builds the container")
        assertTrue("AppContainer(" !in activity, "MainActivity must not build its own AppContainer")
        assertTrue("HttpClient(" !in activity, "a client per activity is leaked on every recreation")
    }

    /** Drafts are written through the container's prefs, so a killed process keeps them. */
    @Test
    fun drafts_are_kept_in_the_prefs() {
        assertTrue("val drafts: DraftMemory = DraftMemory(prefs)" in app)
    }

    /** A recreation or a return from Recents does not open the last notification's session again. */
    @Test
    fun the_launch_intent_is_delivered_only_on_a_fresh_launch_and_only_once() {
        assertTrue("savedInstanceState == null" in activity, "onCreate must not re-deliver after a recreation")
        assertTrue("FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY" in activity, "nor on a return from Recents")
        assertTrue(Regex("""if \(fresh\) deliver\(intent\)""").containsMatchIn(activity))
        val deliver = activity.substringAfter("private fun deliver(", "").substringBefore("override fun")
        assertTrue("setIntent(consumed(intent))" in deliver, "a delivered intent must be marked spent")
        assertTrue("EXTRA_DELIVERED, false)) return" in deliver, "a spent intent must not be delivered again")
        assertTrue("removeExtra(NeedsYouService.EXTRA_SESSION_ID)" in activity)
    }

    /** Later cancels by id, whichever instance of the service posted the alert. */
    @Test
    fun later_withdraws_the_alert_unconditionally() {
        val withdraw = service.substringAfter("private fun withdraw(", "").substringBefore("\n    }")
        assertTrue("cancel(alertId(sessionId))" in withdraw)
        assertTrue("return" !in withdraw, "withdraw must not return early on the in-memory map")
    }

    /** A Later or a sticky restart with "Notify me" off stops, rather than watching again. */
    @Test
    fun the_service_checks_the_stored_choice_before_it_watches() {
        val start = service.substringAfter("override fun onStartCommand(", "").substringBefore("override fun onDestroy")
        val check = start.indexOf("AndroidBackgroundNotifier.isEnabled(this)")
        val watch = start.indexOf("scope.launch { watch() }")
        assertTrue(check in 0 until watch, "the stored setting must be read before the watcher starts")
        assertTrue("START_NOT_STICKY" in start && "stopSelf()" in start)
    }

    /** The two polls stop when the app leaves the screen, and resume when it comes back. */
    @Test
    fun the_terminals_and_control_polls_are_bound_to_the_lifecycle() {
        val pane = terminals.substringAfter("fun TerminalsPane(", "").substringBefore("Column(")
        assertTrue(Regex("""LifecycleStartEffect\([^)]*\)\s*\{\s*handlers\.onShow\(\)""").containsMatchIn(pane))
        assertTrue("DisposableEffect" !in pane)
        assertTrue(Regex("""LifecycleStartEffect\(control\)\s*\{\s*control\.attach\(\)""").containsMatchIn(app))
        assertTrue("DisposableEffect(control)" !in app)
    }

    /** The bar icons follow the app's theme. */
    @Test
    fun the_status_bar_follows_the_app_theme() {
        assertTrue("SystemBarsAppearance(dark = dark)" in app)
        assertTrue("isAppearanceLightStatusBars = !dark" in bars)
        assertTrue("isAppearanceLightNavigationBars = !dark" in bars)
    }

    /** The group summary counts the alerts still showing, not every one posted (r09 F11). */
    @Test
    fun the_summary_counts_only_the_alerts_still_showing() {
        val summarize = service.substringAfter("private fun summarize(", "").substringBefore("\n    }")
        val prune = summarize.indexOf("activeNotifications")
        val count = summarize.indexOf("shown.size < 2")
        assertTrue(prune in 0 until count, "the summary must prune tapped alerts before it counts")
        assertTrue("retainAll" in summarize)
    }

    /** A tap parked while unpaired is dropped by the next pair: its id names a session of the old pairing (r09 F7). */
    @Test
    fun a_new_pair_forgets_a_parked_notification_tap() {
        val pair = app.substringAfter("FirstRun.Pair -> PairRoute(container) {", "").substringBefore("}")
        assertTrue("container.dropOpenRequests()" in pair)
        val drop = app.substringAfter("fun dropOpenRequests()", "").substringBefore("\n    }")
        assertTrue("_openSession.value = null" in drop && "_questionFocus.value = null" in drop)
    }

    /** Before the stream's first list, a missing row reads as loading, not gone (r09 F9). */
    @Test
    fun a_cold_start_does_not_call_the_session_gone() {
        assertTrue("state.session == null && !state.streaming -> \"Reading the fleet…\"" in sessionScreen)
        assertTrue("if (state.streaming) \"This session is gone.\" else \"Reading the fleet…\"" in sessionScreen)
    }
}
