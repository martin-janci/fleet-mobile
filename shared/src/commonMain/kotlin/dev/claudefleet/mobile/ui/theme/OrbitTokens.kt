package dev.claudefleet.mobile.ui.theme

/**
 * The Orbit Fleet design tokens, as the phone holds them.
 *
 * The source is the design manual's `tokens.json`; `docs/design/tokens.json`
 * is this repo's copy of it, the same file claude-fleet keeps in its own
 * `docs/design/` (redesign step 0.5). The values below are literals rather
 * than a parse of that file because commonMain also compiles for Kotlin/Native,
 * where nothing reads the source tree at run time; `OrbitTokensDriftTest`
 * fails the moment the two disagree, in either direction.
 *
 * To change a token: change the manual, copy its `tokens.json` here, then
 * change the literal. A reference such as `{accent}` is written out resolved.
 */
internal object OrbitTokens {

    /** One colour token in both themes, as ARGB (`rgba()` alpha is rounded to the nearest of 255). */
    data class ColorToken(val name: String, val dark: Long, val light: Long)

    /** One text style. Sizes are the manual's px, drawn as sp. */
    data class TypeToken(val name: String, val size: Int, val lineHeight: Int, val weight: Int, val mono: Boolean)

    val colors: List<ColorToken> = listOf(
        ColorToken("bg", dark = 0xFF0F0F0F, light = 0xFFF7F7F8),
        ColorToken("bg-pane", dark = 0xFF161616, light = 0xFFFFFFFF),
        ColorToken("bg-raise", dark = 0xFF1C1C1C, light = 0xFFF2F2F4),
        ColorToken("bg-hover", dark = 0xFF232323, light = 0xFFEBEBEE),
        ColorToken("bg-sunk", dark = 0xFF202020, light = 0xFFF2F2F4),
        ColorToken("fg", dark = 0xFFEDEDED, light = 0xFF18181B),
        ColorToken("fg-2", dark = 0xFFB4B4B4, light = 0xFF3F3F46),
        ColorToken("fg-muted", dark = 0xFF8F8F8F, light = 0xFF6B6B74),
        ColorToken("border", dark = 0xFF2A2A2A, light = 0xFFE4E4E7),
        ColorToken("control-border", dark = 0xFF3A3A3A, light = 0xFFD4D4D8),
        ColorToken("accent", dark = 0xFF60A5FA, light = 0xFF2563EB),
        ColorToken("accent-fg", dark = 0xFF0B1220, light = 0xFFFFFFFF),
        ColorToken("accent-soft", dark = 0xFF1C2735, light = 0xFFE7EEFE),
        ColorToken("ring", dark = 0xFF60A5FA, light = 0xFF2563EB),
        ColorToken("status-working", dark = 0xFF7FA3FF, light = 0xFF3157C9),
        ColorToken("status-waiting", dark = 0xFFD29B4A, light = 0xFF8F520B),
        ColorToken("status-failed", dark = 0xFFEF5350, light = 0xFFC62828),
        ColorToken("status-done", dark = 0xFF5DD17A, light = 0xFF17723E),
        ColorToken("status-idle", dark = 0xFFA09FA8, light = 0xFF6B6B74),
        ColorToken("on-waiting", dark = 0xFF1A1205, light = 0xFFFFFFFF),
        ColorToken("waiting-soft", dark = 0x21D29B4A, light = 0x218F520B),
        ColorToken("waiting-faint", dark = 0x12D29B4A, light = 0x128F520B),
        ColorToken("waiting-line", dark = 0x73D29B4A, light = 0x738F520B),
        ColorToken("failed-soft", dark = 0x1FEF5350, light = 0x1FC62828),
        ColorToken("failed-line", dark = 0x59EF5350, light = 0x59C62828),
        ColorToken("done-soft", dark = 0x1F5DD17A, light = 0x1F17723E),
        ColorToken("danger", dark = 0xFFEF5350, light = 0xFFC62828),
        ColorToken("danger-fill", dark = 0xFFC62828, light = 0xFFC62828),
        ColorToken("on-danger", dark = 0xFFFFFFFF, light = 0xFFFFFFFF),
        ColorToken("chip-bg", dark = 0xFF232323, light = 0xFFEFEFF2),
        ColorToken("count-bg", dark = 0xFF262626, light = 0xFFE9E9EC),
        ColorToken("track", dark = 0xFF2A2A2A, light = 0xFFE4E4E7),
        ColorToken("code", dark = 0xFF56B6C2, light = 0xFF0E7490),
        ColorToken("syn-kw", dark = 0xFFC678DD, light = 0xFF8A3FB0),
        ColorToken("syn-str", dark = 0xFF98C379, light = 0xFF3F7D20),
        ColorToken("syn-num", dark = 0xFFD19A66, light = 0xFF9A5A00),
        ColorToken("org-1", dark = 0xFF60A5FA, light = 0xFF2563EB),
        ColorToken("org-2", dark = 0xFF5DD17A, light = 0xFF17723E),
        ColorToken("org-3", dark = 0xFFC084FC, light = 0xFF8A3FB0),
        ColorToken("org-4", dark = 0xFFA09FA8, light = 0xFF6B6B74),
        ColorToken("brand-ink", dark = 0xFF1B2430, light = 0xFF1B2430),
        ColorToken("brand-light", dark = 0xFFF2F4F7, light = 0xFFF2F4F7),
        ColorToken("brand-amber", dark = 0xFFD29B4A, light = 0xFFD29B4A),
        ColorToken("loader-accent", dark = 0xFF60A5FA, light = 0xFF60A5FA),
        ColorToken("comet-head", dark = 0xFFF2F4F7, light = 0xFF2563EB),
        ColorToken("agent-claude", dark = 0xFFD97757, light = 0xFFD97757),
        ColorToken("scrim", dark = 0x66000000, light = 0x6618181B),
        ColorToken("scrim-strong", dark = 0x9E000000, light = 0x9E18181B),
        ColorToken("ai-pre", dark = 0xFF60A5FA, light = 0xFF2563EB),
        ColorToken("usage-ok", dark = 0xFF5DD17A, light = 0xFF17723E),
        ColorToken("usage-warn", dark = 0xFFD29B4A, light = 0xFF8F520B),
        ColorToken("usage-crit", dark = 0xFFEF5350, light = 0xFFC62828),
        ColorToken("syn-code", dark = 0xFF56B6C2, light = 0xFF0E7490),
        ColorToken("control-bg", dark = 0xFF1C1C1C, light = 0xFFFFFFFF),
        ColorToken("control-bg-hover", dark = 0xFF262626, light = 0xFFF0F0F0),
        ColorToken("control-bg-active", dark = 0xFF303030, light = 0xFFE4E4E4),
        ColorToken("control-border-strong", dark = 0xFF6E6E6E, light = 0xFF8E8E8E),
        ColorToken("control-fg", dark = 0xFFEDEDED, light = 0xFF1A1A1A),
        ColorToken("control-fg-quiet", dark = 0xFFA8A8A8, light = 0xFF5A5A5A),
    )

