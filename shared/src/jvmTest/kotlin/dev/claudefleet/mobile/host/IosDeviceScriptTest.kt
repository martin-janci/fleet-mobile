package dev.claudefleet.mobile.host

import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The one command that puts a development build on the iPhone. */
class IosDeviceScriptTest {

    private val file by lazy { Repo.file("scripts/ios-device.sh") }
    private val script: String by lazy { file.readText() }

    @Test
    fun it_is_executable_and_strict() {
        assertTrue(file.canExecute(), "chmod +x scripts/ios-device.sh")
        assertTrue("set -euo pipefail" in script)
    }

    @Test
    fun it_parses() {
        val p = ProcessBuilder("bash", "-n", file.path).redirectErrorStream(true).start()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS))
        assertEquals(0, p.exitValue(), p.inputStream.bufferedReader().readText())
    }

    @Test
    fun it_never_touches_the_bundle_identifier() {
        assertTrue(
            "PRODUCT_BUNDLE_IDENTIFIER" !in script,
            "a different bundle identifier is a different Keychain group: every pairing would be lost",
        )
    }

    @Test
    fun it_writes_only_the_local_signing_file() {
        val writes = Regex(""">\s*"?\$?\{?([A-Za-z_./-]+)""").findAll(script)
            .map { it.groupValues[1] }
            .filter { it.endsWith(".xcconfig") || it == "LOCAL" }
            .toSet()
        assertEquals(setOf("LOCAL"), writes, "the script may write \$LOCAL (iosApp/Signing.local.xcconfig) and nothing else")
        assertTrue("LOCAL=iosApp/Signing.local.xcconfig" in script)
    }

    @Test
    fun it_refuses_to_change_a_team_it_did_not_get_told_about() {
        assertTrue("--team" in script)
        assertTrue("refusing to change DEVELOPMENT_TEAM" in script)
    }

    @Test
    fun xcode_is_probed_with_a_deadline() {
        assertTrue(
            Regex("""probe\s+\d+\s+xcodebuild -version""").containsMatchIn(script),
            "Xcode on an external card can hang on exec; the script must give up and say so",
        )
    }

    @Test
    fun it_signs_with_automatic_provisioning_and_installs_with_devicectl() {
        assertTrue("-allowProvisioningUpdates" in script)
        assertTrue("devicectl device install app" in script)
        assertTrue("devicectl device process launch" in script)
    }
}
