package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.model.DebugDevice
import dev.claudefleet.mobile.model.DebugDeviceList
import dev.claudefleet.mobile.model.DeviceOutput
import dev.claudefleet.mobile.net.MAX_JSON_DEPTH
import dev.claudefleet.mobile.net.json
import dev.claudefleet.mobile.net.nestsWithin
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * A session's own `debug_devices` calls, as its conversation shows them: the
 * fleet MCP server's tool (`mcp__claude-fleet__debug_devices`), with the
 * action and the device in the row, and its answer read back into the shapes
 * the Debug devices sheet draws. Plain data in and out, for `DeviceCallsTest`;
 * the drawing is `DeviceCallDetail` in ConversationTools.kt.
 */

/**
 * The fleet's `debug_devices` tool, under whatever name the session's MCP
 * config gave the fleet's server: `mcp__claude-fleet__debug_devices` as the
 * hub provisions it, `mcp__fleet__debug_devices` from an older config.
 */
internal fun isDeviceTool(name: String): Boolean =
    name.startsWith("mcp__") && name.endsWith("__debug_devices") && name.length > "mcp____debug_devices".length

/** What a device call asked: its `action` and the `device` it named, as far as either is known. */
internal data class DeviceCall(val action: String?, val device: String?) {
    /** "screenshot · pixel", "list"; null when neither is known. */
    val target: String? get() = listOfNotNull(action, device).joinToString(" · ").ifBlank { null }
}

/** The call from its input (`session_tool_detail`'s pretty JSON). */
internal fun deviceCallOf(input: String): DeviceCall = DeviceCall(inputString(input, "action"), inputString(input, "device"))

private val SUMMARY_ACTION = Regex(""""action"\s*:\s*"([a-z_]+)"""")
private val SUMMARY_DEVICE = Regex(""""device"\s*:\s*"((?:[^"\\]|\\.)*)"""")

/**
 * The call from the hub's one-liner, which for this tool is its input as one
 * line of JSON (`mcp__claude-fleet__debug_devices({"action":"claim",…})`),
 * cut at the hub's cap — so read with patterns, not a parser.
 */
internal fun deviceCallFromSummary(summary: String): DeviceCall = DeviceCall(
    SUMMARY_ACTION.find(summary)?.groupValues?.get(1),
    SUMMARY_DEVICE.find(summary)?.groupValues?.get(1)?.replace("\\\"", "\"")?.takeIf { it.isNotBlank() },
)

/** One host of a `scan` answer. */
@Serializable
internal data class ScannedHost(
    val host: String = "",
    val ok: Boolean = true,
    val error: String? = null,
    val found: Int = 0,
)

/** A device call's answer, read into what it is. */
internal sealed interface DeviceAnswer {
    /** claim, release, configure, shutdown: the device as it stands after. */
    data class One(val device: DebugDevice) : DeviceAnswer

    /** list. */
    data class Many(val list: DebugDeviceList) : DeviceAnswer

    /** run, logs, install. */
    data class Output(val output: DeviceOutput) : DeviceAnswer

    /** scan. */
    data class Scanned(val hosts: List<ScannedHost>) : DeviceAnswer

    /** boot: the hub's word for the new state. */
    data class State(val state: String) : DeviceAnswer

    /** Anything else — a screenshot's line, a refusal, an answer cut at the cap. */
    data class Text(val text: String) : DeviceAnswer
}

/**
 * [result] (a tool result's text) read by its shape: `devices` is a list,
 * `exit_code` / `output` a command's output, `hosts` alone a scan, an `id` a
 * device, a lone `state` a boot. Text that is not JSON — or is an error — stays text.
 */
internal fun deviceAnswerOf(result: String?, isError: Boolean = false): DeviceAnswer? {
    if (result == null) return null
    if (isError) return DeviceAnswer.Text(result)
    val trimmed = result.trim()
    if (!trimmed.startsWith("{") || !nestsWithin(trimmed, MAX_JSON_DEPTH)) return DeviceAnswer.Text(result)
    return try {
        val o = json.parseToJsonElement(trimmed) as? JsonObject ?: return DeviceAnswer.Text(result)
        when {
            o["devices"] is JsonArray -> DeviceAnswer.Many(json.decodeFromJsonElement(DebugDeviceList.serializer(), o))
            "exit_code" in o || "output" in o -> DeviceAnswer.Output(json.decodeFromJsonElement(DeviceOutput.serializer(), o))
            o["hosts"] is JsonArray -> DeviceAnswer.Scanned(
                json.decodeFromJsonElement(ScanAnswer.serializer(), o).hosts,
            )
            "id" in o -> DeviceAnswer.One(json.decodeFromJsonElement(DebugDevice.serializer(), o))
            o.keys == setOf("state") && (o["state"] as? JsonPrimitive)?.isString == true ->
                DeviceAnswer.State((o["state"] as JsonPrimitive).content)
            else -> DeviceAnswer.Text(result)
        }
    } catch (_: IllegalArgumentException) {
        // Not JSON after all, or not the shape it looked like (a SerializationException is one).
        DeviceAnswer.Text(result)
    }
}

@Serializable
private data class ScanAnswer(@SerialName("hosts") val hosts: List<ScannedHost> = emptyList())

/** "mac: 2 found", "oci-arm: adb not found". */
internal fun scannedLine(h: ScannedHost): String =
    if (!h.ok || !h.error.isNullOrBlank()) "${h.host}: ${h.error ?: "failed"}" else "${h.host}: ${h.found} found"

/** "Exit 0", "Exit 1 · cut at the hub's cap". */
internal fun outputLine(o: DeviceOutput): String =
    "Exit ${o.exitCode}" + if (o.truncated) " · cut at the hub's cap" else ""

/** "N more images not shown" (one: "1 more image not shown"); null when none were left out. */
internal fun imagesOmittedLine(n: Int): String? = when {
    n <= 0 -> null
    n == 1 -> "1 more image not shown"
    else -> "$n more images not shown"
}

/** A device's state and line together, as the Debug devices sheet writes them. */
internal fun deviceSummary(d: DebugDevice, nowSeconds: Long): String = deviceState(d) + " · " + deviceLine(d, nowSeconds)
