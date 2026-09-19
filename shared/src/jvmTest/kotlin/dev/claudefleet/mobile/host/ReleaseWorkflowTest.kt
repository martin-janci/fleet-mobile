package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
     * The text of one step, from its own `- name: <marker>` line up to (but
     * not including) the next top-level step. Several tests below need to
     * assert something is true *within* a specific step rather than anywhere
     * in the file — the missing-secret branch actually failing, apksigner
     * actually being resolved a particular way — and a whole-file substring
     * search cannot tell two steps' worth of similar-looking shell apart.
     *
     * Anchored on `- name: ` rather than on the bare marker text: a comment
     * above a step is free to mention that step's name (or any other step's)
     * in prose — `./gradlew build` names itself in the comment immediately
     * above its own step, for instance — and a bare `indexOf(marker)` would
     * find that comment instead of the step, silently inspecting three lines
     * of prose rather than the step's actual body.
     */
    private fun stepAt(marker: String): String {
        val needle = "- name: $marker"
        val start = release.indexOf(needle)
        assertTrue(start >= 0, "expected to find a step named '$marker' in release.yml")
        val nextStep = release.indexOf("\n      - ", start)
        return if (nextStep >= 0) release.substring(start, nextStep) else release.substring(start)
    }

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
     * Two tags pushed close together must not race each other's build/sign/
     * publish steps against the same `$RUNNER_TEMP` keystore path or the same
     * GitHub release, but a release already running must not be cancelled
     * out from under it by a later push either — `cancel-in-progress` stays
     * `false`, unlike `ci.yml`'s.
     */
    @Test
    fun releases_for_different_tags_do_not_race_and_an_in_flight_one_is_never_cancelled() {
        assertTrue(
            Regex("""(?m)^concurrency:\s*$""").containsMatchIn(release),
            "expected a top-level concurrency block",
        )
        assertTrue("cancel-in-progress: false" in release, "an in-flight release build must never be cancelled")
        assertTrue(
            "group: release-\${{ github.ref_name }}" in release,
            "the concurrency group must incorporate github.ref_name so releases for different tags don't serialize behind each other",
        )
    }

    /**
     * `contents: write` is the only elevated scope anywhere in the file, and it
     * belongs to the one job that creates the release — not to the workflow as
     * a whole, where every other job (if one is ever added) would inherit it.
     *
     * This does not require *exactly* two permission entries: a later, more
     * cautious addition (say, `id-token: none`) must not fail this test on its
     * own. What may never be true is some scope other than `contents` reaching
     * `write`.
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

        val writeScopes = release.lineSequence()
            .filter { Regex("""^\s*[a-z-]+:\s*write\s*$""").matches(it) }
            .toList()
        assertTrue(writeScopes.isNotEmpty(), "expected at least one write scope (contents: write) on the release job")
        assertTrue(
            writeScopes.all { it.trim().startsWith("contents:") },
            "no permission scope other than contents may be write; found $writeScopes",
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

        val step = stepAt("Require the signing secrets")
        assertTrue("missing=()" in step, "must collect the names of absent secrets")
        assertTrue(
            "\${#missing[@]} -gt 0" in step,
            "must branch on whether any secret was found missing",
        )

        // The scaffolding above is not the guarantee by itself: a version that
        // still collects and branches on the missing names but drops `exit 1`
        // would pass both assertions while silently continuing to build an
        // unsigned release. Scoped to this step — `exit 1` appears elsewhere
        // in the file (tag validation, the APK-not-found guard) for reasons
        // that have nothing to do with secrets being absent.
        val gtIndex = step.indexOf("-gt 0")
        val exitIndex = step.indexOf("exit 1", gtIndex)
        assertTrue(
            gtIndex >= 0 && exitIndex > gtIndex,
            "the missing-secret branch must actually `exit 1`, not just log — step body:\n$step",
        )

        val secretsCheckIndex = release.indexOf("missing=()")
        val buildIndex = release.indexOf("gradlew :androidApp:assembleRelease")
        assertTrue(secretsCheckIndex in 0 until buildIndex, "the secrets check must run before the build")
    }

    /**
     * Finding #9: a tag on an untested commit must not publish. The full
     * `./gradlew build` — the same one ci.yml runs — has to run, be gated on
     * its own raw `e:` count exactly like ci.yml's, and finish before
     * `assembleRelease` ever starts; and it must not receive any of the four
     * signing secrets, since verifying the source has nothing to do with
     * signing it.
     */
    @Test
    fun the_full_build_runs_and_is_gated_on_raw_compiler_errors_before_the_signed_apk_is_built() {
        assertTrue(
            release.lineSequence().any { Regex("""\./gradlew build(\s|$)""").containsMatchIn(it) },
            "release.yml must run the full ./gradlew build, not just assembleRelease",
        )
        assertTrue("""grep -c '^e: '""" in release, "the build step must count raw e: lines exactly like ci.yml does")
        val fails = """test "${'$'}errors" -eq 0"""
        assertTrue(
            release.lineSequence().any { it.trim() == fails },
            "counting the errors is not a gate unless a non-zero count fails the job",
        )

        val buildIndex = release.indexOf("./gradlew build")
        val gateIndex = release.indexOf(fails)
        val assembleIndex = release.indexOf("gradlew :androidApp:assembleRelease")
        assertTrue(
            buildIndex in 0 until gateIndex && gateIndex in 0 until assembleIndex,
            "the build must run, then be gated, then (only then) the signed APK is built",
        )

        val buildStep = stepAt("./gradlew build")
        for (secret in listOf(
            "ANDROID_KEYSTORE_BASE64",
            "ANDROID_KEYSTORE_PASSWORD",
            "ANDROID_KEY_ALIAS",
            "ANDROID_KEY_PASSWORD",
        )) {
            assertTrue(secret !in buildStep, "the build/test step must not receive $secret — it only verifies the source")
        }
    }

    /**
     * Decoded under `$RUNNER_TEMP` — never under the checked-out workspace —
     * with owner-only permissions, and removed by a step that runs even when
     * an earlier step in the job failed.
     */
    @Test
    fun the_keystore_lives_under_runner_temp_and_is_always_removed() {
        assertTrue("RUNNER_TEMP" in release, "the keystore file must be written under RUNNER_TEMP")

        val decodeStep = stepAt("Decode the keystore")
        assertTrue(
            "chmod 600" in decodeStep,
            "the decoded keystore must be restricted to owner-only permissions",
        )

        val cleanupStep = stepAt("Remove the decoded keystore")
        assertTrue("if: always()" in cleanupStep, "cleanup must run even if an earlier step in the job failed")
        assertTrue(
            Regex("""rm -f "\${'$'}RUNNER_TEMP/[\w.-]+"""").containsMatchIn(cleanupStep),
            "cleanup must delete the keystore file",
        )
    }

    /**
     * `github.ref_name` is the pushed tag — chosen by whoever can push a tag,
     * not by this repository's owners. It is used elsewhere in the file (an
     * `env:` mapping, and the `concurrency:` group name) — this test is
     * specifically about `run:` bodies, where a raw `${{ github.ref_name }}`
     * would be shell-interpolated before validation ever runs, rather than
     * about every appearance of the string in the file.
     */
    @Test
    fun the_tag_is_never_interpolated_directly_into_a_run_body() {
        assertTrue("github.ref_name" in release, "expected the workflow to read github.ref_name somewhere")

        val runBodies = runBlockBodies(release)
        assertTrue(runBodies.isNotEmpty(), "expected at least one run: block in the workflow")
        for (body in runBodies) {
            assertTrue(
                "github.ref_name" !in body,
                "github.ref_name must never be interpolated directly into a run: body — found in:\n$body",
            )
        }
    }

    /**
     * Every `run:` block's script content, keyed by nothing but indentation —
     * the same rule YAML's block scalars use. A line more indented than its
     * `run:` key belongs to the block; the first line at or below that
     * indentation ends it. Blank lines don't end a block on their own.
     */
    private fun runBlockBodies(text: String): List<String> {
        val lines = text.lines()
        val bodies = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("run:")) {
                val indent = line.length - trimmed.length
                val body = StringBuilder()
                val inline = trimmed.removePrefix("run:").trim()
                if (inline.isNotEmpty() && inline != "|" && inline != ">") body.appendLine(inline)
                var j = i + 1
                while (j < lines.size) {
                    val l = lines[j]
                    if (l.isBlank()) {
                        body.appendLine(l)
                        j++
                        continue
                    }
                    val lIndent = l.length - l.trimStart().length
                    if (lIndent <= indent) break
                    body.appendLine(l)
                    j++
                }
                bodies += body.toString()
                i = j
            } else {
                i++
            }
        }
        return bodies
    }

    /**
     * The tag is validated against a strict pattern before `VERSION_NAME` is
     * derived from it, and that derived value is what everything downstream
     * uses — never the raw tag.
     *
     * `[[ "$TAG" =~ PATTERN ]]` is a **substring** test in bash: with no
     * anchors, it accepts a match anywhere in `$TAG`, not only a match of the
     * whole string. `Regex.matches()` does not reproduce that — it always
     * requires the *entire* input to match, regardless of whether the
     * pattern itself carries `^`/`$`, so a workflow edit that dropped the
     * anchors would still show this test green while the real bash check
     * turned dangerously permissive. `containsMatchIn` is the faithful
     * stand-in for bash's actual behaviour, and the explicit anchor check
     * below catches the same regression more directly, by name.
     */
    @Test
    fun the_tag_is_validated_against_a_strict_pattern_before_use() {
        val patternText = tagValidationPatternText()
        assertTrue(patternText.startsWith("^"), "the tag pattern must anchor its start — bash's =~ is a substring test without one: $patternText")
        assertTrue(patternText.endsWith("$"), "the tag pattern must anchor its end — bash's =~ is a substring test without one: $patternText")
        val pattern = Regex(patternText)

        for (tag in listOf("v1.2.3", "v1.2.3-rc.1", "v0.1.0", "v12.34.56-beta.2")) {
            assertTrue(pattern.containsMatchIn(tag), "expected '$tag' to be accepted by the workflow's own tag pattern")
        }
        // A hostile tag is attacker-controlled: anyone who can push a tag
        // chooses this string. This test fails if the workflow's pattern ever
        // accepts one of these, regardless of how its source text is spelled.
        // A trailing-newline tag is deliberately not in this list: Java's `$`
        // matches just before one trailing line terminator even without
        // MULTILINE, which POSIX ERE (what bash's =~ actually uses) does
        // not — that is a difference between the two regex engines, not a
        // property of the workflow's pattern, so it is not a case this test
        // can exercise faithfully either way.
        for (tag in listOf("v1.2.3; rm -rf /", "v\$(id)", "", "1.2.3", "va.b.c", "v1.2.3 ", "v")) {
            assertFalse(pattern.containsMatchIn(tag), "expected '$tag' to be REJECTED by the workflow's own tag pattern")
        }

        // Ordering, without pinning the shell local the derived version is
        // assigned to: only that the strip-`v` expansion happens after the
        // pattern check, whatever the assignment's left-hand side is called.
        val validateIndex = release.indexOf("=~")
        val deriveIndex = release.indexOf("\${TAG#v}")
        assertTrue(validateIndex in 0 until deriveIndex, "validation must run before the version is derived from the tag")
    }

    /**
     * The regex text the workflow actually checks `$TAG` against, read out of
     * its `[[ "$TAG" =~ PATTERN ]]` test rather than duplicated here by hand —
     * so a change to the workflow's own pattern is what this test exercises,
     * not a second copy of it that could quietly drift from the real one.
     * Returned as text rather than a compiled `Regex` so a caller can inspect
     * the source (e.g. whether it still carries its anchors) before deciding
     * how to evaluate it.
     */
    private fun tagValidationPatternText(): String {
        val match = checkNotNull(Regex("""=~\s*(\S+)\s*\]\]""").find(release)) {
            "expected to find a `[[ ... =~ PATTERN ]]` tag-validation test in release.yml"
        }
        return match.groupValues[1]
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
     * Which of `ANDROID_HOME` / `ANDROID_SDK_ROOT` the setup-android action
     * exports to later steps isn't documented as stable, so `apksigner` must
     * be resolved rather than assumed to live at one hard-coded path — and
     * the resolution must fail closed, not silently run `apksigner verify`
     * with an empty binary path.
     */
    @Test
    fun apksigner_is_resolved_robustly_and_fails_closed_if_missing() {
        val step = stepAt("Verify the APK is signed")

        assertTrue(
            "apksigner_bin" in step && "verify --print-certs" in step,
            "must actually invoke the resolved apksigner binary with verify --print-certs",
        )
        assertTrue(
            "ANDROID_HOME" in step && "ANDROID_SDK_ROOT" in step && "command -v apksigner" in step,
            "must try ANDROID_HOME, then ANDROID_SDK_ROOT, then PATH — not just one hard-coded location",
        )
        assertTrue("sort -V" in step, "must pick the newest build-tools version rather than one pinned in this step")
        assertTrue(
            Regex("""build-tools[/;]\d""").containsMatchIn(step).not(),
            "the build-tools version must not be hard-coded in this step — found a version-looking path",
        )

        // Fail-closed: a resolution that silently produced an empty path
        // would still let `"$apksigner_bin" verify` run as bare `verify`
        // with no APK argument instead of stopping the job outright.
        val notFoundIndex = step.indexOf("apksigner not found")
        assertTrue(notFoundIndex >= 0, "must print a clear message when apksigner cannot be resolved")
        val exitIndex = step.indexOf("exit 1", notFoundIndex)
        assertTrue(exitIndex > notFoundIndex, "must exit non-zero when apksigner cannot be resolved")
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
