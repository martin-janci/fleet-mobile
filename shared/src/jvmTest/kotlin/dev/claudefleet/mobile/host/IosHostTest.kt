package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The two things about the iOS host that are fatal rather than cosmetic, plus
 * the names that have to agree across three files.
 *
 * Nobody working in this repository can build iOS — there is no Mac — so these
 * scans are the only check that exists between a change here and a developer
 * finding out on a Mac. They are deliberately about *presence and wiring*, not
 * behaviour: a source scan cannot know whether the app draws, and does not
 * pretend to. What it can know is whether the two lines whose absence stops the
 * app working at all are still there.
 *
 * **The two fatal ones:**
 *
 * 1. `NSCameraUsageDescription`. iOS does not refuse the camera permission when
 *    the purpose string is missing — it terminates the process. On a fresh
 *    install the first screen is Pair, so the app would die the first time
 *    anyone tapped Scan.
 * 2. `ComposeUIViewController`. On iOS, Compose Multiplatform provides
 *    `LocalLifecycleOwner`, the window insets and the frame clock only from
 *    inside that controller. `App` uses `LifecycleStartEffect` and
 *    `WindowInsets.safeDrawing`, so a host that embedded the composables by any
 *    other route would have no lifecycle owner, no event stream and no insets.
 */
class TheIosHostInfoPlistTest {

    private val plist: String by lazy { Repo.file("iosApp/iosApp/Info.plist").readText() }

    /**
     * The key without which Compose refuses to start at all.
     *
     * Compose Multiplatform's iOS runtime throws
     * `IllegalStateException: Info.plist doesn't have a valid
     * CADisableMinimumFrameDurationOnPhone entry, or has it set to false` —
     * on iOS an uncaught Kotlin exception, so a dead app rather than a slow one.
     * There is no first frame.
     *
     * It was missing from the day the host was written, and no scan here caught
     * it, because a scan can only look for what somebody thought to look for.
     * What found it was the XCTest bundle in `iosApp`, the first thing in this
     * repository ever to LAUNCH the app: the macOS job builds and never runs,
     * and `iosSimulatorArm64Test` runs a bare binary with no UI. It crashed on
     * its first run, before reaching the Keychain it was written for.
     *
     * So this test is the belt to that braces, and it checks the *value* and
     * not only the key: `<false/>` satisfies a presence check and fails at
     * launch exactly as absence does.
     */
    @Test
    fun the_frame_duration_opt_in_is_present_and_true() {
        // The `<key>` element, not the first mention of the name: the comment
        // above it explains the key at length and names it several times, so a
        // plain `substringAfter` on the bare name reads the prose instead.
        val element = "<key>CADisableMinimumFrameDurationOnPhone</key>"
        assertTrue(element in plist, "Compose Multiplatform will not start without this key")
        assertTrue(
            plist.substringAfter(element).substringBefore("<key>").contains("<true/>"),
            "the key must be true; <false/> fails at launch exactly as absence does",
        )
    }

    /**
     * The key without which the app is terminated rather than refused.
     *
     * The string is checked for content as well as presence: an empty
     * `<string/>` satisfies the key but leaves the dialog with nothing to say,
     * and App Review rejects it.
     */
    @Test
    fun the_camera_purpose_string_is_present_and_says_something() {
        val purpose = stringValue("NSCameraUsageDescription")
            ?: fail("NSCameraUsageDescription is missing — iOS TERMINATES the app when the scanner opens without it")

        assertTrue(
            purpose.trim().length >= 20,
            "the camera purpose string is shown verbatim in the permission dialog; \"$purpose\" does not explain anything",
        )
    }

    /**
     * A plist Xcode does not read is not a plist.
     *
     * `GENERATE_INFOPLIST_FILE = YES` — the default for a project made by the
     * Xcode wizard — makes Xcode synthesise its own from build settings and
     * ignore this file entirely, at which point the key above is present in the
     * repository and absent from the app. Both halves are asserted in both
     * configurations.
     */
    @Test
    fun the_plist_is_the_one_xcode_uses() {
        val project = Repo.file("iosApp/iosApp.xcodeproj/project.pbxproj").readText()

        assertEquals(
            2,
            Regex("""INFOPLIST_FILE = iosApp/Info\.plist;""").findAll(project).count(),
            "both Debug and Release must point at iosApp/Info.plist",
        )
        assertEquals(
            2,
            Regex("""GENERATE_INFOPLIST_FILE = NO;""").findAll(project).count(),
            "a generated plist would silently replace the one holding NSCameraUsageDescription",
        )
    }

