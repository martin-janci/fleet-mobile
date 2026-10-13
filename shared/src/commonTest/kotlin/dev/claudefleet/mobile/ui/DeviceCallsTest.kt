package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.ConvItem
import dev.claudefleet.mobile.model.DebugDevice
import dev.claudefleet.mobile.model.DeviceOutput
import dev.claudefleet.mobile.model.QueuedHolder
import dev.claudefleet.mobile.model.devicesOfHolder
import dev.claudefleet.mobile.model.findDevice
import dev.claudefleet.mobile.model.placeOf
import dev.claudefleet.mobile.model.screenshotOf
import dev.claudefleet.mobile.model.sessionHolder
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A session's `debug_devices` calls in its conversation, a chat card's device
 * reference, and which devices are a session's own — the pure half.
 */
class DeviceCallsTest {
    private val tool = "mcp__claude-fleet__debug_devices"

    @Test
    fun the_fleet_servers_device_tool_is_known_under_either_name() {
        assertTrue(isDeviceTool(tool))
        assertTrue(isDeviceTool("mcp__fleet__debug_devices"))
        assertFalse(isDeviceTool("debug_devices"))
        assertFalse(isDeviceTool("mcp____debug_devices"))
        assertFalse(isDeviceTool("mcp__claude-fleet__debug_devices_v2"))
        assertEquals("Device", toolVerb(tool))
        assertEquals(ToolKind.Device, toolKind(tool))
        assertEquals("claude-fleet · ask", toolVerb("mcp__claude-fleet__ask"))
    }

    @Test
    fun the_row_says_the_action_and_the_device() {
        val row = ConvItem.Tool(
            summary = """mcp__claude-fleet__debug_devices({"action":"screenshot","device":"mac-mini/bench pixel"})""",
            id = "t1",
            name = tool,
            target = null,
        )
        val line = row.line()
        assertEquals("Device", line.verb)
        assertEquals("screenshot · mac-mini/bench pixel", line.target)
        assertFalse(line.pathLike)
        assertEquals(ToolKind.Device, line.kind)
        // A list names no device; an install's path is not what the row leads with.
        val list = row.copy(summary = """mcp__claude-fleet__debug_devices({"action":"list"})""")
        assertEquals("list", list.line().target)
        val install = row.copy(summary = """mcp__claude-fleet__debug_devices(path=/tmp/app.apk)""", target = "/tmp/app.apk")
        assertEquals("/tmp/app.apk", install.line().target)
    }

    @Test
    fun a_summary_cut_at_the_cap_still_gives_what_it_holds() {
        val cut = deviceCallFromSummary("""mcp__claude-fleet__debug_devices({"action":"claim","device":"Pixel \"9\"","note":"testing the lo…)""")
        assertEquals(DeviceCall("claim", "Pixel \"9\""), cut)
        assertEquals(DeviceCall(null, null), deviceCallFromSummary("mcp__claude-fleet__debug_devices({\"act…)"))
        assertNull(DeviceCall(null, null).target)
    }

    @Test
    fun the_input_names_the_call() {
        assertEquals(DeviceCall("run", "7"), deviceCallOf("{\n  \"action\": \"run\",\n  \"device\": \"7\",\n  \"args\": [\"shell\"]\n}"))
        assertEquals(DeviceCall(null, null), deviceCallOf("not json"))
    }

    @Test
    fun each_answer_reads_as_what_it_is() {
        val one = assertIs<DeviceAnswer.One>(
            deviceAnswerOf("""{"id":3,"title":"Pixel 9","host":"mac","state":"online","ready":true,"claimed_by":"host:mac#4","key":"R5","name":"Pixel 9","shared":false,"first_seen_at":1,"last_seen_at":2}"""),
        )
        assertEquals("host:mac#4", one.device.claimedBy)
        val many = assertIs<DeviceAnswer.Many>(deviceAnswerOf("""{"devices":[{"id":1,"title":"A"}],"hosts":[{"host":"mac","error":"adb not found"}]}"""))
        assertEquals(1, many.list.devices.size)
        assertEquals("adb not found", many.list.hosts.single().error)
        val out = assertIs<DeviceAnswer.Output>(deviceAnswerOf("""{"exit_code":1,"output":"boom","truncated":true}"""))
        assertEquals(DeviceOutput(1, "boom", true), out.output)
        assertEquals("Exit 1 · cut at the hub's cap", outputLine(out.output))
        assertEquals("Exit 0", outputLine(DeviceOutput()))
        val scan = assertIs<DeviceAnswer.Scanned>(
            deviceAnswerOf("""{"hosts":[{"host":"mac","ok":true,"found":2,"tools":["adb"],"bootable":[]},{"host":"oci","ok":false,"error":"no adb","found":0}]}"""),
        )
        assertEquals(listOf("mac: 2 found", "oci: no adb"), scan.hosts.map(::scannedLine))
        assertEquals(DeviceAnswer.State("booting"), deviceAnswerOf("""{"state":"booting"}"""))
    }

