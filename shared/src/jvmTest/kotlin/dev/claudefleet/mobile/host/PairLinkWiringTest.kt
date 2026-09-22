package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The `claudefleet:` scheme is declared on both platforms and reaches the app.
 *
 * Shared code cannot register a URL scheme; each host does it in its own
 * manifest, and a scheme that is handled in Kotlin but declared in neither
 * place is dead code that tests happily exercise. These scans are the only
 * thing between that and somebody discovering it on a device.
 *
 * `PairLinkTest` covers what a link *does* once it arrives. This covers whether
 * it can arrive at all.
 */
class PairLinkWiringTest {

    private val manifest: String by lazy {
        Repo.file("androidApp/src/main/AndroidManifest.xml").readText()
    }
    private val plist: String by lazy { Repo.file("iosApp/iosApp/Info.plist").readText() }
    private val activity: String by lazy {
        Repo.file("androidApp/src/main/kotlin/dev/claudefleet/mobile/android/MainActivity.kt").readText()
    }
    private val contentView: String by lazy { Repo.file("iosApp/iosApp/ContentView.swift").readText() }

    @Test
    fun android_declares_the_scheme() {
        assertTrue("""android:scheme="claudefleet"""" in manifest, "no intent-filter, no link")
        assertTrue("android.intent.action.VIEW" in manifest)
    }

    /**
     * And does **not** make it browsable.
     *
     * `BROWSABLE` is what lets an arbitrary web page launch the app with a URL
     * of its choosing. This scheme is for tooling and for a link the operator
     * already has; the release build only fills the fields either way, but the
     * smaller the set of things that can put a stranger's hub on that screen,
     * the better.
     */
    @Test
    fun android_does_not_make_the_scheme_browsable() {
        assertTrue(
            "android.intent.category.BROWSABLE" !in manifest,
            "a browsable scheme lets any web page launch the app with its own URL",
        )
    }

    /**
     * Both delivery points are handled.
     *
     * `am start` on a dead process delivers the URL to `onCreate`'s intent and
     * on a live one to `onNewIntent`. Handling only the first works right up
     * until somebody pairs a second time, which is exactly the case an agent
     * re-running a setup script hits.
     */
    @Test
    fun android_handles_both_a_cold_start_and_a_running_app() {
        // The *override*, not the name: `unusedOnNewIntent` contains the
        // substring and satisfied a looser check, which a mutation found.
        assertTrue(
            "override fun onNewIntent" in activity,
            "a link to an already-running app must arrive too",
        )
        // The call site in `onCreate`, not the name: `onNewIntent` calls
        // `deliver(intent)` too, so a bare substring check stayed green with
        // the cold-start path deleted. Found by mutation, twice in one file.
        assertTrue(
            Regex("""deliver\(intent\)\s*\n\s*setContent""").containsMatchIn(activity),
            "a link that STARTED the app must arrive, before the UI is built",
        )
    }

    /** The build decides whether a link may pair, and the build alone. */
    @Test
    fun android_ties_auto_pairing_to_the_build_type() {
        assertTrue(
            "autoPairFromLink = BuildConfig.DEBUG" in activity,
            "a release build must fill the fields and wait for a tap",
        )
    }

    /**
     * `onNewIntent` is only reachable if the manifest asks for it.
     *
     * Under the default `standard` launch mode, a second `am start` does not
     * call the override at all — Android stacks a fresh `MainActivity` and the
     * one on screen keeps the first URL. The emulator showed exactly that.
     * Both halves are needed and they live in different files, so removing
     * either one leaves an override that never runs; the instrumentation test
     * `a_second_link_reaches_the_same_activity` is the behavioural guard and
     * this is the cheap one that fails in every CI job rather than only the
     * emulator's.
     */
    @Test
    fun android_asks_for_the_launch_mode_that_makes_onNewIntent_reachable() {
        assertTrue(
            """android:launchMode="singleTop"""" in manifest,
            "without singleTop, a second claudefleet: link stacks a new activity and " +
                "MainActivity.onNewIntent never runs",
        )
    }

    @Test
    fun ios_declares_the_scheme() {
        assertTrue("CFBundleURLTypes" in plist, "no URL type, no link")
        assertTrue("<string>claudefleet</string>" in plist)
    }

    @Test
    fun ios_hands_the_url_to_the_shared_container() {
        assertTrue("onOpenURL" in contentView, "SwiftUI has to forward the URL")
        assertTrue("onPairLink" in contentView, "…to the shared entry point")
    }
}
