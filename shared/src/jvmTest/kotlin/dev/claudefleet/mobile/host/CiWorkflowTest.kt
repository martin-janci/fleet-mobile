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
     * The `macos` job builds `iosApp` for real now that it links, and pins the
     * three things that would otherwise let it drift into claiming more than a
     * build: no signing identity, no team, no device.
     *
     * A `DEVELOPMENT_TEAM` or an `iphoneos` SDK would imply a signed,
     * device-capable build that does not exist on this runner — nobody has a
     * signing identity here. `generic/platform=iOS Simulator` is the
     * destination that builds without an *installed* runtime, which is why the
     * build step uses it; the Kotlin/Native test step below is a separate
     * claim and does boot one.
     */
    @Test
    fun ci_builds_ios_for_the_simulator_only_and_unsigned() {
        assertTrue("xcodebuild" in ci, "the macos job must actually build iosApp now that it links")
        assertTrue(
            "generic/platform=iOS Simulator" in ci,
            "the generic Simulator destination is the one that builds without an installed runtime",
        )
        assertTrue("CODE_SIGNING_ALLOWED=NO" in ci, "nobody has a signing identity on the runner")
        assertTrue("DEVELOPMENT_TEAM" !in ci, "a team id would imply a signed, device-capable build")
        assertTrue("-sdk iphoneos" !in ci, "this job builds for the Simulator, never a device SDK")
    }

    /**
     * The shared suite actually RUNS on Kotlin/Native.
     *
     * `./gradlew build` on the Linux runner compiles the iOS targets and
     * **cannot run them**: `iosSimulatorArm64Test` is disabled off macOS and
     * the Kotlin plugin only warns. So until this step existed, every assertion
     * in `commonTest` — the address parser, the SSE framing, the conversation
     * merge, the view models' coroutine behaviour, the token-hygiene rules —
     * held for the JVM alone, while the platform with the other string, regex,
     * coroutine and memory implementations was merely compiled. It is also the
     * only thing that ever executes `KeychainSecrets`, the one thing this app
     * persists on iOS, and it is the exact counterpart of the emulator job that
     * exists to execute `AndroidSecrets`.
     *
     * Asserted on the command rather than anywhere in the file, for the reason
     * [ci_runs_the_whole_build] gives: a step keeps its name while the command
     * under it is replaced by one that proves less.
     */
    @Test
    fun ci_runs_the_shared_suite_on_kotlin_native() {
        val commands = ci.lineSequence()
            .filterNot { it.trimStart().startsWith("- name:") }
            .filterNot { it.trimStart().startsWith("name:") }
            .filterNot { it.trimStart().startsWith("#") }
            .filter { "./gradlew" in it }
            .toList()

        assertTrue(
            commands.any { "iosSimulatorArm64Test" in it },
            "the macos job must run :shared:iosSimulatorArm64Test — compiling the iOS targets " +
                "is not running them; found $commands",
        )
    }

    /**
     * And it runs in the job that has a Mac.
     *
     * The same line in the `build` job would not fail: the Kotlin plugin
     * *disables* the task off macOS and prints a warning, so it would read as
     * coverage while asserting nothing whatsoever. That is precisely the hole
     * this pair of tests closes, so it is worth refusing to let it reopen one
     * job higher up.
     */
    @Test
    fun the_native_suite_runs_on_the_macos_runner() {
        val macosJob = ci.substringAfter("\n  macos:")
        assertTrue(macosJob.isNotBlank(), "expected to find the macos: job block")
        assertTrue(
            "iosSimulatorArm64Test" in macosJob,
            "the Kotlin/Native test task is silently disabled anywhere but macOS",
        )
        assertTrue(
            "iosSimulatorArm64Test" !in ci.substringBefore("\n  macos:"),
            "a disabled task in an earlier job would read as coverage while proving nothing",
        )
    }

    /**
     * `macos` is the last job in the file, so everything from its own `macos:`
     * key to the end of the file is this job's block and nothing else's.
     * `continue-on-error` would let a broken iOS link go green on every PR.
     */
    @Test
    fun the_macos_job_never_continues_on_error() {
        val macosJob = ci.substringAfter("\n  macos:")
        assertTrue(macosJob.isNotBlank(), "expected to find the macos: job block")
        assertTrue(
            "continue-on-error" !in macosJob,
            "the macos job must not be allowed to fail silently",
        )
    }

    /**
     * The instrumentation job stays in the workflow.
     *
     * `AndroidSecrets` is the only thing the app persists and the only class
     * here that cannot be tested without hardware, so this job is the only
     * thing that ever executes it — deleting it would leave that code untested.
     */
    @Test
    fun ci_still_tries_to_run_the_secure_store_test() {
        assertTrue(
            "connectedAndroidDeviceTest" in ci,
            "the emulator job is the only thing that ever executes AndroidSecrets",
        )
    }
}
