package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The TestFlight workflow holds the App Store Connect key. These are the rules
 * that keep it held: where it comes from, where it is written, that it is
 * removed, that a tag is never interpolated, and that only a release tag
 * uploads.
 */
class TestFlightWorkflowTest {

    private val wf: String by lazy { Repo.file(".github/workflows/testflight.yml").readText() }
    private val ci: String by lazy { Repo.file(".github/workflows/ci.yml").readText() }

    private fun stepAt(marker: String): String {
        val start = wf.indexOf("- name: $marker")
        assertTrue(start >= 0, "expected a step named '$marker' in testflight.yml")
        val next = wf.indexOf("\n      - ", start)
        return if (next >= 0) wf.substring(start, next) else wf.substring(start)
    }

    @Test
    fun it_runs_on_v_tags_and_by_hand_and_on_nothing_else() {
        val on = wf.substringAfter("\non:").substringBefore("\n\n")
        assertTrue("'v*'" in on)
        assertTrue("workflow_dispatch" in on)
        assertTrue("pull_request" !in wf, "a fork's PR must never reach the App Store Connect key")
        assertTrue("branches:" !in on)
    }

    @Test
    fun permissions_are_read_only() {
        assertTrue("permissions:\n  contents: read" in wf)
        assertTrue("contents: write" !in wf)
    }

    @Test
    fun the_tag_reaches_shell_only_through_env() {
        for (line in wf.lines().filter { it.trimStart().startsWith("run:") || it.startsWith("          ") }) {
            assertTrue("\${{ github.ref_name }}" !in line || line.trimStart().startsWith("TAG:"), "tag interpolated into a run body: $line")
        }
    }

    @Test
    fun the_build_number_rule_is_the_specs() {
        val version = stepAt("Derive the version and build number")
        assertTrue("10#\$x * 10000 + 10#\$y * 100 + 10#\$z" in version)
        assertTrue(">= 100" in version, "Y or Z of 100 would collide with the next minor's build numbers")
        assertTrue("upload=false" in version)
    }

    @Test
    fun only_a_pushed_release_tag_uploads() {
        val version = stepAt("Derive the version and build number")
        assertTrue("[[ -n \"\$suffix\" ]] && upload=false" in version, "a -suffix tag must not upload")
        assertTrue("x=0 y=0 z=1 upload=false" in version, "workflow_dispatch must not upload")
        val export = stepAt("Export, and upload when this is a release tag")
        assertTrue("Set :destination export" in export, "without upload, the export options must say export")
    }

    @Test
    fun the_key_lives_under_runner_temp_and_is_always_removed() {
        val write = stepAt("Write the App Store Connect key")
        assertTrue("umask 077" in write)
        assertTrue("\$RUNNER_TEMP/asc_key.p8" in write)
        val remove = stepAt("Remove the App Store Connect key")
        assertTrue("if: always()" in remove)
        assertTrue("rm -f \"\$RUNNER_TEMP/asc_key.p8\"" in remove)
    }

    @Test
    fun secrets_reach_the_shell_through_env_only() {
        for (line in wf.lines()) {
            if ("secrets." in line) {
                assertTrue(Regex("""^\s+[A-Z_0-9]+:\s*\$\{\{ secrets\.[A-Z_0-9]+ }}\s*$""").matches(line), "secret used outside an env: mapping: $line")
            }
        }
    }

    @Test
    fun a_missing_secret_fails_before_building() {
        val check = stepAt("Check the App Store Connect secrets")
        for (name in listOf("ASC_KEY_ID", "ASC_ISSUER_ID", "ASC_KEY_P8", "APPLE_TEAM_ID")) assertTrue(name in check)
        assertTrue("exit 1" in check)
    }

    @Test
    fun export_options_upload_to_app_store_connect() {
        val opts = Repo.file("iosApp/ExportOptions.plist").readText()
        assertTrue("<string>app-store-connect</string>" in opts)
        assertTrue(Regex("""<key>destination</key>\s*<string>upload</string>""").containsMatchIn(opts))
        assertTrue("teamID" !in opts, "the team comes from the secret at run time")
    }

    @Test
    fun builds_skip_the_export_compliance_question() {
        val plist = Repo.file("iosApp/iosApp/Info.plist").readText()
        assertTrue(Regex("""<key>ITSAppUsesNonExemptEncryption</key>\s*<false/>""").containsMatchIn(plist))
    }

    @Test
    fun actions_are_pinned_as_in_ci() {
        val pins = { t: String -> Regex("""uses:\s*([\w./-]+)@(\S+)""").findAll(t).associate { it.groupValues[1] to it.groupValues[2] } }
        val ciPins = pins(ci)
        for ((action, pin) in pins(wf)) {
            val sha = Regex("""^[0-9a-f]{40}$""").matches(pin)
            assertTrue(sha || Regex("""^v?\d+(\.\d+){0,2}$""").matches(pin), "$action@$pin")
            if (!sha) ciPins[action]?.let { assertEquals(it, pin, "$action pinned differently from ci.yml") }
        }
    }

    @Test
    fun the_signing_job_runs_in_the_testflight_environment() {
        assertTrue("    environment: testflight" in wf, "the secrets are environment secrets, gated by the environment's branch rule")
        assertTrue("persist-credentials: false" in wf, "checkout must not leave a token in .git/config")
        assertTrue(Regex("""uses:\s*maxim-lobanov/setup-xcode@[0-9a-f]{40}\s+# v1\.\d+\.\d+""").containsMatchIn(wf), "setup-xcode is pinned to a commit")
        assertTrue("timeout-minutes: 60" in wf)
    }
}
