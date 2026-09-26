package dev.claudefleet.mobile.model

import kotlin.math.round

/**
 * A file the person picked, held in memory until Send. Nothing leaves the
 * phone before then: a file queued and removed was never uploaded.
 */
class PickedFile(val name: String, val size: Long, val bytes: ByteArray)

/** Per-file ceiling. Mirrors `attachments::MAX_BYTES`. */
const val ATTACH_MAX_BYTES: Long = 10L * 1024 * 1024

/** Ceiling for one send's attachments together. Mirrors `attachments::MAX_TOTAL`. */
const val ATTACH_MAX_TOTAL: Long = 25L * 1024 * 1024

private const val MAX_BYTES_MB = ATTACH_MAX_BYTES / (1024 * 1024)
private const val MAX_TOTAL_MB = ATTACH_MAX_TOTAL / (1024 * 1024)

/**
 * Mirrors `fmt_bytes` in `service/attachments.rs`, so a refusal shown here
 * reads exactly like the one the hub would have sent back.
 */
fun fmtBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${round(n / 1024.0).toLong()} KB"
    else -> {
        val mb = n / (1024.0 * 1024.0)
        val tenths = round(mb * 10).toLong()
        "${tenths / 10}.${tenths % 10} MB"
    }
}

/**
 * Both ceilings, in order, returning the first refusal as the sentence to
 * show — or `null` when the batch fits. This is UX: it stops someone queueing
 * a file that will not go. The bound that actually holds is `check_budget` on
 * the host, which runs whatever this believes.
 */
fun checkBudget(files: List<Pair<String, Long>>): String? {
    var total = 0L
    for ((name, size) in files) {
        if (size > ATTACH_MAX_BYTES) {
            return "$name is ${fmtBytes(size)} — the limit is $MAX_BYTES_MB MB."
        }
        total += size
        if (total > ATTACH_MAX_TOTAL) {
            return "$name would make ${fmtBytes(total)} in total — " +
                "the limit is $MAX_TOTAL_MB MB in total."
        }
    }
    return null
}

/**
 * Put the staged remote paths into the prompt. A port of `withAttachments` in
 * the desktop's `src/lib/attach_prompt.ts`, so both clients build one string.
 */
fun withAttachments(draft: String, paths: List<String>): String {
    if (paths.isEmpty()) return draft
    val block = "Attached files:\n" + paths.joinToString("\n")
    return if (draft.trim().isEmpty()) block else "$draft\n\n$block"
}
