package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * XML comments removed, so a gate reads the document and not the prose around
 * it. `AndroidManifest.xml` in this repository is more comment than element.
 */
private fun String.withoutXmlComments(): String = replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

/**
 * Kotlin comments removed, for the same reason and with the same urgency: the
 * files these gates read explain the very API names the gates look for, at
 * length, several times each. A scan that counted a mention in a KDoc would go
 * green on a file that had the explanation and not the call — which is the
 * failure [NoSecondKeyboardTransformTest] exists to prevent.
 *
 * Deliberately naive — it does not know about string literals — and that is
 * sound only because it is applied to named files, none of which opens a
 * comment inside a string literal. It is not a general Kotlin lexer and must
 * not be used as one.
 *
 * (Kotlin block comments nest, so this KDoc cannot write the opening delimiter
 * it is describing: doing so opens a comment that never closes, and the file
 * stops parsing at the first top-level declaration after it. That is not a
 * hypothetical — it is how the first draft of this file failed to compile.)
 */
private fun String.withoutKotlinComments(): String = this
    .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
    .replace(Regex("//[^\n]*"), "")

/**
 * The keyboard moves the screen exactly once, on both platforms.
 *
 * `App` pads the whole app with `WindowInsets.safeDrawing`, and `safeDrawing`
 * **contains the IME inset**. That is a complete and correct answer to the
 * keyboard — the prompt box rises, the conversation shrinks — but only for as
 * long as it is the *only* answer. Each platform ships a second one, on by
 * default, and neither is visible from the shared code:
 *
 *  - **Android.** An activity that declares no `windowSoftInputMode` gets
 *    `ADJUST_UNSPECIFIED`, and the system picks for it — in practice panning the
 *    window. The window slides up, and then Compose pads the content up again
 *    inside it. Two transforms, and the top bar walks off the screen.
 *  - **iOS.** `ComposeUIViewController`'s default
 *    `onFocusBehavior = FocusableAboveKeyboard` translates the entire Compose
 *    scene so the focused field clears the keyboard. `safeDrawing` then raises
 *    the prompt box a second time inside the scene that has already moved.
 *
 * `ContentView.swift` already documents this hazard for the SwiftUI side and
 * disables SwiftUI's own keyboard avoidance with `.ignoresSafeArea(.all)`. It
 * does not — and cannot — reach Compose Multiplatform's, which is configured in
 * Kotlin.
 *
 * Neither platform's default can be seen from a JVM test, and this repository
 * can launch neither an emulator nor a simulator. So these are source scans, in
 * the tradition of [AndroidHostTest] and [IosHostTest]: they cannot know that
 * the screen moves once, only that the two lines which decide it are still
 * there. What a device can actually show belongs in the emulator and simulator
 * suites.
 */
class TheKeyboardMovesTheScreenOnceTest {

    /**
     * The attribute is read off the `MainActivity` element rather than looked
     * for anywhere in the file. A manifest can hold several activities, and
     * "the string appears somewhere" would be satisfied by a comment
     * *recommending* the value — which, in this repository, is a likely thing
     * for the file to contain.
     */
    @Test
    fun the_android_activity_asks_the_window_to_resize() {
        val manifest = Repo.file("androidApp/src/main/AndroidManifest.xml").readText().withoutXmlComments()

        val element = Regex("<activity\\b[^>]*>", RegexOption.DOT_MATCHES_ALL)
            .findAll(manifest)
            .map { it.value }
            .firstOrNull { "android:name=\".MainActivity\"" in it }
            ?: fail("no <activity> element for .MainActivity in the manifest")

        assertTrue(
            "android:windowSoftInputMode=\"adjustResize\"" in element,
            """
            MainActivity must declare android:windowSoftInputMode="adjustResize".

            Without it the activity gets ADJUST_UNSPECIFIED and the system pans
            the window, on top of the IME padding `App` already applies through
            WindowInsets.safeDrawing — so the screen travels twice the height of
            the keyboard. With edge-to-edge (`enableEdgeToEdge()`, which clears
            decorFitsSystemWindows) adjustResize does not resize the window
            either; it makes the insets the one and only movement.

            Found instead: $element
            """.trimIndent(),
        )
    }

    /**
     * And the iOS half of the same property.
     *
     * Checked against the file with its comments removed, because the KDoc
     * above the call explains `OnFocusBehavior` by name — a scan over the raw
     * text would pass on the explanation alone.
     */
    @Test
    fun the_ios_controller_leaves_the_keyboard_to_compose() {
        val source = Repo.file("shared/src/iosMain/kotlin/dev/claudefleet/mobile/MainViewController.kt")
            .readText()
            .withoutKotlinComments()

        assertTrue(
            Regex("onFocusBehavior\\s*=\\s*OnFocusBehavior\\.DoNothing").containsMatchIn(source),
            """
            MainViewController must configure onFocusBehavior = OnFocusBehavior.DoNothing.

            ComposeUIViewController defaults to FocusableAboveKeyboard, which
            slides the whole Compose scene above the keyboard. `App` already
            raises the prompt box itself through WindowInsets.safeDrawing, so
            the default makes the box travel twice.
            """.trimIndent(),
        )
    }
}

/**
 * One place applies the IME inset, and it is the root.
 *
 * The two gates above remove each platform's own keyboard avoidance so that
 * `App`'s single `windowInsetsPadding(WindowInsets.safeDrawing)` is the whole
 * of the behaviour. That leaves the third way to get the old bug back, and it
 * is the easiest one to reach for: adding `Modifier.imePadding()` to the prompt
 * box, which reads like an obvious improvement and pads for a keyboard the root
 * has already consumed.
 *
 * `windowInsetsPadding` **consumes** what it applies, so a nested
 * `WindowInsets.safeDrawing` would be harmless — but `imePadding()` and a bare
 * `WindowInsets.ime` are not written in terms of what is left; they ask the
 * platform. Hence the sweep.
 */
class NoSecondKeyboardTransformTest {

    @Test
    fun nothing_pads_for_the_keyboard_a_second_time() {
        val offenders = Repo.shipped
            .map { it to it.readText().withoutKotlinComments() }
            .filter { (_, code) -> "imePadding(" in code || "WindowInsets.ime" in code }
            .map { (file, _) -> file.name }

        assertEquals(
            emptyList(),
            offenders,
            """
            The IME inset is applied once, by App's root
            windowInsetsPadding(WindowInsets.safeDrawing), which already
            contains it. A second application moves the screen twice.
            """.trimIndent(),
        )
    }

    /**
     * And the root application itself is still there. The sweep above is
     * satisfied by an app that pads for nothing at all, which is the other way
     * for the prompt box to end up under the keyboard.
     */
    @Test
    fun the_root_still_insets_the_whole_app() {
        val app = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt")
            .readText()
            .withoutKotlinComments()

        assertTrue(
            Regex("windowInsetsPadding\\(\\s*WindowInsets\\.safeDrawing\\s*\\)").containsMatchIn(app),
            "App must inset the whole app once with WindowInsets.safeDrawing; nothing else does it",
        )
    }
}
