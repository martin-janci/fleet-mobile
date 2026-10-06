package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Local builds are signed from a file nobody commits; CI builds are not signed
 * at all, exactly as before. Both halves are a few lines of configuration that
 * nothing else would notice going wrong.
 */
class IosSigningTest {

    private val project: String by lazy { Repo.file("iosApp/iosApp.xcodeproj/project.pbxproj").readText() }
    private val gitignore: String by lazy { Repo.file(".gitignore").readText() }

    @Test
    fun the_signing_include_is_optional() {
        val xcconfig = Repo.file("iosApp/Signing.xcconfig").readText()
        assertTrue(
            "#include? \"Signing.local.xcconfig\"" in xcconfig,
            "without the '?', every checkout without the local file fails to build, CI first",
        )
    }

    @Test
    fun both_project_configurations_are_based_on_it() {
        val base = Regex("""baseConfigurationReference = 5FE0A10000000000000000E0 /\* Signing.xcconfig \*/;""")
        assertEquals(2, base.findAll(project).count(), "the project's Debug and Release must both include Signing.xcconfig")
    }

    @Test
    fun no_target_pins_an_empty_team() {
        assertTrue(
            "DEVELOPMENT_TEAM = \"\";" !in project,
            "a target's own DEVELOPMENT_TEAM beats the xcconfig, so an empty one there throws the local team away",
        )
    }

    @Test
    fun the_local_file_and_apple_credentials_are_ignored() {
        for (pattern in listOf("iosApp/Signing.local.xcconfig", "*.p8", "*.mobileprovision")) {
            assertTrue(gitignore.lines().any { it.trim() == pattern }, ".gitignore must list $pattern")
        }
    }

    @Test
    fun no_apple_credential_is_tracked() {
        val offenders = Repo.root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != "build" && it.name != ".gradle" }
            // Extensions only: the ignored Signing.local.xcconfig legitimately exists
            // on a developer's Mac, and the_local_file_and_apple_credentials_are_ignored covers it.
            .filter { it.isFile && it.extension in setOf("p12", "p8", "mobileprovision") }
            .map { it.relativeTo(Repo.root).path }
            .toList()
        assertEquals(emptyList(), offenders)
    }
}
