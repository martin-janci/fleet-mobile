// `@JsonIgnoreUnknownKeys` is still experimental in kotlinx.serialization; the
// opt-in is here rather than on the class so the annotation below reads as the
// rule it is.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.claudefleet.mobile.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys

/**
 * What this app asks `fleet_health` about the hub itself.
 *
 * The tool answers a whole fleet roll-up — sessions by status, ghosts, usage
 * per host, tunnels, trackers — and a phone draws none of it: the session list
 * is its own call, and the roll-up would be a second, staler copy of it. So
 * this models the hub rather than the fleet: is its store open, what
 * version is it running, and is its automation paused.
 *
 * [version] is the HUB's, never this app's. The two are separate programs on
 * separate release trains — a phone from the store, a hub the operator
 * upgrades — and the Settings screen now names both so that "0.4.5" on screen
 * is never a guess about which.
 *
 * [JsonIgnoreUnknownKeys] because every other field of that payload is one we
 * deliberately do not read, and every field carries a default so an older hub
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
    /**
     * `automation.paused` (claude-fleet redesign 8.1): the hub's loops that act
     * on their own stand still. Sent only while true; an older hub never sends it.
     */
    @SerialName("automation_paused") val automationPaused: Boolean = false,
    /**
     * The hub's built-in routines (claude-fleet 8.1's loops: reconcile,
     * missions, tidy, …) and how each last ran, in this hub process. What the
     * desktop's Automation lists as built-in routines (8.4); absent from an
     * older hub.
     */
    val loops: List<LoopHealth> = emptyList(),
    /**
     * Stuck-session playbooks that acted over the last seven days, by stuck
     * kind (`press_enter`, `oom`, …): "Ran 3 times this week" (hub contract
     * 17, gap plan G7.3). Empty from an older hub and on a quiet week alike.
     */
    @SerialName("playbooks_week") val playbooksWeek: Map<String, Int> = emptyMap(),
    /** Jev's health; only its week is read here. Absent for a client the hub keeps it from. */
    val decide: DecideHealth? = null,
)

/** `fleet_health.decide`: the one part this app reads, the last seven days' answers. */
@Serializable
@JsonIgnoreUnknownKeys
data class DecideHealth(val week: DecideWeek? = null)

/** `fleet_health.decide.week` (contract 17): Jev's live proposals and how people met them. */
@Serializable
@JsonIgnoreUnknownKeys
data class DecideWeek(
    val proposals: Int = 0,
    val kept: Int = 0,
    val changed: Int = 0,
    val rejected: Int = 0,
    val ignored: Int = 0,
)

/**
 * The stuck kind a playbook setting acts on (`service::playbooks` on the
 * hub): `playbooks.press_enter` runs on `press_enter`, `playbooks.oom_recreate`
 * on `oom`. Null for any other key.
 */
fun playbookKind(settingKey: String): String? = when (settingKey) {
    "playbooks.press_enter" -> "press_enter"
    "playbooks.oom_recreate" -> "oom"
    else -> null
}

/**
 * A playbook setting's recent activity ("Ran 3 times this week."), from
 * [HubHealth.playbooksWeek]; null when it did not run or the hub does not
 * count, since an older hub and a quiet week read the same.
 */
fun playbookActivity(settingKey: String, week: Map<String, Int>): String? {
    val n = playbookKind(settingKey)?.let { week[it] } ?: return null
    return when {
        n <= 0 -> null
        n == 1 -> "Ran once this week."
        else -> "Ran $n times this week."
    }
}

/** Jev's week in one line: "This week: 7 proposals · 6 kept · 4 changed". Zero follow-ups are left out. */
fun DecideWeek.line(): String {
    if (proposals == 0 && kept + changed + rejected + ignored == 0) return "This week: no proposals"
    val parts = buildList {
        add(if (proposals == 1) "1 proposal" else "$proposals proposals")
        if (kept > 0) add("$kept kept")
        if (changed > 0) add("$changed changed")
        if (rejected > 0) add("$rejected turned down")
        if (ignored > 0) add("$ignored not answered")
    }
    return "This week: " + parts.joinToString(" · ")
}

/** One built-in routine (`service::loops::LoopHealth`). */
@Serializable
@JsonIgnoreUnknownKeys
data class LoopHealth(
    val name: String = "",
    val label: String = "",
    /** Pause all stops it. */
    val pausable: Boolean = false,
    @SerialName("last_run_at") val lastRunAt: Long? = null,
    @SerialName("next_run_at") val nextRunAt: Long? = null,
    /** `ok` | `error` | `paused`; null before its first run in this process. */
    val result: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    val runs: Long = 0,
    val failures: Long = 0,
)
