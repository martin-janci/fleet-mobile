package dev.claudefleet.mobile.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A test function nobody annotated is a test nobody runs.
 *
 * It fails silently in the worst possible direction: the file compiles, the
 * suite is green, the count goes up by one less than anybody notices, and the
 * property the function describes is guarded by nothing at all. It reads as
 * covered in review, because review reads the name and the body.
 *
 * This is not hypothetical and it is not rare. It happened **twice in one
 * week** in this repository, both times in a batch of tests added at the end
 * of a change, both times to a function whose neighbours were annotated
 * correctly — which is exactly why the eye slides over it.
 *
 * The rule is narrow on purpose: a function is a test when its **name looks
 * like a sentence** — `lower_case_with_underscores`, the convention every test
 * in this repository follows — and it sits inside a file under a test source
 * set. Helpers (`private fun row(...)`, `fun TestScope.viewModel(...)`) are
 * camelCase and are not touched. So the gate has one way to be wrong in each
 * direction, and both are cheap: a helper named with underscores would have to
 * be renamed, and a test named in camelCase would not be caught.
 */
class EveryTestFunctionIsAnnotatedTest {

    /** Every Kotlin file under a test source set, across every module. */
    private val testSources: List<File> by lazy {
        listOf(File(Repo.root, "shared/src"), File(Repo.root, "androidApp/src"))
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> TEST_TREES.any { "${File.separator}$it${File.separator}" in f.path } }
            .toList()
    }

    /**
     * [TEST_TREES] is the whole truth about where tests live, checked against
     * the disk in **both** directions.
     *
     * The first version of this only checked that every listed tree existed,
     * and a mutation sweep walked straight through it: deleting `commonTest`
     * from the list left the remaining entries findable, so the guard passed
     * while the scan had silently stopped covering the largest source set in
     * the build. A list that may quietly shrink is the same failure as the
     * missing annotation one directory up — coverage that looks present and
     * is not.
     *
     * So the list is compared with what is actually there. A new source set
     * fails this until it is listed; a deleted entry fails it until the
     * directory goes too.
     */
    @Test
    fun the_scan_knows_every_test_source_set_on_disk() {
        val onDisk = listOf(File(Repo.root, "shared/src"), File(Repo.root, "androidApp/src"))
            .filter { it.isDirectory }
            .flatMap { it.listFiles().orEmpty().toList() }
            .filter { it.isDirectory && it.name.endsWith("Test") }
            .map { it.name }
            .toSortedSet()

        assertEquals(
            onDisk.toList(),
            TEST_TREES.toSortedSet().toList(),
            "the scan's list of test source sets and the ones on disk have to be the same list",
        )
    }

    @Test
    fun no_test_function_is_missing_its_annotation() {
        val offenders = testSources.flatMap { file ->
            // Comments blanked, not removed, so a line number still points at
            // the line it came from. Without this the scan reads its own KDoc:
            // the first run reported the example in [SENTENCE_NAMED]'s comment
            // below as an unannotated test, which is this repository's oldest
            // lesson about gates — one that matches prose is not a gate.
            val lines = file.readText().blankComments().lines()
            lines.mapIndexedNotNull { index, line ->
                val name = SENTENCE_NAMED.find(line)?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                // The annotation may sit on the line above, or on the same
                // line ahead of `fun`, and KDoc or blank lines may come
                // between the annotation and the function only if the
                // annotation is above them — so the search walks back over
                // comment and blank lines and stops at the first thing that
                // is neither.
                if (isAnnotated(lines, index)) null else "${file.name}:${index + 1} $name"
            }
        }

        assertEquals(
            emptyList(),
            offenders,
            """
            A test function without @Test compiles, reads as covered, and runs
            never. Annotate it, or rename it to camelCase if it is a helper.
            """.trimIndent(),
        )
    }

    /**
     * Whether the function declared at [index] carries `@Test` — on its own
     * line, on the same line, or above the KDoc that introduces it.
     */
    private fun isAnnotated(lines: List<String>, index: Int): Boolean {
        if ("@Test" in lines[index]) return true
        var i = index - 1
        while (i >= 0) {
            val t = lines[i].trim()
            when {
                t.startsWith("@Test") -> return true
                // Other annotations, KDoc, block comments, line comments and
                // blank lines all sit legitimately between `@Test` and `fun`.
                t.isEmpty() || t.startsWith("@") || t.startsWith("*") ||
                    t.startsWith("/*") || t.startsWith("//") || t.endsWith("*/") -> i -= 1
                else -> return false
            }
        }
        return false
    }

    /**
     * Comment text replaced by spaces, line by line, so that offsets and line
     * numbers are untouched and nothing a comment says can be matched as code.
     *
     * Block comments nest in Kotlin, so the depth is counted rather than
     * searched for a closing delimiter.
     */
    private fun String.blankComments(): String {
        val out = StringBuilder(length)
        var depth = 0
        var line = false
        var i = 0
        while (i < length) {
            val c = this[i]
            val two = if (i + 1 < length) substring(i, i + 2) else ""
            when {
                c == '\n' -> { line = false; out.append(c); i += 1 }
                line -> { out.append(' '); i += 1 }
                two == "/*" -> { depth += 1; out.append("  "); i += 2 }
                depth > 0 && two == "*/" -> { depth -= 1; out.append("  "); i += 2 }
                depth > 0 -> { out.append(' '); i += 1 }
                two == "//" -> { line = true; out.append("  "); i += 2 }
                else -> { out.append(c); i += 1 }
            }
        }
        return out.toString()
    }

    private companion object {
        /** The source-set directory names that hold tests in this build. */
        val TEST_TREES = listOf("commonTest", "jvmTest", "iosTest", "androidDeviceTest", "androidTest")

        /**
         * `fun some_thing_happens(` — a function whose name is a sentence.
         *
         * Requires at least one underscore between name characters, which is
         * what separates a test's name from a helper's camelCase one. The
         * receiver form (`fun TestScope.viewModel(`) cannot match, because a
         * dot is not in the character class.
         */
        val SENTENCE_NAMED = Regex("""\bfun\s+([a-z][a-z0-9]*(?:_[a-z0-9]+)+)\s*\(""")
    }
}
