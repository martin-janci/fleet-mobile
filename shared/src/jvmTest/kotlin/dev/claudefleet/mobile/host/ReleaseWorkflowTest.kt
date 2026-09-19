package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The security-relevant properties of `.github/workflows/release.yml`, for the
 * same reason [CiWorkflowTest] exists: a workflow file is only exercised by
 * being pushed, and this repository is public, so a weakened release workflow
 * would not be noticed until it had already signed or published something it
 * should not have.
 *
 * This does not re-check what [CiWorkflowTest] already checks (the SDK
 * provisioning, the raw `e:` count) — only what is specific to cutting a
 * signed release: the trigger, the permission scope, the refusal to publish
 * unsigned, where the keystore lives, and the tag being attacker-controlled
 * input.
 */
class ReleaseWorkflowTest {

    private val release: String by lazy { Repo.file(".github/workflows/release.yml").readText() }
    private val ci: String by lazy { Repo.file(".github/workflows/ci.yml").readText() }

    /**
     * Nothing else may kick off a release. In particular not `pull_request` —
     * a fork's PR would otherwise reach the signing secrets — and not
     * `workflow_dispatch`, which would let a run publish without a tag whose
     * name has been validated at all.
     */
    @Test
    fun it_triggers_only_on_pushed_v_tags() {
        val onBlock = release.substringAfter("\non:").substringBefore("\n\n")

        assertTrue("tags:" in onBlock, "must trigger on tags, not branches")
        assertTrue("'v*'" in onBlock, "must trigger on v* tags specifically")
        assertTrue("branches:" !in onBlock, "a branch push must not cut a release")
        assertTrue("pull_request" !in release, "a fork's PR must never reach the signing secrets")
        assertTrue("workflow_dispatch" !in release, "manual dispatch would publish without a validated tag")
    }

    /**
     * `contents: write` is the only elevated scope anywhere in the file, and it
     * belongs to the one job that creates the release — not to the workflow as
     * a whole, where every other job (if one is ever added) would inherit it.
     */
    @Test
    fun permissions_are_contents_write_on_the_release_job_only() {
        assertTrue(
            Regex("""(?m)^permissions:\s*\n\s+contents:\s*read\s*$""").containsMatchIn(release),
            "the workflow-level default must be contents: read",
        )
        assertTrue(
            Regex("""(?m)^\s+permissions:\s*\n\s+contents:\s*write\s*$""").containsMatchIn(release),
            "the release job must escalate to contents: write to create the release",
        )

        val scopeLines = release.lineSequence()
            .filter { Regex("""^\s*[a-z-]+:\s*(read|write|none)\s*$""").matches(it) }
            .toList()
        assertEquals(
            2,
            scopeLines.size,
            "exactly two permission entries are expected (workflow read, job write); found $scopeLines",
        )
    }

    /**
     * Absence of any one of the four is a release that cannot be signed, and
     * this must be caught before Gradle ever runs — not discovered as an
     * unsigned APK after the fact.
     */
    @Test
    fun it_fails_before_building_when_a_signing_secret_is_missing() {
        for (secret in listOf(
            "ANDROID_KEYSTORE_BASE64",
            "ANDROID_KEYSTORE_PASSWORD",
            "ANDROID_KEY_ALIAS",
            "ANDROID_KEY_PASSWORD",
        )) {
            assertTrue(secret in release, "the workflow must reference $secret")
        }

        assertTrue("missing=()" in release, "must collect the names of absent secrets")
        assertTrue(
            "\${#missing[@]} -gt 0" in release,
            "must branch on whether any secret was found missing",
        )

        val secretsCheckIndex = release.indexOf("missing=()")
        val buildIndex = release.indexOf("gradlew :androidApp:assembleRelease")
        assertTrue(secretsCheckIndex in 0 until buildIndex, "the secrets check must run before the build")
    }

