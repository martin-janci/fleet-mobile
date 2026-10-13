package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A test phone, emulator or simulator on one of the fleet's hosts, as the
 * hub's `debug_devices` answers it (claude-fleet contract revision 10,
 * `service/debug_devices`). Not a person's own phone: those are the paired
 * devices under People & devices.
 */
@Serializable
data class DebugDevice(
    val id: Long,
    /** The label a person set, else the device's own name. */
    val title: String = "",
    /** The host it is attached to; every command runs there. */
    val host: String = "",
    /** android | ios */
    val platform: String = "",
    /** physical | emulator | simulator */
    val kind: String = "",
    val model: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    /** online | booted (ready) · offline | unauthorized | shutdown | missing */
    val state: String = "",
    val ready: Boolean = false,
    /** Sessions on other hosts of the same organisation may use it too. */
    val shared: Boolean = false,
    /** Who holds it: `host:<alias>#<session>` for a session, `client:<name>` for a device. */
    @SerialName("claimed_by") val claimedBy: String? = null,
    @SerialName("claim_note") val claimNote: String? = null,
    @SerialName("claimed_until") val claimedUntil: Long? = null,
    /** Who waits for the claim, first in line first; each gets it in turn. */
    val queue: List<QueuedHolder> = emptyList(),
    /** How many wait: [queue]'s length. */
    val waiting: Int = 0,
    /** This device's own place in [queue] (1: next), when it waits. */
    @SerialName("queue_place") val queuePlace: Int? = null,
    @SerialName("last_seen_at") val lastSeenAt: Long = 0,
    /** Stable per host: an adb serial, `avd:<name>`, or a UDID. What a reference may name. */
    val key: String = "",
    /** What the host's tools address it by now; absent while a stopped emulator has none. */
    val serial: String? = null,
    /** The device's own name, before any label. */
    val name: String = "",
    /** The label a person set, when one is. */
    val label: String? = null,
)

/** One holder waiting in a device's line (`claim { queue: true }`). */
@Serializable
data class QueuedHolder(
    val holder: String,
    val note: String? = null,
    @SerialName("queued_at") val queuedAt: Long = 0,
)

/** One host's last scan: when, and what went wrong if it did. */
@Serializable
data class DeviceHostScan(
    val host: String,
    @SerialName("scanned_at") val scannedAt: Long? = null,
    val error: String? = null,
)

/** `debug_devices { list }`. */
@Serializable
data class DebugDeviceList(
    val devices: List<DebugDevice> = emptyList(),
    val hosts: List<DeviceHostScan> = emptyList(),
)

/** What a device command printed (`logs`, `run`): the output, cut at the hub's cap. */
@Serializable
data class DeviceOutput(
    @SerialName("exit_code") val exitCode: Int = 0,
    val output: String = "",
    val truncated: Boolean = false,
)

/**
 * One `debug_devices { screenshot }`: the hub's line ("Pixel on mac: 81234
 * bytes") and the image, base64 as it came. Decoded only where it is drawn.
 */
data class DeviceScreenshot(val caption: String, val mime: String, val data: String)

/**
 * The screenshot in a `debug_devices { screenshot }` result: MCP content of a
 * text block and an image block (`{type: "image", data, mimeType}`). Throws
 * when the result holds no image, which [dev.claudefleet.mobile.net.HubClient.call]
 * reports as a hub that answered something unintelligible.
 */
fun screenshotOf(result: JsonElement): DeviceScreenshot {
    val blocks = ((result as? JsonObject)?.get("content") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val image = blocks.firstOrNull { it.str("type") == "image" && !it.str("data").isNullOrEmpty() }
    val data = image?.str("data") ?: throw IllegalStateException("the screenshot came back without an image")
    val caption = blocks.firstOrNull { it.str("type") == "text" }?.str("text").orEmpty()
    return DeviceScreenshot(caption, image.str("mimeType") ?: image.str("mime_type") ?: "image/png", data)
}

/**
 * The device a reference names among [devices], the way the hub's
 * `debug_devices::resolve` reads one: `#12` is that id; otherwise a label,
 * name, serial or key (any case), optionally as `host/that`, and a bare
 * number is an id when it names nothing else. Null when nothing matches or
 * the reference is ambiguous — the hub would refuse it too.
 */
fun findDevice(devices: List<DebugDevice>, reference: String): DebugDevice? {
    val r = reference.trim()
    if (r.isEmpty()) return null
    val byId = r.removePrefix("#").toLongOrNull()?.let { id -> devices.firstOrNull { it.id == id } }
    if (byId != null && r.startsWith("#")) return byId
    val slash = r.indexOf('/')
    val (host, what) = if (slash > 0 && devices.any { it.host == r.substring(0, slash) }) {
        r.substring(0, slash) to r.substring(slash + 1)
    } else {
        null to r
    }
    fun eq(a: String?) = a != null && a.equals(what, ignoreCase = true)
    val hits = devices
        .filter { host == null || it.host == host }
        .filter { eq(it.label) || eq(it.name) || eq(it.serial) || eq(it.key) || eq(it.title) }
    if (byId != null) return byId.takeIf { hits.all { h -> h.id == byId.id } }
    return hits.singleOrNull()
}

/** The holder a session's claims are under: `host:<alias>#<session id>`, as the hub writes it. */
fun sessionHolder(hostAlias: String, sessionId: Long): String = "host:$hostAlias#$sessionId"

/**
 * The devices the session [holder] names holds, or waits in line for: those
 * it holds first, then the ones it waits for. A waiter is matched in the
 * device's [DebugDevice.queue]; [DebugDevice.queuePlace] is the asker's (the
 * phone's) own place and says nothing about the session.
 */
fun devicesOfHolder(devices: List<DebugDevice>, holder: String): List<DebugDevice> =
    devices.filter { it.claimedBy == holder } + devices.filter { d -> d.claimedBy != holder && d.queue.any { it.holder == holder } }

/** [holder]'s place in [d]'s line (1: next), or null when it does not wait there. */
fun placeOf(d: DebugDevice, holder: String): Int? = d.queue.indexOfFirst { it.holder == holder }.takeIf { it >= 0 }?.plus(1)