    @Test
    fun what_is_not_one_of_those_stays_text() {
        assertEquals(DeviceAnswer.Text("Pixel 9 on mac: 812 bytes"), deviceAnswerOf("Pixel 9 on mac: 812 bytes"))
        // An answer cut at the hub's 8 000 characters is not JSON any more.
        assertEquals(DeviceAnswer.Text("""{"devices":[{"id":1,"tit…"""), deviceAnswerOf("""{"devices":[{"id":1,"tit…"""))
        // A refusal is drawn as the refusal, even when it looks like JSON.
        assertEquals(DeviceAnswer.Text("""{"state":"x"}"""), deviceAnswerOf("""{"state":"x"}""", isError = true))
        assertEquals(DeviceAnswer.Text("""{"what":1}"""), deviceAnswerOf("""{"what":1}"""))
        assertNull(deviceAnswerOf(null))
    }

    @Test
    fun images_left_out_are_counted_in_words() {
        assertNull(imagesOmittedLine(0))
        assertEquals("1 more image not shown", imagesOmittedLine(1))
        assertEquals("2 more images not shown", imagesOmittedLine(2))
    }

    @Test
    fun a_screenshot_result_is_its_line_and_its_image() {
        val result = Json.parseToJsonElement(
            """{"content":[{"type":"text","text":"Pixel on mac: 3 bytes"},{"type":"image","data":"/9j/","mimeType":"image/jpeg"}]}""",
        )
        val shot = screenshotOf(result)
        assertEquals("Pixel on mac: 3 bytes", shot.caption)
        assertEquals("image/jpeg", shot.mime)
        assertEquals("/9j/", shot.data)
    }

    // ─── A card's reference, as the hub's resolve reads one ─────────────

    private val pixel = DebugDevice(id = 12, title = "Bench pixel", host = "mac-mini", key = "R5CT", serial = "R5CT", name = "Pixel 9", label = "Bench pixel")
    private val avd = DebugDevice(id = 3, title = "Pixel_8", host = "mercury", key = "avd:Pixel_8", serial = "emulator-5554", name = "Pixel_8")
    private val twin = DebugDevice(id = 4, title = "Pixel_8", host = "mac-mini", key = "avd:Pixel_8", name = "Pixel_8")
    private val numbered = DebugDevice(id = 5, title = "12", host = "mac-mini", key = "k12", name = "12")

    @Test
    fun a_reference_finds_its_device() {
        val all = listOf(pixel, avd)
        assertEquals(pixel, findDevice(all, "#12"))
        assertEquals(pixel, findDevice(all, "12"))
        assertEquals(pixel, findDevice(all, "mac-mini/bench pixel"))
        assertEquals(pixel, findDevice(all, "  BENCH PIXEL "))
        assertEquals(pixel, findDevice(all, "Pixel 9"))
        assertEquals(pixel, findDevice(all, "r5ct"))
        assertEquals(avd, findDevice(all, "emulator-5554"))
        assertEquals(avd, findDevice(all, "avd:Pixel_8"))
        assertNull(findDevice(all, "mercury/bench pixel"))
        assertNull(findDevice(all, "nothing"))
        assertNull(findDevice(all, " "))
    }

    @Test
    fun an_ambiguous_reference_finds_nothing_as_the_hub_refuses_it() {
        val all = listOf(pixel, avd, twin, numbered)
        assertNull(findDevice(all, "Pixel_8"))
        assertEquals(twin, findDevice(all, "mac-mini/Pixel_8"))
        // `12` is the bench pixel's id and the name of another: only `#12` is the id.
        assertNull(findDevice(all, "12"))
        assertEquals(pixel, findDevice(all, "#12"))
    }

    // ─── A session's own devices ────────────────────────────────────────

    private val holder = sessionHolder("mercury", 42)

    @Test
    fun a_session_holds_under_its_host_and_id() {
        assertEquals("host:mercury#42", holder)
    }

    @Test
    fun the_strip_lists_what_the_session_holds_then_what_it_waits_for() {
        val held = avd.copy(claimedBy = holder, claimedUntil = 1_000 + 12 * 60)
        val waited = pixel.copy(
            claimedBy = "client:Martin's Pixel",
            queue = listOf(QueuedHolder("host:mac#7"), QueuedHolder(holder)),
            waiting = 2,
            // The phone's own place: nothing to do with the session.
            queuePlace = 1,
        )
        val other = twin.copy(claimedBy = "host:mercury#43")
        val nobody = numbered
        // Same host, other session; a different session id that starts the same.
        val prefix = twin.copy(id = 9, claimedBy = "host:mercury#420")
        val mine = devicesOfHolder(listOf(waited, other, nobody, held, prefix), holder)
        assertEquals(listOf(held, waited), mine)
        assertEquals(2, placeOf(waited, holder))
        assertNull(placeOf(held, holder))
        assertEquals("Held · mercury · 12 min left", stripLine(held, holder, nowSeconds = 1_000))
        assertEquals("Waiting · mac-mini · 2nd in line", stripLine(waited, holder, nowSeconds = 1_000))
        val first = waited.copy(queue = listOf(QueuedHolder(holder)))
        assertEquals("Waiting · mac-mini · next in line", stripLine(first, holder, nowSeconds = 1_000))
        // A lapsed claim names no time left.
        assertEquals("Held · mercury", stripLine(held, holder, nowSeconds = 1_000_000))
    }

    @Test
    fun the_queue_is_named_in_order() {
        assertNull(queueNames(pixel))
        val d = pixel.copy(queue = listOf(QueuedHolder("host:mac#7"), QueuedHolder("client:Martin's Pixel")))
        assertEquals("a session on mac, Martin's Pixel", queueNames(d))
    }
}
