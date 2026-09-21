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
    // Anchored on the ESC. Without it the pattern is "[" plus a final byte,
    // which is an ordinary bracket — so `Committing to [main]` lost its
    // `[m`, and `waiting for input: [y/n]?` lost the `[y`. The escape is
    // written `\u001B` rather than as a raw byte so the rule survives every
    // copy, patch and review tool between here and the file.
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
    // SGR mouse-report residue: `0;16;27M0;16;27m` — digit groups ending in M/m.
    private val mouseResidue = Regex("(?:\\d+;)+\\d+[Mm]")
    private val waiting = Regex("^waiting for (input|permission)\\s*:\\s*(.+)$")
    private val chrome = listOf(
        "bypass permissions on",
        "shift+tab to cycle",
        "? for shortcuts",
        "esc to interrupt",
    )

    /**
     * `for agents` on its own is not chrome — "waiting for agents to finish"
     * is a session saying what it is doing, and dropping it left the row
     * blank at the one moment it had something to say. It is chrome only in
     * the REPL footer it came from, which is a `·`-separated strip with a `←`
     * in it, so those two glyphs are what the rule actually keys on.
     */
    private val agentFooter = "for agents"

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
        if (agentFooter in lower && ('←' in cleaned || '·' in cleaned)) return null
        // The prompt glyph is drawn by the REPL, not typed by the agent, and
        // a row that begins with one is a line about `Reading a.kt`, not
        // about `❯`. `bare` was computed only to decide emptiness and then
        // thrown away, so the glyph reached the screen anyway.
        val bare = cleaned.trimStart('❯', '›', ' ')
        if (bare.isEmpty()) return null
        pending(cleaned)?.let { return it }
        return bare
    }

    fun pending(raw: String?): String? {
        if (raw == null) return null
        val m = waiting.find(raw.trim()) ?: return null
        return m.groupValues[2].trim().ifEmpty { null }
    }

    private fun Char.isPrivateUse(): Boolean = this in ''..''
}
