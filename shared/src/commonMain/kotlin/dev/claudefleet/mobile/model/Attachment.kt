package dev.claudefleet.mobile.model

import kotlin.math.round

/**
 * A file the person picked, held in memory until Send. Nothing leaves the
 * phone before then: a file queued and removed was never uploaded.
 */
class PickedFile(val name: String, val size: Long, val bytes: ByteArray)

/**
 * A file the person picked and the picker did **not** bring back.
 *
 * It exists so that nothing chosen can disappear without a word. The picker
 * refuses a file over [ATTACH_MAX_BYTES] before reading it — which is the
 * only way "the bytes come back in memory" stays bounded — and it can also
 * fail to read one at all, when a content grant has already gone stale by the
 * time it is opened. Both used to be an absence in a list, indistinguishable
 * from a cancelled pick; now each one is a sentence the composer shows.
 *
 * [size] is what the platform declared, and is null when it would not say —
 * an Android provider that publishes no `OpenableColumns.SIZE`, an iOS URL
 * with no `NSURLFileSizeKey`. In that case the file was abandoned mid-read at
 * the ceiling, so all that is known is that it is over it.
 */
class SkippedFile(val name: String, val size: Long?, val reason: SkipReason)

/** Why a [SkippedFile] was skipped. */
enum class SkipReason {
    /** Bigger than [ATTACH_MAX_BYTES] on its own; never read. */
    TooBig,

    /**
     * Small enough by itself, but there was no room left for it inside
     * [ATTACH_MAX_TOTAL] in the pick it arrived with. Its own sentence,
     * because "a.png is 8.0 MB — the limit is 10 MB" would be a lie about a
     * file that is perfectly acceptable on its own.
     */
    OverTotal,

    /** The platform would not open it — a grant that did not survive the trip. */
    Unreadable,
}

/**
 * One launch of the picker: what came back, and what was left behind.
 *
 * Both lists empty means the person cancelled — that, and only that, which is
 * the distinction the older `List<PickedFile>` callback could not make.
 */
class PickResult(
    val files: List<PickedFile> = emptyList(),
    val skipped: List<SkippedFile> = emptyList(),
)

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
            return overLimitRefusal(name, size)
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
 * The one sentence for "this file is over the per-file ceiling", whether the
 * refusal came from [checkBudget] with a size in hand or from the picker
 * abandoning a file whose size the platform would not declare. One function
 * so the two cannot drift into two different wordings for one rule.
 */
fun overLimitRefusal(name: String, size: Long?): String =
    if (size != null) {
        "$name is ${fmtBytes(size)} — the limit is $MAX_BYTES_MB MB."
    } else {
        "$name is over the $MAX_BYTES_MB MB limit."
    }

/**
 * What to say about a file the picker did not bring back. The composer is the
 * only thing with a screen to say it on — see [SkippedFile].
 */
fun skippedRefusal(file: SkippedFile): String = when (file.reason) {
    SkipReason.TooBig -> overLimitRefusal(file.name, file.size)
    SkipReason.OverTotal -> "${file.name} did not fit — the limit is $MAX_TOTAL_MB MB in total."
    SkipReason.Unreadable -> "${file.name} could not be read."
}

/**
 * Whether a file of a *known* size can be taken, given how much of
 * [ATTACH_MAX_TOTAL] this pick has already spent — or the [SkippedFile] to
 * report instead. Null means take it.
 *
 * Lives here, in common code, rather than in each `actual`: both platform
 * pickers make exactly this decision per file, and they made it in two
 * places until one of them was wrong. [remaining] is the pick's own budget,
 * never the composer's queue — the picker cannot see the queue, which is why
 * `SessionViewModel.attach` checks the running total again over everything.
 */
fun skipForSize(name: String, size: Long, remaining: Long): SkippedFile? = when {
    size > ATTACH_MAX_BYTES -> SkippedFile(name, size, SkipReason.TooBig)
    size > remaining -> SkippedFile(name, size, SkipReason.OverTotal)
    else -> null
}

/**
 * How many bytes a picker may read for the next file: the per-file ceiling,
 * or what is left of this pick's [ATTACH_MAX_TOTAL] if that is less. Used
 * where the platform will not declare a size and the read itself has to be
 * the bound. Never negative.
 */
fun readCap(remaining: Long): Long = minOf(ATTACH_MAX_BYTES, maxOf(remaining, 0L))

/**
 * [name], or the first `-1`, `-2`, … variant of it that is not in [taken] —
 * the suffix going before the final extension (`a.png` → `a-1.png`;
 * `notes` → `notes-1`; `.env` → `.env-1`, since a leading dot is not an
 * extension).
 *
 * A port of `dedupe_names`/`suffix_name` in the desktop's
 * `commands/upload.rs`, so a batch of same-named files lands under the same
 * names whichever client sent it. Two files really can share a name — one
 * `IMG_0001.jpg` from each of two folders in a single multi-select — and the
 * queue must hold both: the alternative is one of them disappearing without
 * a word, which is the whole thing attachments-on-the-phone exists to avoid.
 */
fun dedupedName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    var n = 1
    while (true) {
        val candidate = suffixName(name, n)
        if (candidate !in taken) return candidate
        n++
    }
}

private fun suffixName(name: String, n: Int): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) "${name.substring(0, dot)}-$n.${name.substring(dot + 1)}" else "$name-$n"
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
