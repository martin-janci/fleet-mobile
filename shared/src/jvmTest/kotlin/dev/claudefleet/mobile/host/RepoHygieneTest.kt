package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `.gitignore` and the README's own instructions have to agree about where
 * signing material may land, for the same reason [ReleaseWorkflowTest] checks
 * the workflow itself: this repository is public, and an upload keystore
 * committed by mistake cannot be un-published.
 */
class RepoHygieneTest {

    private val gitignore: String by lazy { Repo.file(".gitignore").readText() }
    private val readme: String by lazy { Repo.file("README.md").readText() }

    /**
     * `*.jks` alone does not cover the base64 form the Releasing section has
     * the operator create (`release.jks.base64`) — that pattern has no `.jks`
     * suffix at all, so it was tracked by default. `*.keystore`/`*.p12` are
     * the other common keystore extensions `keytool` and other tools produce.
     */
    @Test
    fun gitignore_excludes_every_keystore_shape_the_readme_tells_an_operator_to_create() {
        for (pattern in listOf("*.jks", "*.jks.base64", "*.keystore", "*.keystore.base64", "*.p12")) {
            assertTrue(pattern in gitignore, "expected .gitignore to exclude $pattern")
        }
    }

    /**
     * The Releasing section walks an operator through creating a keystore and
     * base64-encoding it. Both must land outside the working tree — inside it,
     * only a `.gitignore` pattern stands between that file and a commit, and
     * this test is what would catch the instructions drifting back to a bare,
     * repo-relative filename.
     */
    @Test
    fun the_readme_writes_keystore_material_outside_the_working_tree() {
        val releasing = readme.substringAfter("## Releasing").substringBefore("## What a Mac still has to check")

        assertTrue("keytool" in releasing, "expected the keytool command that creates the keystore")
        assertTrue(
            Regex("""-keystore\s+"?\$\{?TMPDIR""").containsMatchIn(releasing),
            "the keystore must be created under \$TMPDIR, not a bare filename in the repo:\n$releasing",
        )
        assertTrue(
            Regex("""base64.*>\s*"?\$\{?TMPDIR""").containsMatchIn(releasing),
            "the base64 form must be written under \$TMPDIR too, not release.jks.base64 in the repo:\n$releasing",
        )
    }
}
