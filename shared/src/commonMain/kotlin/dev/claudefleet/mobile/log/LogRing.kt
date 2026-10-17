package dev.claudefleet.mobile.log

import dev.claudefleet.mobile.epochMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/*
 * This app's own log, kept in memory for the hub's self debug agent
 * (claude-fleet `POST /log`, "Self debug agent", 2026-10-17). Nothing is
 * written to disk and nothing leaves the phone until the hub asks for it
 * ([LogPoller]); the lines are redacted as they come in, before they are held.
 *
 * A line reads `2026-10-17T10:15:40.123Z  ERROR tag: message`: an RFC 3339
 * UTC stamp, two spaces, the level in upper case — the shape the hub windows
 * by.
 */

enum class LogLevel(val word: String) { Debug("DEBUG"), Info("INFO"), Warn("WARN"), Error("ERROR") }

/** One held line: when it was logged, and the line exactly as it would be sent. */
data class LogLine(val atMillis: Long, val text: String)

/**
 * A bounded ring of log lines, oldest first: at most [maxLines] lines and
 * [maxBytes] bytes (UTF-8), the oldest dropped first.
 *
 * Safe from any thread the way the rest of the app shares state: one
 * [MutableStateFlow] of an immutable list, replaced with `update`'s
 * compare-and-set. A log call never suspends and never blocks.
 */
class LogRing(
    private val maxLines: Int = LOG_MAX_LINES,
    private val maxBytes: Int = LOG_MAX_BYTES,
    private val clock: () -> Long = ::epochMillis,
) {
    private class Held(val lines: List<LogLine> = emptyList(), val bytes: Int = 0)

    private val held = MutableStateFlow(Held())

    /**
     * Log [message] at [level] under [tag]. A message of several lines (a
     * stack trace) becomes several lines, each with the same stamp, so every
     * line the hub reads starts with one.
     */
    fun add(level: LogLevel, tag: String, message: String, atMillis: Long = clock()) {
        val stamp = logStamp(atMillis)
        val pieces = message.split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }.ifEmpty { listOf("") }
        val fresh = pieces.take(MAX_PIECES).map { piece ->
            LogLine(atMillis, "$stamp  ${level.word} $tag: ${redactLog(piece)}".take(LOG_LINE_MAX_CHARS))
        }
        append(fresh)
    }

    /** Put back lines kept from an earlier run (a crash's last words), as they were. */
    fun restore(lines: List<LogLine>) = append(lines.map { it.copy(text = it.text.take(LOG_LINE_MAX_CHARS)) })

    /** Every held line, oldest first. */
    fun lines(): List<LogLine> = held.value.lines

    /** The lines logged in the last [minutes] before [nowMillis], oldest first. */
    fun window(minutes: Long, nowMillis: Long = clock()): List<String> {
        val from = nowMillis - minutes * 60_000L
        return held.value.lines.filter { it.atMillis >= from }.map { it.text }
    }

    private fun append(fresh: List<LogLine>) {
        if (fresh.isEmpty()) return
        held.update { h ->
            val all = h.lines + fresh
            var bytes = h.bytes + fresh.sumOf { utf8Size(it.text) }
            var drop = 0
            while (drop < all.size && (all.size - drop > maxLines || bytes > maxBytes)) {
                bytes -= utf8Size(all[drop].text)
                drop++
            }
            Held(if (drop == 0) all else all.subList(drop, all.size).toList(), bytes)
        }
    }

    private companion object {
        /** Most lines one call may add: a deep stack trace is cut, not the ring. */
        const val MAX_PIECES = 60
    }
}

/**
 * The app's one log: what the screens and the connection report, held in
 * [ring] for the hub's self debug agent. Public so the Android host can log a
 * crash through it.
 */
object FleetLog {
    val ring: LogRing = LogRing()

    fun debug(tag: String, message: String) = ring.add(LogLevel.Debug, tag, message)

    fun info(tag: String, message: String) = ring.add(LogLevel.Info, tag, message)

