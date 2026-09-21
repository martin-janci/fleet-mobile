package dev.claudefleet.mobile.model

/** "4 min", "2 h", "3 d" — coarse on purpose; a phone row has no room for seconds. */
fun relativeTime(epochSeconds: Long?, nowSeconds: Long): String? {
    if (epochSeconds == null) return null
    val delta = (nowSeconds - epochSeconds).coerceAtLeast(0)
    return when {
        delta < 60 -> "just now"
        delta < 3600 -> "${delta / 60} min"
        delta < 86_400 -> "${delta / 3600} h"
        else -> "${delta / 86_400} d"
    }
}