    /**
     * A hub on the operator's own LAN is the ordinary case, and iOS 14+ needs
     * both of these before an app may reach a local-network address over plain
     * HTTP. Neither is fatal for a hub behind a public HTTPS name, which is why
     * they are asserted here rather than in the test above: losing them narrows
     * where the app works instead of stopping it.
     */
    @Test
    fun a_hub_on_the_local_network_is_reachable() {
        assertTrue(
            stringValue("NSLocalNetworkUsageDescription")?.isNotBlank() == true,
            "without a local-network purpose string iOS cannot prompt, and the connection just fails",
        )
        // The KEY element, not the string anywhere in the file. The plist
        // explains this key in a comment three lines above it, so a bare
        // substring test stayed green when the key was deleted and the
        // explanation left behind — a gate guarding its own documentation.
        assertTrue(
            Regex("""<key>NSAllowsLocalNetworking</key>\s*<true\s*/>""").containsMatchIn(plist),
            "App Transport Security blocks http:// to the LAN without this key set true",
        )
        assertTrue(
            "NSAllowsArbitraryLoads" !in plist,
            "a blanket cleartext exception would send the bearer token over plain HTTP to anywhere",
        )
    }

    private fun stringValue(key: String): String? =
        Regex("""<key>${Regex.escape(key)}</key>\s*<string>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(plist)?.groupValues?.get(1)
}

/**
 * The SwiftUI host reaches the shared UI through `ComposeUIViewController`, and
 * by no other route.
 */
class TheIosHostEmbedsComposeCorrectlyTest {

    @Test
    fun the_kotlin_entry_point_returns_a_compose_ui_view_controller() {
        val text = Repo.file("shared/src/iosMain/kotlin/dev/claudefleet/mobile/MainViewController.kt").readText()

        assertTrue(
            "androidx.compose.ui.window.ComposeUIViewController" in text,
            "the iOS entry point must import ComposeUIViewController",
        )
        assertTrue(
            Regex("""fun MainViewController\(\)\s*:\s*UIViewController\s*=\s*ComposeUIViewController""")
                .containsMatchIn(text),
            "MainViewController() must BE a ComposeUIViewController — that is where LocalLifecycleOwner comes from",
        )
    }

    @Test
    fun the_swift_side_wraps_it_in_a_representable() {
        val text = Repo.file("iosApp/iosApp/ContentView.swift").readText()

        assertTrue("UIViewControllerRepresentable" in text, "SwiftUI needs a representable to host a UIViewController")
        assertTrue(
            "MainViewControllerKt.MainViewController()" in text,
            "the representable must return the Kotlin entry point, not a controller of its own",
        )
    }

    /**
     * Exactly one Swift file makes the controller.
     *
     * A second call site would be a second Compose window: two lifecycles, two
     * event streams and two copies of the paired state, disagreeing with each
     * other. `MainViewController()` guards the container against that on the
     * Kotlin side, but only the one that is actually composed is ever visible.
     */
    @Test
    fun there_is_exactly_one_call_site() {
        val callers = Repo.shippedSwift.filter { "MainViewController()" in it.readText() }.map { it.name }

        assertEquals(listOf("ContentView.swift"), callers.sorted())
    }

    /**
     * The three places the framework is named have to agree, and nothing in the
     * build fails when they do not: Gradle produces `Shared.framework`, Xcode
     * links `-framework Shared`, and Swift writes `import Shared`. A rename in
     * one of them surfaces on a Mac as "no such module 'Shared'".
     */
    @Test
    fun the_framework_name_agrees_in_all_three_places() {
        val gradle = Repo.file("shared/build.gradle.kts").readText()
        val project = Repo.file("iosApp/iosApp.xcodeproj/project.pbxproj").readText()
        val swift = Repo.file("iosApp/iosApp/ContentView.swift").readText()

        val baseName = Regex("""baseName\s*=\s*"([^"]+)"""").find(gradle)?.groupValues?.get(1)
            ?: fail("no framework baseName in shared/build.gradle.kts")

        assertEquals("Shared", baseName)
        assertTrue(
            Regex(""""-framework",\s*\n\s*$baseName,""").containsMatchIn(project),
            "the Xcode target must link -framework $baseName",
        )
        assertTrue("import $baseName" in swift, "ContentView.swift must import $baseName")
    }

    /**
     * Gradle must actually be asked to build the framework.
     *
     * Without the run-script phase the Xcode build uses whatever
     * `Shared.framework` happens to be lying in `shared/build` from last time,
     * or none at all, and the failure is a linker error a long way from the
     * cause.
     */
    @Test
    fun xcode_builds_the_framework_before_it_links() {
        val project = Repo.file("iosApp/iosApp.xcodeproj/project.pbxproj").readText()

        assertTrue(
            "embedAndSignAppleFrameworkForXcode" in project,
            "the target needs a run-script phase invoking Gradle",
        )

        // Scoped to the application target rather than to the first
        // `buildPhases` in the file. There is more than one target now — the
        // XCTest bundle has its own Sources phase and, correctly, no Kotlin
        // build phase, since it depends on the app and gets the framework from
        // it. Reading whichever block came first made this test assert
        // something about the wrong target the moment a second one existed.
        val targets = project
            .substringAfter("/* Begin PBXNativeTarget section */")
            .substringBefore("/* End PBXNativeTarget section */")
            .split("};")
        val app = targets.single { "com.apple.product-type.application" in it }

        val phases = app.substringAfter("buildPhases = (").substringBefore(");")
        val script = phases.indexOf("Compile Kotlin Framework")
        val sources = phases.indexOf("Sources")
        assertTrue(script in 0 until sources, "the framework must be built before the Swift sources compile")
    }
}

/**
 * The iOS host asks for nothing at launch, the same rule the Android host
 * follows, checked over Swift because [AndroidHostTest]'s sweeps are `.kt` only.
 *
 * The camera on iOS belongs to `QrScanner.ios.kt` and its `AVCaptureSession`.
 * A Swift file touching AVFoundation would be a second way in — and one that
 * `TheCameraIsAskedForOnlyWhenTheScannerOpensTest` would never see, because it
 * does not read Swift.
 */
class TheIosHostAsksForNothingAtLaunchTest {

    @Test
    fun no_swift_file_touches_the_camera() {
        val offenders = Repo.shippedSwift
            .filter { file ->
                val text = file.readText()
                "AVFoundation" in text || "AVCaptureDevice" in text || "requestAccess" in text
            }
            .map { it.name }

        assertEquals(emptyList(), offenders, "the camera is the shared scanner's, not the host's")
    }

    /**
     * The host is two files and stays two files.
     *
     * Not a style rule: every screen, view model and network call is in
     * `:shared` so that Android and iOS cannot drift, and the moment app logic
     * starts appearing in Swift that guarantee is gone with nothing to announce
     * it. The number is asserted rather than the shape because any third file
     * deserves someone's attention.
     */
    @Test
    fun the_host_is_two_files() {
        assertEquals(
            listOf("ContentView.swift", "iOSApp.swift"),
            Repo.shippedSwift.map { it.name }.sorted(),
            "app logic belongs in shared/src/commonMain, not in the iOS host",
        )
    }
}

/**
 * The iOS host has never been built. This test is the record of that, in a place
 * that fails when it stops being true.
 *
 * Kotlin/Native cross-compiles `iosMain` to a klib on Linux, so
 * `MainViewController.kt` is type-checked against the real UIKit, Foundation and
 * Compose declarations — that much is real. Linking `Shared.framework`,
 * compiling the Swift, and running any of it need macOS and Xcode.
 */
class TheIosHostIsUnbuiltTest {

    @Test
    fun the_readme_says_so_and_lists_what_a_mac_must_check() {
        val readme = Repo.file("README.md").readText()

        assertTrue(
            "What a Mac still has to check" in readme,
            "the README must carry the list of things nobody here can verify",
        )
        assertTrue(
            "NSCameraUsageDescription" in readme && "ComposeUIViewController" in readme,
            "the two fatal requirements must be named in the README, not only in code comments",
        )
    }
    /**
     * The UI-test target is in the project *and* in the shared scheme.
     *
     * Both halves, because either one alone is silent. A target no scheme
     * references is never built, and `xcodebuild test -scheme iosApp` runs
     * what the scheme's `Testables` list names — so dropping the
     * `TestableReference` stops the deep-link test running without removing
     * anything, and the job stays green while proving one fewer thing.
     *
     * This is the same failure the Android side already had: an intent-filter
     * that a source scan could see and the platform never used. The scan is
     * cheap, runs in every job, and does not replace the test — it replaces
     * *nobody noticing the test stopped running*.
     */
    @Test
    fun the_ui_test_target_is_built_and_run() {
        val project = Repo.file("iosApp/iosApp.xcodeproj/project.pbxproj").readText()
        val scheme = Repo.file("iosApp/iosApp.xcodeproj/xcshareddata/xcschemes/iosApp.xcscheme").readText()

        assertTrue(
            "com.apple.product-type.bundle.ui-testing" in project,
            "a UI-testing target is the only thing that can drive the app from outside its process",
        )
        assertTrue("iosAppUITests" in project, "the target has to be in the project")
        assertTrue(
            """BlueprintName = "iosAppUITests"""" in scheme,
            "a target the scheme does not name is never run by `xcodebuild test -scheme iosApp`",
        )
    }

    /**
     * And the result bundle is kept.
     *
     * `PairLinkUITests` attaches the app's accessibility tree, and attachments
     * live in the `.xcresult` rather than in the log. Nobody working on this
     * repository has a Mac, so that bundle is the only way to see how Compose
     * surfaces its text to XCUITest — and without it, a failure cannot
     * distinguish "iOS did not deliver the URL" from "it did, and the query
     * looked in the wrong place".
     */
    @Test
    fun the_ios_result_bundle_is_uploaded() {
        val ci = Repo.file(".github/workflows/ci.yml").readText()

        assertTrue("-resultBundlePath" in ci, "xcodebuild must be told to write a result bundle")
        assertTrue("ios-xcresult" in ci, "and it must be uploaded, or the attachments are lost")
    }

}
