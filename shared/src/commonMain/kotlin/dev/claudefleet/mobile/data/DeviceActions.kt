package dev.claudefleet.mobile.data

import dev.claudefleet.mobile.model.DebugDevice
import dev.claudefleet.mobile.model.DebugDeviceList
import dev.claudefleet.mobile.model.DeviceOutput

/**
 * The calls the Debug devices sheet may make (claude-fleet `debug_devices`).
 * Its own interface, as [RoutineActions] is, so the sheet gets only what it
 * draws. Install, run and screenshot stay on the desktop.
 */
interface DeviceActions {
    suspend fun list(refresh: Boolean): DebugDeviceList

    suspend fun scan()

    suspend fun claim(deviceId: Long): DebugDevice

    suspend fun release(deviceId: Long): DebugDevice

    /** Answers the hub's word for the new state. */
    suspend fun boot(deviceId: Long): String

    suspend fun shutdown(deviceId: Long): DebugDevice

    suspend fun logs(deviceId: Long, lines: Int): DeviceOutput
}

/** [DeviceActions] against the paired hub, through [AppSession.withClient]. */
class HubDeviceActions(private val session: AppSession) : DeviceActions {
    override suspend fun list(refresh: Boolean): DebugDeviceList = session.withClient { it.debugDevices(refresh) }

    override suspend fun scan() = session.withClient { it.scanDebugDevices() }

    override suspend fun claim(deviceId: Long): DebugDevice = session.withClient { it.claimDebugDevice(deviceId, note = "from the phone") }

    override suspend fun release(deviceId: Long): DebugDevice = session.withClient { it.releaseDebugDevice(deviceId) }

    override suspend fun boot(deviceId: Long): String = session.withClient { it.bootDebugDevice(deviceId) }

    override suspend fun shutdown(deviceId: Long): DebugDevice = session.withClient { it.shutdownDebugDevice(deviceId) }

    override suspend fun logs(deviceId: Long, lines: Int): DeviceOutput = session.withClient { it.debugDeviceLogs(deviceId, lines) }
}
