package dev.claudefleet.mobile.ui.theme

import dev.claudefleet.mobile.host.Repo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `OrbitTokens` against `docs/design/tokens.json`, the copy of the design
 * manual's tokens (redesign step 0.10). Both directions: a token the manual
 * has and the phone lacks fails, and so does one the phone invented.
 *
 * A JVM test because it reads the source tree; the literals it checks are in
 * commonMain, so what passes here is what Android and iOS draw.
 */
class OrbitTokensDriftTest {

    private val snapshot: JsonObject by lazy {
        Json.parseToJsonElement(Repo.file("docs/design/tokens.json").readText()).jsonObject
    }

    private fun tokens(group: String) = snapshot[group]!!.jsonObject["tokens"]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    /** `{accent}` → the accent's own value in the same theme, however deep the chain. */
    private fun resolve(name: String, theme: String, seen: Set<String> = emptySet()): String {
        if (name in seen) fail("colour token $name refers to itself through $seen")
        val token = tokens("color").firstOrNull { it.str("name") == name } ?: fail("{$name} names no colour token")
        val value = token["value"]!!.jsonObject.str(theme)
        val ref = Regex("""^\{(.+)}$""").find(value)?.groupValues?.get(1)
        return if (ref != null) resolve(ref, theme, seen + name) else value
    }

    /** `#rrggbb` or `rgba(r,g,b,a)` as ARGB, alpha rounded to the nearest of 255 like the literals. */
    private fun argb(css: String): Long {
        Regex("""^#([0-9a-fA-F]{6})$""").find(css)?.let { return 0xFF000000L or it.groupValues[1].toLong(16) }
        val m = Regex("""^rgba\((\d+),(\d+),(\d+),([\d.]+)\)$""").find(css.replace(" ", ""))
            ?: fail("unreadable colour $css")
        val (r, g, b, a) = m.destructured
        val alpha = (a.toDouble() * 255).roundToLong()
        return (alpha shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    private fun px(value: String): Float {
        assertTrue(value.endsWith("px"), "expected a px value, got $value")
        return value.removeSuffix("px").toFloat()
    }

    private fun ms(value: String): Long = when {
        value.endsWith("ms") -> value.removeSuffix("ms").toLong()
        value.endsWith("s") -> (value.removeSuffix("s").toDouble() * 1000).roundToLong()
        else -> fail("expected a duration, got $value")
    }

    private fun hex(v: Long) = "0x" + v.toString(16).uppercase().padStart(8, '0')

    @Test
    fun every_colour_matches_the_snapshot_in_both_themes() {
        val expected = tokens("color").map { it.str("name") }
        assertEquals(expected, OrbitTokens.colors.map { it.name }, "the phone's colour tokens, in the manual's order")
        for (token in OrbitTokens.colors) {
            for ((theme, actual) in listOf("dark" to token.dark, "light" to token.light)) {
                val want = argb(resolve(token.name, theme))
                assertEquals(hex(want), hex(actual), "${token.name} ($theme)")
            }
        }
    }

    @Test
    fun spacing_and_radius_match_the_snapshot() {
        for ((group, actual) in listOf("spacing" to OrbitTokens.spacing, "radius" to OrbitTokens.radius)) {
            val expected = tokens(group).associate { it.str("name") to px(it.str("value")) }
            assertEquals(expected, actual, group)
        }
    }

    @Test
    fun the_size_group_matches_the_snapshot() {
        val expected = tokens("size").associate { it.str("name") to px(it.str("value")) }
        assertEquals(expected, OrbitTokens.size, "size")
    }

    @Test
    fun every_group_of_the_snapshot_is_compared() {
        // A group added to the manual (as `size` was, review r10) must get a
        // comparison here; otherwise the copy grows and the phone never hears.
        val compared = setOf("color", "type", "spacing", "radius", "duration", "size")
        // `shadow` is desktop-only box-shadow CSS; Compose draws elevation.
        val notDrawn = setOf("name", "version", "meta", "shadow")
        assertEquals(emptySet(), snapshot.keys - compared - notDrawn, "groups in tokens.json nobody compares")
    }

    @Test
    fun the_copy_is_held_to_claude_fleet_by_a_scheduled_check() {
        // The tests above hold the literals to the copy; only this workflow
        // holds the copy to claude-fleet main. Without the schedule nothing
        // runs when the other repo moves, which is how the copy fell behind.
        val wf = Repo.file(".github/workflows/design-tokens.yml").readText()
        assertTrue(Regex("""(?m)^\s+schedule:""").containsMatchIn(wf), "design-tokens.yml must run on a schedule")
        assertTrue("bash scripts/check-design-tokens.sh" in wf, "design-tokens.yml must run the check script")
        val script = Repo.file("scripts/check-design-tokens.sh").readText()
        assertTrue("cmp -s" in script, "the check compares byte for byte")
        assertTrue("docs/design/tokens.json" in script)
    }

    @Test
    fun the_phone_tokens_are_there() {
        // Named because 14.1's kit is built on them; a snapshot that dropped one
        // would otherwise pass the equality above by dropping it here too.
        for (name in listOf("touch-min", "phone-gutter", "phone-bar-h", "tab-bar-h", "phone-row-min")) {
            assertTrue(name in OrbitTokens.spacing, "missing $name")
        }
        for (name in listOf("radius-sheet", "radius-phone-card")) {
            assertTrue(name in OrbitTokens.radius, "missing $name")
        }
        assertEquals(48f, OrbitTokens.spacing("touch-min"), "touch-min is Material's 48 dp")
    }

    @Test
    fun type_matches_the_snapshot() {
        val families = snapshot["type"]!!.jsonObject
        val expected = families["groups"]!!.jsonArray.flatMap { group ->
            val g = group.jsonObject
            val mono = g.str("family") == "mono"
            g["styles"]!!.jsonArray.map { s ->
                val o = s.jsonObject
                OrbitTokens.TypeToken(
                    name = o.str("name"),
                    size = px(o.str("fontSize")).roundToInt(),
                    lineHeight = px(o.str("lineHeight")).roundToInt(),
                    weight = o["fontWeight"]!!.jsonPrimitive.int,
                    mono = mono,
                )
            }
        }
        assertEquals(expected, OrbitTokens.type)
    }

    @Test
    fun durations_match_the_snapshot() {
        val expected = tokens("duration").associate { it.str("name") to ms(it.str("value")) }
        assertEquals(expected, OrbitTokens.durationMs)
        assertEquals(400L, OrbitTokens.durationMs["loader-delay"], "no loader before 400 ms")
    }
}
