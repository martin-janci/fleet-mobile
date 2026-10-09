package dev.claudefleet.mobile.host

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The design manual's copy rules on the phone's own strings (review round
 * 14), the counterpart of the desktop's `copy_lint`: the app is Orbit Fleet,
 * a lost session is not a "ghost", and a state reads as one of the six status
 * words (Working, not Running; Done, not Completed). A source scan over the
 * string literals the screens draw.
 */
class CopyRulesTest {

    private val sources: List<File> by lazy {
        // A directory, so not `Repo.file`: that one insists on a regular file.
        File(Repo.root, "shared/src/commonMain/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            .also { assertTrue(it.isNotEmpty(), "found no Kotlin sources under shared/src/commonMain/kotlin") }
    }

    /** Every `"…"` literal in [f] with its line, wire names and comments left out. */
    private fun literals(f: File): List<Pair<Int, String>> = f.readLines().flatMapIndexed { i, line ->
        val code = line.trimStart()
        if (code.startsWith("*") || code.startsWith("//") || code.startsWith("/*")) {
            emptyList<Pair<Int, String>>()
        } else {
            LITERAL.findAll(line.replace(CHAR_QUOTE, "")).map { i + 1 to it.groupValues[1] }.toList()
        }
    }

    private fun offences(rule: Regex): List<String> = sources.flatMap { f ->
        literals(f).filter { (_, s) -> rule.containsMatchIn(s) }.map { (n, s) -> "${f.name}:$n \"$s\"" }
    }

    @Test
    fun the_app_is_called_orbit_fleet() {
        assertEquals(emptyList(), offences(Regex("(?i)claude[- ]fleet")))
    }

    @Test
    fun a_lost_session_is_not_a_ghost() {
        // Lowercase "ghost" is the hub's wire value and the action's name.
        assertEquals(emptyList(), offences(Regex("\\bGhost|\\bghost\\b(?!_)")).filterNot { "\"ghost\"" in it })
    }

    @Test
    fun states_use_the_six_words() {
        assertEquals(emptyList(), offences(Regex("^(Running|Completed)$")))
    }

    private companion object {
        val LITERAL = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"")
        /** `'"'` and `'\\"'`, which would open a literal that is not one. */
        val CHAR_QUOTE = Regex("'\\\\?\"'")
    }
}
