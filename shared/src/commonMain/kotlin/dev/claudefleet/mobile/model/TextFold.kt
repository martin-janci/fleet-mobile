package dev.claudefleet.mobile.model

/**
 * One fold for every search box, so "uloha" finds "Úloha" and "zluty" finds
 * "žltý": lower-case, combining marks dropped, and the Latin-1 / Latin
 * Extended-A letters (U+00C0–U+017F) mapped to their bare letter. A table,
 * not Unicode decomposition (which common code does not have), and the
 * same table as the hub's (`crates/fleet-core/src/search_text.rs`) and the
 * desktop's (`src/lib/text_fold.ts`), so a query matches the same rows
 * everywhere.
 */
fun fold(text: String): String {
    val lower = text.lowercase()
    if (lower.all { it.code < 0x80 }) return lower
    val out = StringBuilder(lower.length)
    for (c in lower) {
        val cp = c.code
        if (cp in 0x300..0x36F) continue
        if (cp in 0xC0 until 0x180) {
            val b = LATIN[cp - 0xC0]
            if (b != '.') {
                out.append(b)
                continue
            }
        }
        out.append(c)
    }
    return out.toString()
}

/** Whether [text] holds [folded] (already [fold]ed), case and accents ignored. */
fun String?.foldedContains(folded: String): Boolean = this != null && fold(this).contains(folded)

/** U+00C0..U+0180, lower-cased: the bare letter, or `.` to keep it. */
private const val LATIN =
    "aaaaaa.ceeeeiiiidnooooo.ouuuuy..aaaaaa.ceeeeiiiidnooooo.ouuuuy.y" +
        "aaaaaaccccccccddddeeeeeeeeeegggggggghhhhiiiiiiiiii..jjkkklllllll" +
        "lllnnnnnnn..oooooo..rrrrrrssssssssttttttuuuuuuuuuuuuwwyyyzzzzzzs"
