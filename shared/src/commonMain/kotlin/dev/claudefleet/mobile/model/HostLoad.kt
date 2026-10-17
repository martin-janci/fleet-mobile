package dev.claudefleet.mobile.model

import kotlin.math.roundToInt

/*
 * Host health on the phone (mission control hardening, claude-fleet #913 and
 * #914): CPU, memory and disk of a host, and whether that is trouble. A port
 * of the desktop's `src/lib/host_health.ts`, rule for rule, so the two say
 * the same thing about the same host.
 *
 * The numbers are the host row's health sample (`list_hosts`, refreshed by
 * every `host:pinged`). A sample older than [HEALTH_SAMPLE_FRESH_SECS] says
 * nothing about now: its numbers still show, but no trouble is judged on
 * them.
 */

/** ok, warn, crit: how a meter is tinted. */
enum class LoadLevel { OK, WARN, CRIT }

/** One host's numbers and whether they are trouble. */
data class HostLoad(
    val alias: String,
    val reachable: Boolean,
    /** The sample is recent enough to judge. */
    val fresh: Boolean,
    val cpu: Int?,
    val mem: Int?,
    /** Memory pressure 0–100, when the host reports one. */
    val pressure: Int?,
    val disk: Int?,
    /** When it was sampled, unix seconds. */
    val at: Long?,
    /** What is wrong, in a few words ("mem 94%", "offline"); null when fine. */
    val trouble: String?,
    val level: LoadLevel,
    val cpuLevel: LoadLevel,
    val memLevel: LoadLevel,
    val diskLevel: LoadLevel,
)

/** `health.disk_low_pct`, `health.mem_low_pct` (available), `health.cpu_busy_pct`: the hub's defaults. */
data class LoadLimits(val diskLowPct: Int = 90, val memLowPct: Int = 10, val cpuBusyPct: Int = 90)

/** The same value as claude-fleet's `health::HEALTH_SAMPLE_FRESH_SECS`. */
const val HEALTH_SAMPLE_FRESH_SECS: Long = 3600

/** The host's health sample was taken within [HEALTH_SAMPLE_FRESH_SECS]. */
fun healthSampleFresh(h: HostRow, nowSeconds: Long): Boolean =
    h.healthAt != null && nowSeconds - h.healthAt <= HEALTH_SAMPLE_FRESH_SECS

private fun pct(v: Double?): Int? =
    v?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)?.roundToInt()

/** Used memory as a whole percent of physical, or null when either number is unknown. */
fun memUsedPct(h: HostRow): Int? {
    val avail = h.memAvailKb ?: return null
    val total = h.memTotalKb?.takeIf { it > 0 } ?: return null
    return (100.0 - avail * 100.0 / total).coerceIn(0.0, 100.0).roundToInt()
}

/** Used percent of `$HOME`, or null when either number is unknown. */
fun diskUsedPct(h: HostRow): Int? {
    val free = h.diskHomeFreeKb ?: return null
    val total = h.diskHomeTotalKb?.takeIf { it > 0 } ?: return null
    return ((total - free) * 100.0 / total).coerceIn(0.0, 100.0).roundToInt()
}

private fun level(v: Int?, warn: Int, crit: Int): LoadLevel = when {
    v == null -> LoadLevel.OK
    v >= crit -> LoadLevel.CRIT
    v >= warn -> LoadLevel.WARN
    else -> LoadLevel.OK
}

/** One host's numbers and whether they are trouble. `local` is never offline: the hub runs there. */
fun hostLoad(h: HostRow, nowSeconds: Long, limits: LoadLimits = LoadLimits()): HostLoad {
    val fresh = healthSampleFresh(h, nowSeconds)
    val cpu = pct(h.cpuPct)
    val mem = memUsedPct(h)
    val disk = diskUsedPct(h)
    val memHigh = 100 - limits.memLowPct
    val cpuLevel = level(cpu, minOf(75, limits.cpuBusyPct), limits.cpuBusyPct)
    val memLevel = if (fresh && h.memLow) LoadLevel.CRIT else level(mem, minOf(85, memHigh), memHigh)
    val diskLevel = level(disk, limits.diskLowPct, maxOf(limits.diskLowPct, 95))
    val trouble = when {
        !h.reachable && h.alias != LOCAL_HOST -> "offline"
        fresh && (h.memLow || (mem != null && mem >= memHigh)) -> "mem ${mem?.toString() ?: "—"}%"
        fresh && h.cpuSaturated -> "cpu ${cpu?.toString() ?: "—"}%"
        fresh && disk != null && disk >= limits.diskLowPct -> "disk $disk%"
        else -> null
    }
    val worst = when {
        trouble != null -> LoadLevel.CRIT
        fresh && LoadLevel.WARN in listOf(cpuLevel, memLevel, diskLevel) -> LoadLevel.WARN
        else -> LoadLevel.OK
    }
    return HostLoad(
        alias = h.alias,
        reachable = h.reachable || h.alias == LOCAL_HOST,
        fresh = fresh,
        cpu = cpu,
        mem = mem,
        pressure = pct(h.memPressure),
        disk = disk,
        at = h.healthAt,
        trouble = trouble,
        level = worst,
        cpuLevel = if (fresh) cpuLevel else LoadLevel.OK,
        memLevel = if (fresh) memLevel else LoadLevel.OK,
        diskLevel = if (fresh) diskLevel else LoadLevel.OK,
    )
}

/** "CPU 18% · Mem 55% · Disk 40%": the parts the host answered; "offline" or "not sampled yet" when none. */
fun loadParts(l: HostLoad): List<String> {
    if (!l.reachable) return listOf("offline")
    val parts = listOfNotNull(
        l.cpu?.let { "CPU $it%" },
        l.mem?.let { "Mem $it%" },
        l.disk?.let { "Disk $it%" },
    )
    return parts.ifEmpty { listOf("not sampled yet") }
}

/**
 * What a host's trouble means for its sessions, one sentence: what the
 * session screen says above the conversation when its host is in trouble.
 * Null when the host is fine.
 */
fun troubleSentence(l: HostLoad): String? {
    val t = l.trouble ?: return null
    return when {
        t == "offline" -> "${l.alias} does not answer. Sessions there show their last state."
        t.startsWith("mem") -> "${l.alias} is low on memory ($t). Agents there may be killed; Fleet waits for memory before restoring them."
        t.startsWith("cpu") -> "${l.alias} is busy ($t). Agents there may be slow."
        else -> "${l.alias} is low on disk ($t). Transcripts and worktrees there may fail to write."
    }
}

/** "checked 20s ago", "checked 3m ago"; "not sampled yet" when never. */
fun checkedAgo(at: Long?, nowSeconds: Long): String {
    if (at == null) return "not sampled yet"
    val s = maxOf(0L, nowSeconds - at)
    return when {
        s < 60 -> "checked ${s}s ago"
        s < 3600 -> "checked ${s / 60}m ago"
        else -> "checked ${s / 3600}h ago"
    }
}

/** The machine the hub itself runs on. */
private const val LOCAL_HOST = "local"
