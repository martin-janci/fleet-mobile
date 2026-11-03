package dev.claudefleet.mobile.ui

import kotlinx.coroutines.CancellationException

/**
 * [block]'s answer, or null when it fails: `runCatching { }.getOrNull()` for
 * a suspend call, minus the trap. `runCatching` also catches the
 * [CancellationException] of a scope that is ending, so the coroutine went
 * on as if a read had merely failed; here it goes on up (hardening review
 * 2026-11-03, notes).
 */
internal inline fun <T> orNullUnlessCancelled(block: () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
