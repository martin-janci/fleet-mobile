package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("last_seen_at") val lastSeenAt: Long = 0,
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
