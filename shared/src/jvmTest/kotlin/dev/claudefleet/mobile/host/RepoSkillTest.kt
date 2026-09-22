package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The repo skill's load-bearing claims, checked against the things they claim
 * about.
 *
 * `skills/fleet-mobile-repo/SKILL.md` is what an agent reads *before* it
 * touches this repository, which makes it the one document whose being wrong
 * costs the most: a wrong instruction there is followed, not noticed. And it is
 * exactly the kind of document that goes stale, because nothing breaks when it
 * does.
 *
 * So the claims that are checkable are checked. Not the prose — the facts it
 * states about files that are right here: which Gradle tasks CI runs, which
 * launch mode the activity declares, what the URL scheme is. This is the same
 * argument `AndroidHostTest` and `IosHostTest` already make about the hosts,
 * applied to the file that tells somebody what to do with them.
 */
class RepoSkillTest {

    private val skill: String by lazy { Repo.file("skills/fleet-mobile-repo/SKILL.md").readText() }

    /**
     * Every Gradle task the skill names as a test host is one CI actually runs.
     *
     * The table is the skill's most useful paragraph and the easiest to get
     * wrong: an agent that runs the task named there and sees it pass believes
     * the corresponding host is covered. A task that CI has stopped running, or
     * that never existed, would be believed just as readily.
     */
    @Test
    fun the_test_host_table_names_tasks_ci_runs() {
        val ci = Repo.file(".github/workflows/ci.yml").readText()

        val tasks = Regex("""`(:[A-Za-z]+:[A-Za-z0-9]+)`""").findAll(skill)
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(tasks.size >= 3, "expected the skill to name several Gradle tasks, found $tasks")

        val missing = tasks.filterNot { it in ci }
        assertTrue(
            missing.isEmpty(),
            "the skill tells an agent to rely on these tasks, but CI does not run them: $missing",
        )
    }

    /**
     * The launch-mode warning still matches the manifest.
     *
     * The skill says "`MainActivity` is `launchMode=\"singleTop\"`, and it must
     * stay that way", with the symptom to look for. If the attribute ever goes,
     * that paragraph becomes an instruction to check something that is not
     * there — worse than saying nothing, because it sends the reader away from
     * the actual cause.
     */
    @Test
    fun the_launch_mode_warning_is_still_true() {
        val manifest = Repo.file("androidApp/src/main/AndroidManifest.xml").readText()

        if ("""launchMode=\"singleTop\"""" in skill || """launchMode="singleTop"""" in skill) {
            assertTrue(
                """android:launchMode="singleTop"""" in manifest,
                "the skill says MainActivity is singleTop and the manifest no longer says so",
            )
        }
    }

    /** One scheme, and the skill is not a second place that decides what it is. */
    @Test
    fun the_scheme_in_the_skill_is_the_scheme_in_the_code() {
        val pairLink = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/data/PairLink.kt").readText()
        val scheme = Regex("""PAIR_LINK_SCHEME: String = "([^"]+)"""").find(pairLink)?.groupValues?.get(1)
            ?: error("could not read PAIR_LINK_SCHEME from PairLink.kt")

        assertTrue(
            "$scheme:" in skill,
            "the skill hands an agent a URL with a scheme the app does not answer to; " +
                "the app's scheme is '$scheme'",
        )
    }
}
