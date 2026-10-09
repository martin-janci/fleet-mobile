package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * No icon button is shrunk under the 48 dp touch target (`touch-min`, review
 * r11). An outer `Modifier.size(32.dp)` on an IconButton overrides its 48 dp
 * minimum, so the target and TalkBack's focus box shrink with it; the icon
 * inside is what gets small.
 */
class IconButtonTargetTest {

    /** The argument list of the call whose `(` is at [open], balanced. */
    private fun argsAt(src: String, open: Int): String {
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) {
                '(', '{', '[' -> depth++
                ')', '}', ']' -> if (--depth == 0) return src.substring(open + 1, i)
            }
        }
        return src.substring(open + 1)
    }

    /** The argument list with every nested (), {} and [] blanked out. */
    private fun topLevel(args: String): String {
        val out = StringBuilder()
        var depth = 0
        for (c in args) {
            if (c in "({[") depth++
            out.append(if (depth == 0 || (depth == 1 && c in "({[")) c else ' ')
            if (c in ")}]") depth--
        }
        return out.toString()
    }

    @Test
    fun no_icon_button_is_sized_under_the_touch_target() {
        val call = Regex("""\bIconButton\(""")
        val modifier = Regex("""modifier = Modifier\.(?:size|requiredSize|height|width)\((\d+)\.dp\)""")
        val hits = Repo.shipped.asSequence().flatMap { f ->
            val src = f.readText()
            call.findAll(src).mapNotNull { m ->
                val args = argsAt(src, m.range.last)
                // The modifier must be the call's own argument, not one nested in its lambda.
                val own = topLevel(args).indexOf("modifier = Modifier.")
                val size = if (own < 0) null else modifier.find(args.substring(own))?.takeIf { it.range.first == 0 }
                if (size != null && size.groupValues[1].toInt() < 48) "${f.name}:${src.substring(0, m.range.first).count { it == '\n' } + 1}" else null
            }
        }.toList()
        assertEquals(emptyList(), hits)
    }
}
