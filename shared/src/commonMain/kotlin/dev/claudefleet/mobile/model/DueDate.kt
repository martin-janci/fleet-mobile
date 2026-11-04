package dev.claudefleet.mobile.model

/** A due date in words, and whether it has passed. */
data class DueLabel(val text: String, val overdue: Boolean)

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Day 0 (1970-01-01) was a Thursday. */
private val WEEKDAYS = listOf("Thu", "Fri", "Sat", "Sun", "Mon", "Tue", "Wed")

/** The local calendar day of [nowSeconds], counted from 1970-01-01. */
fun localEpochDay(nowSeconds: Long, utcOffsetSeconds: Int): Long = (nowSeconds + utcOffsetSeconds).floorDiv(86_400L)

/** `YYYY-MM-DD` as days from 1970-01-01 (Howard Hinnant's days_from_civil); null when it is not a real date. */
internal fun epochDayOf(date: String): Long? {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(date) ?: return null
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
    val month = due.substring(5, 7).toInt()
    val date = "${MONTHS[month - 1]} ${due.substring(8, 10).toInt()}"
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
 * 2026-10-30" once the date has passed. A done task's date is history:
 * "Due: 2026-10-30", never overdue. A date the phone cannot read is shown as
 * the hub sent it.
 */
fun dueLine(due: String?, todayEpochDay: Long, done: Boolean): DueLabel? {
    val d = due?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val label = dueLabel(d, todayEpochDay)
    return when {
        done || label == null -> DueLabel("Due: $d", false)
        label.overdue -> DueLabel("Overdue · was due $d", true)
        else -> DueLabel("Due ${label.text} · $d", false)
    }
}

/**
 * The short form for a chip or a card line: "Due Fri", or "Overdue Oct 30"
 * once the date has passed. A done task's, or a date the phone cannot read,
 * is shown as the hub sent it ("Due 2026-10-30"), never overdue.
 */
fun dueChip(due: String?, todayEpochDay: Long, done: Boolean): DueLabel? {
    val d = due?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val label = dueLabel(d, todayEpochDay)
    return when {
        done || label == null -> DueLabel("Due $d", false)
        label.overdue -> DueLabel("Overdue ${label.text}", true)
        else -> DueLabel("Due ${label.text}", false)
    }
}