    fun warn(tag: String, message: String) = ring.add(LogLevel.Warn, tag, message)

    fun error(tag: String, message: String, t: Throwable? = null) =
        ring.add(LogLevel.Error, tag, if (t == null) message else "$message\n${t.stackTraceToString()}")

    /**
     * The last [count] lines as `millis<TAB>line`, for a host to keep across a
     * crash (the ring itself dies with the process); [restore] reads them back.
     */
    fun crashRecord(count: Int = CRASH_LINES): List<String> =
        ring.lines().takeLast(count).map { "${it.atMillis}\t${it.text}" }

    /** Lines a previous run kept with [crashRecord]; anything malformed is skipped. */
    fun restore(record: List<String>) {
        ring.restore(
            record.mapNotNull { r ->
                val at = r.substringBefore('\t', "").toLongOrNull() ?: return@mapNotNull null
                LogLine(at, r.substringAfter('\t'))
            },
        )
    }

    /** How many lines a crash keeps. */
    const val CRASH_LINES = 200
}

/** Most lines the ring holds, and most lines one upload carries (the hub keeps 2000). */
const val LOG_MAX_LINES = 2000

/** Most bytes the ring holds: half the hub's 1 MiB body, so an upload of all of it fits. */
const val LOG_MAX_BYTES = 512 * 1024

/** Most characters per line (the hub cuts at 2000). */
const val LOG_LINE_MAX_CHARS = 2000

/** What stands where a secret was. */
internal const val LOG_REDACTED = "<redacted>"

private val BEARER = Regex("""(?i)\b(bearer|basic)(\s+)[A-Za-z0-9._~+/=-]{8,}""")
private val KEY_VALUE = Regex("""(?i)\b([A-Za-z_]*(?:token|password|passwd|secret))=[^\s&,;"']+""")
private val JSON_VALUE = Regex("""(?i)("[A-Za-z_]*(?:token|password|passwd|secret)"\s*:\s*")[^"]*""")
private val LONG_RUN = Regex("""[A-Za-z0-9_]{32,}""")

/**
 * [text] with what looks like a credential masked: an `Authorization`
 * value (`Bearer …`, `Basic …`), a `token=` / `password=` / `secret=` value
 * (a query string's, a JSON field's), and any run of 32 or more letters and
 * digits that mixes both — the shape of the hub's 64-hex token and of most
 * provider tokens. A sanitiser of shapes, like the hub's `logging::redact`;
 * the uploader also masks this device's own token by value.
 */
fun redactLog(text: String): String {
    var out = BEARER.replace(text) { m -> "${m.groupValues[1]}${m.groupValues[2]}$LOG_REDACTED" }
    out = KEY_VALUE.replace(out) { m -> "${m.groupValues[1]}=$LOG_REDACTED" }
    out = JSON_VALUE.replace(out) { m -> "${m.groupValues[1]}$LOG_REDACTED" }
    out = LONG_RUN.replace(out) { m ->
        val run = m.value
        if (run.any { it.isDigit() } && run.any { it.isLetter() }) LOG_REDACTED else run
    }
    return out
}

/** [millis] since the epoch as `2026-10-17T10:15:40.123Z`, without a date library. */
fun logStamp(millis: Long): String {
    val days = millis.floorDiv(86_400_000L)
    val ms = millis.mod(86_400_000L)
    // Howard Hinnant's civil_from_days.
    val z = days + 719_468L
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    fun two(n: Long) = n.toString().padStart(2, '0')
    return "${year.toString().padStart(4, '0')}-${two(month)}-${two(day)}" +
        "T${two(ms / 3_600_000)}:${two(ms / 60_000 % 60)}:${two(ms / 1000 % 60)}.${(ms % 1000).toString().padStart(3, '0')}Z"
}

/** The UTF-8 length of [s], without encoding it. */
internal fun utf8Size(s: String): Int {
    var n = 0
    for (c in s) {
        n += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isSurrogate() -> 2
            else -> 3
        }
    }
    return n
}
