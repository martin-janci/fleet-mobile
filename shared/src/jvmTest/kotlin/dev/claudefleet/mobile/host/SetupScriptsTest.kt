package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The setup scripts, checked against the build they are supposed to set up.
 *
 * `scripts/bootstrap.sh` hard-codes the two SDK package names, and a script
 * that installs the wrong platform is worse than no script: it runs, it
 * succeeds, and the build then fails its AAR-metadata check with a message
 * about artifacts rather than about the SDK. That is the failure this whole
 * file exists to prevent, and it is exactly what happens the day `compileSdk`
 * is bumped and the script is not.
 *
 * So the versions are read out of `gradle/libs.versions.toml` and compared,
 * rather than being a second copy somebody has to remember to keep in step.
 */
class SetupScriptsTest {

    private val bootstrap: String by lazy { Repo.file("scripts/bootstrap.sh").readText() }
    private val doctor: String by lazy { Repo.file("scripts/doctor.sh").readText() }
    private val versions: String by lazy { Repo.file("gradle/libs.versions.toml").readText() }

    private fun version(key: String): String =
        Regex("""^\s*$key\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .find(versions)?.groupValues?.get(1)
            ?: error("no $key in libs.versions.toml")

    /**
     * The platform the script installs is the one the build compiles against.
     *
     * Spelled with the minor version — `android-37.0`, not `android-37`, which
     * does not exist as a package at all. That trap costs an afternoon the
     * first time and is why the name is asserted rather than assumed.
     */
    @Test
    fun bootstrap_installs_the_platform_this_build_compiles_against() {
        val compileSdk = version("android-compileSdk")

        assertTrue(
            "platforms;android-$compileSdk.0" in bootstrap,
            "bootstrap.sh must install platforms;android-$compileSdk.0 (compileSdk is $compileSdk)",
        )
        assertTrue(
            "platforms;android-$compileSdk\"" !in bootstrap,
            "the package name carries the minor version; 'android-$compileSdk' does not exist",
        )
    }

    /** And the build-tools major matches too. */
    @Test
    fun bootstrap_installs_matching_build_tools() {
        val compileSdk = version("android-compileSdk")

        assertTrue(
            "build-tools;$compileSdk." in bootstrap,
            "bootstrap.sh must install build-tools $compileSdk.x",
        )
    }

    /**
     * `doctor.sh` checks for the same platform it tells people to install.
     *
     * The two scripts drifting apart is the quiet version of this bug: one
     * installs 37.0 and the other looks for 36, so a correctly set up machine
     * is told it is broken.
     */
    @Test
    fun doctor_looks_for_what_bootstrap_installs() {
        val compileSdk = version("android-compileSdk")

        assertTrue(
            """PLATFORM_VERSION="$compileSdk.0"""" in doctor,
            "doctor.sh must check for the same platform bootstrap.sh installs",
        )
        assertTrue(
            """BUILD_TOOLS_MAJOR="$compileSdk"""" in doctor,
            "…and the same build-tools major",
        )
    }

    /**
     * Both scripts stop at the first failure.
     *
     * Without `set -e` a failed download is followed by an extract of nothing,
     * an install of nothing, and a cheerful "Ready" — which is the single most
     * misleading thing a setup script can do. `doctor.sh` deliberately does not
     * use `-e`: it is a report, and it has to reach the end to give one.
     */
    @Test
    fun bootstrap_stops_at_the_first_failure() {
        assertTrue(
            bootstrap.lineSequence().any { it.trim() == "set -euo pipefail" },
            "bootstrap.sh must not carry on after a failed step",
        )
    }

    /**
     * Bootstrap finishes by running something real.
     *
     * A script that installs packages and declares victory has not established
     * that the build works — only that files were copied. The last thing it
     * does is run the suite, so a green run means the machine is ready rather
     * than merely furnished.
     */
    @Test
    fun bootstrap_verifies_by_building_rather_than_by_asserting() {
        assertTrue("./gradlew" in bootstrap, "bootstrap.sh must finish by actually building")
    }

    /** Both are committed executable, or nobody can run them as documented. */
    @Test
    fun the_scripts_are_executable() {
        val notExecutable = listOf("scripts/bootstrap.sh", "scripts/doctor.sh")
            .map { Repo.file(it) }
            .filterNot { it.canExecute() }
            .map { it.name }

        assertEquals(emptyList(), notExecutable, "README tells people to run these directly")
    }

    /** The README points at them, so they cannot quietly stop being the way in. */
    @Test
    fun the_readme_sends_people_to_the_scripts() {
        val readme = Repo.file("README.md").readText()

        assertTrue("scripts/bootstrap.sh" in readme)
        assertTrue("scripts/doctor.sh" in readme)
    }
}
