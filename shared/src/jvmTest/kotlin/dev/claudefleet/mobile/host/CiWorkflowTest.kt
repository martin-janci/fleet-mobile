package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What CI has to do, asserted here because nothing else can assert it.
 *
 * A workflow file is only exercised by being pushed, and by then a weakened one
 * has already stopped catching things. Each property below was learned the
 * expensive way in this project and would cost the same again.
 */
class CiWorkflowTest {

    private val ci: String by lazy { Repo.file(".github/workflows/ci.yml").readText() }

    /**
     * The whole build, not a pair of hand-picked task names.
     *
     * The plan originally specified `:shared:jvmTest :androidApp:assembleDebug`.
     * Both are green on a tree where commonMain calls `Map.toSortedMap()` — a
     * JVM-only extension that does not exist for Kotlin/Native — so neither of
     * them compiles the iOS targets and neither notices. That is not a
     * hypothetical: it happened, and `./gradlew build` is what found it.
     */
    @Test
    fun ci_runs_the_whole_build() {
        // Commands only. This test used to search the whole file, and the step
        // is *named* `./gradlew build`, so replacing the command underneath it
        // with two task names left the test passing — found by mutation P, which
        // is the entire reason for running mutations against one's own gates.
        val commands = ci.lineSequence()
            .filterNot { it.trimStart().startsWith("- name:") }
            .filterNot { it.trimStart().startsWith("name:") }
            .filter { "./gradlew" in it }
            .toList()

        assertTrue(
            commands.any { Regex("""\./gradlew build(\s|$)""").containsMatchIn(it) },
            "CI must run `./gradlew build` — a pair of task names does not compile the iOS targets; found $commands",
        )
    }

    /**
     * `compileSdk` is 37, and the platform package is spelled with its minor
     * version. `platforms;android-37` does not exist, and a CI image without it
     * fails the AAR-metadata check with a message about the artifacts rather
     * than about the SDK.
     */
    @Test
    fun ci_provisions_the_sdk_this_build_needs() {
        assertTrue("platforms;android-37.0" in ci, "platform 37 must be installed by name")
        assertTrue("build-tools;37.0.0" in ci, "build-tools 37 must be installed by name")
    }

    /**
     * The `e:` count is asserted where the output is raw.
     *
     * On the machine this project was written on, the shell wrapper in front of
     * `gradlew` strips `e:` lines, so a failing compile printed a clean-looking
     * log and two agents nearly filed infrastructure bugs that did not exist.
     * Nothing strips anything on a runner, which is exactly why the count is
     * checked there.
     */
    @Test
    fun ci_counts_compiler_errors_in_raw_output() {
        assertTrue("""grep -c '^e: '""" in ci, "CI must count `e:` lines in the raw Gradle output")

        // And the line that turns the count into a failure. Asserting only the
        // grep guards the *measurement* and not the *gate*: delete
        // `test "$errors" -eq 0` and the grep, the echo and this test all stay
        // green while CI stops failing on a broken compile. That is the whole
        // bug this test was written to prevent, living inside the test itself.
        val fails = """test "${'$'}errors" -eq 0"""
        assertTrue(
            ci.lineSequence().any { it.trim() == fails },
            "counting the errors is not a gate unless a non-zero count fails the job",
        )
    }

    /**
     * There is no macOS runner here and there must not be a step that pretends.
     *
     * A workflow running `xcodebuild` on Linux fails for a reason that has
     * nothing to do with the code, and a step that fails for a reason nobody can
     * fix is a step someone eventually deletes — taking whatever it also
     * guarded with it.
     */
    @Test
    fun ci_does_not_claim_to_build_ios() {
        assertTrue("xcodebuild" !in ci, "CI runs on Linux and cannot build iOS")
    }

    /**
     * The instrumentation job stays in the workflow.
     *
     * `AndroidSecrets` is the only thing the app persists and the only class
     * here that cannot be tested without hardware. The job is
     * `continue-on-error` until someone has watched it pass once — but
     * `continue-on-error` and *deleted* are different things, and the second is
     * the easy mistake when a job is red.
     */
    @Test
    fun ci_still_tries_to_run_the_secure_store_test() {
        assertTrue(
            "connectedAndroidDeviceTest" in ci,
            "the emulator job is the only thing that ever executes AndroidSecrets",
        )
    }

    /**
     * If the secure-store job cannot fail the build, the README says so.
     *
     * `continue-on-error` is the right call for a job nobody has watched pass —
     * but it makes the only test that ever executes `AndroidSecrets`
     * structurally incapable of turning CI red, and a comment in a workflow file
     * saying "delete this line one day" is not a thing anyone reads. Either the
     * flag goes, or the README admits the green tick means nothing. This test
     * fails if someone silently keeps the first without the second.
     */
    @Test
    fun a_secure_store_job_that_cannot_fail_is_admitted_in_the_readme() {
        if ("continue-on-error: true" !in ci) return

        // The admission, not the word: "delete the `continue-on-error` line one
        // day" names the flag without telling anyone what it costs today.
        val readme = Repo.file("README.md").readText()
        assertTrue(
            Regex("""`continue-on-error`[^.]*\bcannot\s+fail\b""").containsMatchIn(readme),
            "the emulator job cannot fail CI and the README does not say so",
        )
    }
}
