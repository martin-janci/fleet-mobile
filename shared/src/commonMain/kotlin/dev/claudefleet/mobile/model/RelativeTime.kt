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

/**
 * "5 min ago", or "just now" — [relativeTime] as a phrase that stands on its
 * own. Every "… ago" in the app goes through here: tacking " ago" onto
 * [relativeTime] by hand read "just now ago".
 */
fun relativeAgo(epochSeconds: Long?, nowSeconds: Long): String? {
    val t = relativeTime(epochSeconds, nowSeconds) ?: return null
    return if (t == "just now") t else "$t ago"
}

/** Time left until [deadlineSeconds]: "5 min", "2 h"; "under a minute" at the end, never "just now". */
fun relativeWithin(deadlineSeconds: Long?, nowSeconds: Long): String? {
    if (deadlineSeconds == null) return null
    val left = deadlineSeconds - nowSeconds
    return if (left < 60) "under a minute" else relativeTime(nowSeconds, deadlineSeconds)
}
