package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.ui.components.formatUsd
import kotlin.test.Test
import kotlin.test.assertEquals

class ListWordsTest {

    @Test
    fun a_pr_url_is_shown_by_its_number() {
        assertEquals("PR #9", prLabel("https://github.com/acme/api/pull/9"))
        assertEquals("PR #12", prLabel("https://gitlab.com/acme/api/-/merge_requests/12"))
        assertEquals("PR", prLabel("https://example.com/somewhere"))
    }

    @Test
    fun dollars_are_grouped_by_thousands() {
        assertEquals("$1.84", formatUsd(1_840_000))
        assertEquals("$999.00", formatUsd(999_000_000))
        assertEquals("$1,234.56", formatUsd(1_234_560_000))
        assertEquals("$1,234,567.00", formatUsd(1_234_567_000_000))
    }
}
