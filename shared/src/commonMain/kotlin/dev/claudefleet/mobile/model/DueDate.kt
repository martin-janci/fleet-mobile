package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.epochSeconds
import dev.claudefleet.mobile.utcOffsetSeconds

/** A due date in words, and whether it has passed. */
data class DueLabel(val text: String, val overdue: Boolean)

/** Day 0 (1970-01-01) was a Thursday. */
private val WEEKDAYS = listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")

/** `YYYY-MM-DD`, the only shape a due date has on the wire. Built once, not per row. */
internal val ISO_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

/** The local calendar day of [nowSeconds], counted from 1970-01-01. */
fun localEpochDay(nowSeconds: Long, utcOffsetSeconds: Int): Long = (nowSeconds + utcOffsetSeconds).floorDiv(86_400L)

/** Today's local calendar day: what every due date on a screen is read against. */
internal fun todayEpochDay(nowSeconds: Long = epochSeconds()): Long = localEpochDay(nowSeconds, utcOffsetSeconds(nowSeconds))

/** `YYYY-MM-DD` as days from 1970-01-01 (Howard Hinnant's days_from_civil); null when it is not a real date. */
internal fun epochDayOf(date: String): Long? {
    val m = ISO_DATE.find(date) ?: return null
    val (ys, ms, ds) = m.destructured
    val y0 = ys.toLong()
    val mo = ms.toInt()
    val d = ds.toInt()
    if (mo !in 1..12 || d < 1) return null
    val leap = (y0 % 4 == 0L && y0 % 100 != 0L) || y0 % 400 == 0L
    val len = when (mo) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
    if (d > len) return null
    val y = if (mo <= 2) y0 - 1 else y0
    val era = y.floorDiv(400L)
    val yoe = y - era * 400
    val mp = (mo + 9) % 12
    val doy = (153 * mp + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097 + doe - 719_468
}

/**
 * A due date as the desktop's Work row and Board card say it (`dueLabel` in
 * claude-fleet's `work.ts`): "Today", "Tomorrow", a weekday within the
 * coming week ("Fri"), else the day and month ("Oct 23"). A past date is
 * overdue. Null for no date or one that does not parse.
 */
fun dueLabel(due: String?, todayEpochDay: Long): DueLabel? {
    if (due == null) return null
    val day = epochDayOf(due) ?: return null
    val days = day - todayEpochDay
    // "Oct 23": the same month-and-day words the rest of the phone uses.
    val date = dayLabel(day * 86_400L, 0)
    return when {
        days < 0 -> DueLabel(date, overdue = true)
        days == 0L -> DueLabel("Today", false)
        days == 1L -> DueLabel("Tomorrow", false)
        days < 7 -> DueLabel(WEEKDAYS[day.mod(7L).toInt()], false)
        else -> DueLabel(date, false)
    }
}

/**
 * The task screen's due line: "Due Fri · 2026-11-06", or "Overdue · was due
 * 2026-10-30" once the date has passed. A done task shows no due date: it
 * is history, never overdue (the desktop's `openDue`). A date the phone
 * cannot read is shown as the hub sent it.
 */
fun dueLine(due: String?, todayEpochDay: Long, done: Boolean): DueLabel? {
    if (done) return null
    val d = due?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val label = dueLabel(d, todayEpochDay)
    return when {
        label == null -> DueLabel("Due: $d", false)
        label.overdue -> DueLabel("Overdue · was due $d", true)
        else -> DueLabel("Due ${label.text} · $d", false)
    }
}

/**
 * A task's due date as every chip and row line says it, or null (the
 * desktop's `openDue` in `work.ts`): "Due Fri", or "Overdue · Oct 30" once
 * it has passed. A done task shows no due date: it is history, never
 * overdue. Null for no date or one that does not parse, as on the desktop.
 */
fun openDue(due: String?, done: Boolean, todayEpochDay: Long): DueLabel? {
    if (done) return null
    val label = dueLabel(due?.trim(), todayEpochDay) ?: return null
    return DueLabel(if (label.overdue) "Overdue · ${label.text}" else "Due ${label.text}", label.overdue)
}
