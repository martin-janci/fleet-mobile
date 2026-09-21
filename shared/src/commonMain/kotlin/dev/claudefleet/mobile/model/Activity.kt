package dev.claudefleet.mobile.model

/**
 * The one place pane text is cleaned before a screen draws it.
 *
 * `current_activity` is the hub's one-line reading of the pane. For a working
 * or blocked session it says what is happening; for an idle one it is usually
 * the REPL's own footer, which tells a person nothing. Everything here is a
 * pure string rule with the screenshots it was written from as its tests.
 */
object Activity {
    private val ansi = Regex("\\[[0-9;?]*[ -/]*[@-~]")
    // SGR mouse-report residue: `0;16;27M0;16;27m` — digit groups ending in M/m.
    private val mouseResidue = Regex("(?:\\d+;)+\\d+[Mm]")
    private val waiting = Regex("^waiting for (input|permission)\\s*:\\s*(.+)$")
    private val chrome = listOf(
        "bypass permissions on",
        "shift+tab to cycle",
        "? for shortcuts",
        "esc to interrupt",
        "for agents",
    )

    fun sanitize(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw
            .replace(ansi, "")
            .replace(mouseResidue, "")
            .filterNot { it.isPrivateUse() || (it.isISOControl() && it != '\t') }
            .trim()
        if (cleaned.isEmpty()) return null
        val lower = cleaned.lowercase()
        if (chrome.any { it in lower }) return null
        val bare = cleaned.trimStart('❯', '›', ' ')
        if (bare.isEmpty()) return null
        pending(cleaned)?.let { return it }
        return cleaned
    }

    fun pending(raw: String?): String? {
        if (raw == null) return null
        val m = waiting.find(raw.trim()) ?: return null
        return m.groupValues[2].trim().ifEmpty { null }
    }

    private fun Char.isPrivateUse(): Boolean = this in ''..''
}
