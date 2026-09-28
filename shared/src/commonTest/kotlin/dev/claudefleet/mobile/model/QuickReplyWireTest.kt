package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuickReplyWireTest {

    @Test
    fun the_hub_flag_reads_as_auto_send() {
        val chip = json.decodeFromString(
            QuickReply.serializer(),
            """{"label":"Go","text":"go on","auto_send":true}""",
        )
        assertEquals(true, chip.autoSend)
        assertTrue(chip.sendsOnTap)
        assertFalse(json.decodeFromString(QuickReply.serializer(), """{"label":"a","text":"b","auto_send":false}""").sendsOnTap)
    }

    @Test
    fun a_hub_without_the_flag_keeps_the_old_tap_to_send() {
        val chip = json.decodeFromString(QuickReply.serializer(), """{"label":"Go","text":"go on"}""")
        assertEquals(null, chip.autoSend)
        assertTrue(chip.sendsOnTap)
    }

    @Test
    fun an_unknown_flag_is_left_out_of_a_write_so_the_hub_keeps_its_own() {
        val out = json.encodeToString(QuickReply.serializer(), QuickReply.of("go on"))
        assertFalse("auto_send" in out, out)
        val set = json.encodeToString(QuickReply.serializer(), QuickReply("a", "b", autoSend = false))
        assertTrue("\"auto_send\":false" in set, set)
    }
}
