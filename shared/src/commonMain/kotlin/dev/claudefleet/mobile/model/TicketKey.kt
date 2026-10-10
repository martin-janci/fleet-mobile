package dev.claudefleet.mobile.model

/**
 * *Link a ticket* checks what was typed when Link is pressed, not on each
 * key (MobileFormsWork): a ticket key that is almost right — `fleet142`,
 * `FLEET 142`, `FLEET_142`, or a prefix one letter off one the phone knows —
 * is answered under the field with the fix, "Not a key. Did you mean
 * FLEET-142?", and the fix is one tap.
 *
 * PURE: the well-formed key to offer for [input], or null when it should go
 * as typed. A well-formed key (`PREFIX-123`, the hub's `is_ticket_key`
 * shape), a GitHub `owner/repo#12`, a pasted URL and anything that is not
 * key-shaped at all (the hub takes free text as a local key) are never
 * corrected: only input that reads as a key with the wrong separator, or
 * a bare number when the phone knows exactly one project.
 *
 * [knownPrefixes] are the project keys of tickets the phone has seen
 * ([knownKeyPrefixes]); a typed prefix one edit away from exactly one of
 * them is corrected to it ("FLET 142" → FLEET-142).
 */
fun ticketKeyFix(input: String, knownPrefixes: Collection<String> = emptyList()): String? {
    val t = input.trim()
    if (t.isEmpty() || t.contains("://") || isTicketKey(t) || GITHUB_REF.matches(t)) return null
    val known = knownPrefixes.map { it.uppercase() }.filter { isPrefix(it) }.distinct()
    BARE_NUMBER.matchEntire(t)?.let { m ->
        return known.singleOrNull()?.let { "$it-${m.groupValues[1]}" }
    }
    val m = LOOSE_KEY.matchEntire(t) ?: return null
    val typed = m.groupValues[1].uppercase()
    val prefix = if (typed in known) typed else known.filter { editDistance(it, typed) == 1 }.singleOrNull() ?: typed
    val key = "$prefix-${m.groupValues[2]}"
    return key.takeIf { isTicketKey(it) && !it.equals(t, ignoreCase = true) }
}

/** The line under the field for [fix]: "Not a key. Did you mean FLEET-142?" */
fun keyFixLine(fix: String): String = "Not a key. Did you mean $fix?"

/** The distinct project keys ("FLEET") of [tickets] whose key is well formed, for [ticketKeyFix]. */
fun knownKeyPrefixes(tickets: List<Ticket>): List<String> =
    tickets.mapNotNull { t -> t.key?.trim()?.takeIf { isTicketKey(it) }?.substringBefore('-')?.uppercase() }.distinct()

/**
 * `PREFIX-123`: a letter, then 1–9 of `[A-Za-z0-9_]`, a dash, 1–7 digits —
 * the hub's `store::work::is_ticket_key`, the shape it upper-cases.
 */
fun isTicketKey(s: String): Boolean {
    val dash = s.indexOf('-')
    if (dash < 0) return false
    val num = s.substring(dash + 1)
    return isPrefix(s.substring(0, dash)) && num.length in 1..7 && num.all { it in '0'..'9' }
}

private fun isPrefix(p: String): Boolean =
    p.length in 2..10 && p[0].isAsciiLetter() && p.all { it.isAsciiLetter() || it in '0'..'9' || it == '_' }

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'

/** A key with the wrong separator, or none: `fleet142`, `FLEET 142`, `FLEET_142`, `FLEET.142`, `FLEET#142`. */
private val LOOSE_KEY = Regex("""([A-Za-z][A-Za-z0-9_]{1,9}?)[\s_.:/#–—-]*([0-9]{1,7})""")

/** A number on its own, `142` or `#142`. */
private val BARE_NUMBER = Regex("""#?([0-9]{1,7})""")

/** `owner/repo#12`, or an enterprise host's `host/owner/repo#12`. */
private val GITHUB_REF = Regex("""[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+){1,2}#[0-9]{1,9}""")

/** Levenshtein distance; both strings are short prefixes. */
private fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        }
        prev = cur
    }
    return prev[b.length]
}

/**
 * PURE: what a press of Link does with [input] — null to link it, or the
 * fix to offer under the field. [offeredFor] is the input a fix was last
 * offered for: pressing Link again on that same input links it as typed,
 * so a free-form key that only looks like a broken one is never stuck.
 */
fun keyFixOnLink(input: String, offeredFor: String?, knownPrefixes: Collection<String> = emptyList()): String? =
    if (offeredFor != null && offeredFor == input.trim()) null else ticketKeyFix(input, knownPrefixes)