    /** Spacing and layout sizes, in the manual's px, drawn as dp. */
    val spacing: Map<String, Float> = mapOf(
        "space-1" to 4f,
        "space-2" to 8f,
        "space-3" to 12f,
        "space-4" to 16f,
        "space-6" to 24f,
        "control-h" to 24f,
        "control-h-sm" to 22f,
        "control-h-lg" to 28f,
        "header-h" to 44f,
        "rail-w" to 68f,
        "list-w" to 340f,
        "settings-nav-w" to 240f,
        "prose-max" to 720f,
        "status-h" to 25f,
        "touch-min" to 48f,
        "phone-gutter" to 16f,
        "phone-bar-h" to 56f,
        "tab-bar-h" to 72f,
        "phone-row-min" to 72f,
    )

    /** Corner radii, in the manual's px, drawn as dp. */
    val radius: Map<String, Float> = mapOf(
        "radius-xs" to 3f,
        "radius-sm" to 4f,
        "radius-md" to 6f,
        "radius-lg" to 8f,
        "radius-brand" to 24f,
        "radius-sheet" to 22f,
        "radius-phone-card" to 14f,
        "radius-pill" to 999f,
    )

    val type: List<TypeToken> = listOf(
        TypeToken("text-2xs", size = 11, lineHeight = 14, weight = 500, mono = false),
        TypeToken("text-xs", size = 12, lineHeight = 16, weight = 400, mono = false),
        TypeToken("text-sm", size = 13, lineHeight = 18, weight = 500, mono = false),
        TypeToken("text-md", size = 14, lineHeight = 21, weight = 400, mono = false),
        TypeToken("text-lg", size = 16, lineHeight = 22, weight = 600, mono = false),
        TypeToken("text-xl", size = 20, lineHeight = 26, weight = 600, mono = false),
        TypeToken("code", size = 12, lineHeight = 18, weight = 400, mono = true),
    )

    /**
     * The manual's `size` group: fixed sizes the layout and controls share, in
     * px drawn as dp (a font size as sp). Most are desktop chrome (inspector
     * widths, control padding); the phone holds the whole group so the drift
     * test compares it in both directions, as it does every other group.
     */
    val size: Map<String, Float> = mapOf(
        "control-px" to 8f,
        "control-px-lg" to 12f,
        "control-gap" to 6f,
        "control-font" to 12f,
        "control-font-sm" to 11f,
        "ring-w" to 2f,
        "ring-offset" to 1f,
        "inspector-min" to 280f,
        "inspector-max" to 320f,
        "fab-size" to 48f,
        "layer-gap" to 8f,
    )

    /** Durations in milliseconds. A loader never shows before `loader-delay`. */
    val durationMs: Map<String, Long> = mapOf(
        "dur-fast" to 80L,
        "dur-base" to 160L,
        "dur-slow" to 280L,
        "loader-delay" to 400L,
        "loader-reduced" to 2_400L,
        "hub-lost-after" to 6_000L,
    )

    private val byName: Map<String, ColorToken> = colors.associateBy { it.name }

    /** The ARGB value of colour token [name] in one theme; a misspelt name is a bug, so it throws. */
    fun argb(name: String, dark: Boolean): Long =
        byName[name]?.let { if (dark) it.dark else it.light } ?: error("no colour token named $name")

    fun spacing(name: String): Float = spacing[name] ?: error("no spacing token named $name")

    fun radius(name: String): Float = radius[name] ?: error("no radius token named $name")

    fun size(name: String): Float = size[name] ?: error("no size token named $name")
}
