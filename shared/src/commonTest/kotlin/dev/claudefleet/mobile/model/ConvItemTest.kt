package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The hub's `ConvItem` enum has two variants today. The day it grows a third,
 * every phone already in a pocket must degrade to showing "something I don't
 * understand" — not throw on the conversation screen.
 *
 * That is the same promise `ignoreUnknownKeys` makes for a new *field*; these
 * tests make it hold for a new *variant* too.
 */
class ConvItemTest {

    @Test
    fun a_known_kind_still_parses_as_its_own_type() {
        val parsed = json.decodeFromString(
            Conversation.serializer(),
            """{"turns":[{"prompt":"hi","items":[
                 {"kind":"text","text":"hello"},
                 {"kind":"tool","summary":"Read(a.kt)","error":false}
               ]}]}""",
        )
        val items = parsed.turns.single().items
        assertEquals(ConvItem.Text("hello"), items[0])
        assertEquals(ConvItem.Tool("Read(a.kt)", error = false), items[1])
    }

    /** The one the ledger asked for: a third, invented kind must not throw. */
    @Test
    fun an_unknown_kind_degrades_instead_of_throwing() {
        val parsed = json.decodeFromString(
            Conversation.serializer(),
            """{"turns":[{"items":[{"kind":"thinking","text":"...","tokens":41}]}]}""",
        )
        val item = parsed.turns.single().items.single()
        assertIs<ConvItem.Unsupported>(item)
        assertEquals("thinking", item.kind)
    }

    /** A new variant among known ones must not take the known ones down with it. */
    @Test
    fun an_unknown_kind_beside_known_ones_leaves_them_intact() {
        val parsed = json.decodeFromString(
            Conversation.serializer(),
            """{"turns":[{"items":[
                 {"kind":"text","text":"before"},
                 {"kind":"image","url":"https://example.com/a.png"},
                 {"kind":"tool","summary":"Bash(ls)","error":true}
               ]}]}""",
        )
        val items = parsed.turns.single().items
        assertEquals(3, items.size)
        assertEquals(ConvItem.Text("before"), items[0])
        assertEquals(ConvItem.Unsupported("image"), items[1])
        assertEquals(ConvItem.Tool("Bash(ls)", error = true), items[2])
    }

    /** An item with no `kind` at all is malformed, not unknown — same treatment. */
    @Test
    fun an_item_without_a_kind_degrades_too() {
        val parsed = json.decodeFromString(
            Conversation.serializer(),
            """{"turns":[{"items":[{"text":"no discriminator"}]}]}""",
        )
        assertIs<ConvItem.Unsupported>(parsed.turns.single().items.single())
    }

    /**
     * The fallback must not depend on the app's own `Json` being lenient: a
     * caller that builds a strict one still gets a degrade, not a throw.
     */
    @Test
    fun the_fallback_does_not_rely_on_ignore_unknown_keys() {
        val strict = kotlinx.serialization.json.Json { ignoreUnknownKeys = false }
        val parsed = strict.decodeFromString(
            ConvItem.serializer(),
            """{"kind":"thinking","text":"..."}""",
        )
        assertEquals(ConvItem.Unsupported("thinking"), parsed)
    }

    /** An unsupported item still has something a screen can put on the page. */
    @Test
    fun an_unsupported_item_is_displayable() {
        assertTrue(ConvItem.Unsupported("thinking").label.isNotBlank())
    }
}
