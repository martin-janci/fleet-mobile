// `@JsonIgnoreUnknownKeys` is still experimental in kotlinx.serialization; the
// opt-in is here rather than on the class so the annotation below reads as the
// rule it is.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/**
 * The two things this app asks `fleet_health` about the hub itself.
 *
 * The tool answers a whole fleet roll-up — sessions by status, ghosts, usage
 * per host, tunnels, trackers — and a phone draws none of it: the session list
 * is its own call, and the roll-up would be a second, staler copy of it. So
 * this models the hub rather than the fleet: is its store open, and what
 * version is it running.
 *
 * [version] is the HUB's, never this app's. The two are separate programs on
 * separate release trains — a phone from the store, a hub the operator
 * upgrades — and the Settings screen now names both so that "0.4.5" on screen
 * is never a guess about which.
 *
 * [JsonIgnoreUnknownKeys] because every other field of that payload is one we
 * deliberately do not read, and both fields carry a default so an older hub
 * (or one that renames something) reads as "unknown" instead of failing the
 * probe [dev.claudefleet.mobile.data.SessionActions.ping] depends on.
 */
@Serializable
@JsonIgnoreUnknownKeys
data class HubHealth(
    /** The hub's SQLite store is open. False is a hub in trouble, not an unreachable one. */
    @SerialName("db_ready") val dbReady: Boolean = false,
    /** The hub's own version, e.g. `0.4.5`; empty when it did not say. */
    val version: String = "",
)