    /**
     * Decoded under `$RUNNER_TEMP` — never under the checked-out workspace —
     * and removed by a step that runs even when an earlier step in the job
     * failed.
     */
    @Test
    fun the_keystore_lives_under_runner_temp_and_is_always_removed() {
        assertTrue("RUNNER_TEMP" in release, "the keystore file must be written under RUNNER_TEMP")

        val cleanupIndex = release.indexOf("Remove the decoded keystore")
        assertTrue(cleanupIndex >= 0, "expected a dedicated step that removes the keystore")

        val cleanupStep = release.substring(cleanupIndex, minOf(release.length, cleanupIndex + 150))
        assertTrue("if: always()" in cleanupStep, "cleanup must run even if an earlier step in the job failed")
        assertTrue(
            Regex("""rm -f "\${'$'}RUNNER_TEMP/[\w.-]+"""").containsMatchIn(cleanupStep),
            "cleanup must delete the keystore file",
        )
    }

    /**
     * `github.ref_name` is the pushed tag — chosen by whoever can push a tag,
     * not by this repository's owners. Every line naming it must be an `env:`
     * mapping (`KEY: ${{ github.ref_name }}`); none may sit inside a `run:`
     * body, where it would be shell-interpolated before validation ever runs.
     */
    @Test
    fun the_tag_reaches_shell_only_through_env_never_interpolated_into_a_run_body() {
        val refNameLines = release.lineSequence()
            .filter { "github.ref_name" in it }
            .filterNot { it.trim().startsWith("#") }
            .toList()

        assertTrue(refNameLines.isNotEmpty(), "expected the workflow to read github.ref_name somewhere")
        for (line in refNameLines) {
            assertTrue(
                Regex("""^\s*\w+:\s*\${'$'}\{\{\s*github\.ref_name\s*}}\s*$""").matches(line),
                "github.ref_name must only appear as an env: mapping value, never inline in a run: body — found: $line",
            )
        }
    }

    /**
     * The tag is validated against a strict pattern before `VERSION_NAME` is
     * derived from it, and that derived value is what everything downstream
     * uses — never the raw tag.
     */
    @Test
    fun the_tag_is_validated_against_a_strict_pattern_before_use() {
        assertTrue(
            "^v[0-9]+(\\.[0-9]+)*(-[0-9A-Za-z.-]+)?\$" in release,
            "the tag must be checked against a digits-and-dots pattern with an optional bounded suffix",
        )
        val validateIndex = release.indexOf("[0-9A-Za-z.-]")
        val deriveIndex = release.indexOf("version=\"\${TAG#v}\"")
        assertTrue(validateIndex in 0 until deriveIndex, "validation must run before the version name is derived from the tag")
    }

    /**
     * A signing config that silently failed to apply would still produce an
     * APK that builds successfully; this is what turns that into a failed
     * release rather than a published one, and it has to run before the
     * publish step, not after.
     */
    @Test
    fun the_apk_signature_is_verified_before_the_release_is_published() {
        assertTrue("apksigner" in release, "the built APK's signature must be checked with apksigner")

        val verifyIndex = release.indexOf("apksigner")
        val publishIndex = release.indexOf("gh release create")
        assertTrue(verifyIndex >= 0 && publishIndex >= 0 && verifyIndex < publishIndex, "signing must be verified before the release is published")
    }

    /**
     * `gh` is preinstalled on the runner and authenticates with
     * `GITHUB_TOKEN` — pulling in a third-party release action would be one
     * more piece of supply chain nobody asked for.
     */
    @Test
    fun it_publishes_with_the_preinstalled_gh_cli_not_a_third_party_action() {
        assertTrue("gh release create" in release, "must use the gh CLI to publish")
        assertTrue(
            release.lineSequence().none { line -> "uses:" in line && "release" in line.lowercase() },
            "no third-party release action may be used to publish",
        )
    }

    /**
     * The four signing values must reach Gradle as environment variables on
     * the one step that needs them — `androidApp/build.gradle.kts` reads them
     * with `System.getenv`. A `-P` flag would put the same values on the
     * process command line, which is visible to anything else on the runner
     * and is recorded in the Gradle console log.
     */
    @Test
    fun signing_secrets_reach_gradle_through_env_not_command_line_flags() {
        assertTrue(
            Regex("""-P\S*(?i:keystore|key.?alias|key.?password)""").containsMatchIn(release).not(),
            "signing values must never be passed to Gradle as -P command-line arguments",
        )
        assertTrue("set -x" !in release, "set -x would echo every expanded variable, secrets included, to the log")
    }

    /**
     * Every third-party action release.yml uses is pinned the same way
     * ci.yml already pins it — a released version tag, not a branch — and
     * where the two workflows share an action, they pin it to the same
     * version rather than drifting apart.
     */
    @Test
    fun third_party_actions_are_pinned_the_same_way_as_ci() {
        val releasePins = usesPins(release)
        val ciPins = usesPins(ci)

        assertTrue(releasePins.isNotEmpty(), "release.yml must use at least one action")
        for ((action, pin) in releasePins) {
            assertTrue(
                Regex("""^v?\d+(\.\d+){0,2}$""").matches(pin),
                "$action must be pinned to a released version, not a branch or a floating tag; found @$pin",
            )
            val ciPin = ciPins[action]
            if (ciPin != null) {
                assertEquals(ciPin, pin, "$action is pinned differently in release.yml than in ci.yml")
            }
        }
    }

    private fun usesPins(text: String): Map<String, String> =
        Regex("""uses:\s*([\w./-]+)@(\S+)""").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
}
