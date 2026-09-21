# Phone as a pager — Phase 0 (the surface tells the truth) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every field the hub already sends is drawn, nothing that is terminal noise is, status has colour and a dark theme, errors speak plainly, and the app knows when it is looking at a hub it cannot trust.

**Architecture:** Pure functions in `commonMain` (`Activity.sanitize`, `RelativeTime`, `StatusTone.of`, `HubError.friendly`, contract parsing) with `commonTest` fixtures taken from real screenshots; composables only draw them. A `FleetTheme` wrapper provides status colour tokens through a `CompositionLocal`. No hub change in this phase.

**Tech Stack:** Kotlin Multiplatform 2.4, Compose Multiplatform 1.12 (material3 1.9), kotlinx.serialization, Ktor 3.6, kotlin.test + `runTest`. Build with `./gradlew :shared:jvmTest` (fast, ~6 s incremental) and `./gradlew :androidApp:assembleDebug`.

**Spec:** `docs/superpowers/specs/2026-09-21-phone-as-pager-design.md` (Phase 0 sections 0.1–0.7). Read it first; the plan argues from it.

## Global Constraints

- `compileSdk = 37`, `minSdk = 26`, `targetSdk = 35` (from `gradle/libs.versions.toml`); do not change them.
- No new runtime dependency. (The plan first allowed `compose.materialIconsCore` for Task 5; it does not exist for CMP 1.12 and `material-icons-core` stopped at 1.7.3, so every icon is hand-drawn in `ui/theme/FleetIcons.kt` — controller ruling, 2026-09-21.) The repo chose ZXing over ML Kit to keep the APK small; keep that stance.
- The token never reaches a screen, a log or an exception message (`HubError`, `Explain.kt`). Any new error text goes through `explain()`/`friendly()`, never `t.message` of an unknown throwable.
- One rule, one place: a rule used by two screens is a pure function in `commonMain` with a `commonTest`.
- `MutableStateFlow.update {}`; never `value = value.copy(...)`.
- Every new tool call the app makes must be in the hub's `CLIENT_TOOLS` (`ToolsTheAppMayCallTest` in `jvmTest` enforces it by scanning sources).
- Commit after every task with a Conventional Commits subject (`feat:`, `fix:`, `test:`, `refactor:`), one line, present tense, saying why.
- Run `./gradlew :shared:jvmTest` before every commit. It must be green. After Tasks 5, 6 also run `./gradlew :androidApp:assembleDebug`.
- Work on branch `feat/ux-pager` (already exists, holds the spec). Never push or open a PR from a task; the controller does that.

## File map

| File | Responsibility |
|---|---|
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/Activity.kt` (new) | `Activity.sanitize(raw): String?`, `Activity.pending(raw): String?` — the only place pane text is cleaned |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/RelativeTime.kt` (new) | `relativeTime(epochSeconds, now): String` |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/SessionRow.kt` | new wire fields; `displayName` for `bg:` rows; `supportingLine(now)` |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/theme/StatusTone.kt` (new) | `enum StatusTone` + `StatusTone.of(claudeStatus, stuckKind)` |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/theme/FleetTheme.kt` (new) | `FleetTheme {}`, `FleetStatusColors`, `LocalStatusColors` |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/theme/FleetIcons.kt` (new) | two hand-drawn `ImageVector`s (sessions, hosts) |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/components/StatusChip.kt` | draws a `StatusTone` |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/Friendly.kt` (new) | `Friendly(title, body, isError)`, `HubError.friendly()` |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/components/Banners.kt` | `ErrorBanner` takes `Friendly`, has a Details expander |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionsScreen.kt` | `TopAppBar`, `FilterChip`, `PullToRefreshBox`, `ListItem` rows, sticky host headers |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionScreen.kt` | empty states, composer pill, `TopAppBar` |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt` | `FleetTheme`, icon tabs with badge |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/net/EventStream.kt` | `Ready.contract` |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/net/HubContract.kt` (new) | `MIN_HUB_CONTRACT`, `MAX_HUB_CONTRACT`, `contractVerdict()` |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/data/FleetRepository.kt` | refuses an out-of-range hub |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/data/SessionActions.kt` | `ping()` (`fleet_health`) |
| `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionViewModel.kt` | `canSend` follows the `/mcp` probe |

---

### Task 1: Activity sanitiser

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/Activity.kt`
- Test: `shared/src/commonTest/kotlin/dev/claudefleet/mobile/model/ActivityTest.kt`

**Interfaces:**
- Produces: `object Activity { fun sanitize(raw: String?): String?; fun pending(raw: String?): String? }`. `sanitize` returns null when nothing worth drawing survives. `pending` returns the question after `waiting for <kind>: ` or null when the line is not a waiting line.

- [ ] **Step 1: Write the failing tests** (fixtures are the exact strings from the 2026-09-21 screenshots)

```kotlin
package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ActivityTest {
    @Test
    fun repl_chrome_is_dropped_entirely() {
        assertNull(Activity.sanitize("⏵⏵ bypass permissions on (shift+tab to cycle) · ← 3 agents"))
        assertNull(Activity.sanitize("⏵⏵ bypass permissions on (shift+tab to cycle) · ← for agents"))
        assertNull(Activity.sanitize("❯"))
        assertNull(Activity.sanitize("❯ "))
        assertNull(Activity.sanitize("? for shortcuts"))
        assertNull(Activity.sanitize("esc to interrupt"))
        assertNull(Activity.sanitize(null))
        assertNull(Activity.sanitize("   "))
    }

    @Test
    fun ansi_and_mouse_report_residue_is_stripped() {
        assertNull(Activity.sanitize("❯ 0;16;27M0;16;27m"))
        assertEquals("Running tests", Activity.sanitize("\u001B[32mRunning tests\u001B[0m"))
        assertEquals("build ok", Activity.sanitize(" build ok "))
        assertEquals("tail", Activity.sanitize("\u0007tail\u0000"))
    }

    @Test
    fun a_waiting_line_keeps_the_question_and_drops_the_prefix() {
        assertEquals(
            "☐ Recreate turanga?",
            Activity.sanitize("waiting for input: ☐ Recreate turanga?"),
        )
        assertEquals(
            "Do you want to make this edit?",
            Activity.sanitize("waiting for permission: Do you want to make this edit?"),
        )
        assertEquals("☐ Recreate turanga?", Activity.pending("waiting for input: ☐ Recreate turanga?"))
        assertNull(Activity.pending("Running tests"))
        assertNull(Activity.pending("waiting for input"))
    }

    @Test
    fun ordinary_activity_is_kept_trimmed() {
        assertEquals("Reading src/main.rs", Activity.sanitize("  Reading src/main.rs  "))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.model.ActivityTest'`
Expected: FAIL — `Unresolved reference: Activity`.

- [ ] **Step 3: Implement**

```kotlin
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
    private val ansi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
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
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.model.ActivityTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/Activity.kt shared/src/commonTest/kotlin/dev/claudefleet/mobile/model/ActivityTest.kt
git commit -m "feat(model): sanitise the activity line, so REPL chrome and ANSI residue never reach a row"
```

---

### Task 2: Relative time, new row fields, names for background sessions

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/RelativeTime.kt`
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/SessionRow.kt`
- Test: `shared/src/commonTest/kotlin/dev/claudefleet/mobile/model/RelativeTimeTest.kt`, `shared/src/commonTest/kotlin/dev/claudefleet/mobile/model/SessionRowTest.kt`

**Interfaces:**
- Produces: `fun relativeTime(epochSeconds: Long?, nowSeconds: Long): String?` ("just now", "4 min", "2 h", "3 d"; null for null input).
- Produces on `SessionRow`: `lastPrompt`, `lastStopAt`, `lastTurnAt`, `startedAt`, `usageCostMicros`, `usageModel`, `parentSessionId`, `branch`; `displayName` names `bg:` rows; `fun supportingLine(nowSeconds: Long): String?`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelativeTimeTest {
    private val now = 1_790_000_000L

    @Test
    fun buckets() {
        assertNull(relativeTime(null, now))
        assertEquals("just now", relativeTime(now - 20, now))
        assertEquals("4 min", relativeTime(now - 4 * 60, now))
        assertEquals("2 h", relativeTime(now - 2 * 3600, now))
        assertEquals("3 d", relativeTime(now - 3 * 86_400, now))
        assertEquals("just now", relativeTime(now + 30, now), "a clock ahead of the hub is not the future")
    }
}
```

```kotlin
package dev.claudefleet.mobile.model

import dev.claudefleet.mobile.net.json
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionRowTest {
    private val now = 1_790_000_000L

    @Test
    fun a_background_row_is_named_from_friendly_name_then_last_prompt_then_a_short_id() {
        val base = SessionRow(id = 1, tmuxName = "bg:44366faf-ae97-426a-91cd-beaf3c74f1d7")
        assertEquals("Background · 4436", base.displayName)
        assertEquals("fix the tenant header", base.copy(lastPrompt = "fix the tenant header").displayName)
        assertEquals("ADR", base.copy(friendlyName = "ADR", lastPrompt = "x").displayName)
        assertEquals("a".repeat(60), base.copy(lastPrompt = "a".repeat(80)).displayName)
    }

    @Test
    fun a_tmux_row_keeps_its_tmux_name() {
        assertEquals("trust-test", SessionRow(id = 1, tmuxName = "trust-test").displayName)
    }

    @Test
    fun the_supporting_line_is_the_sanitised_activity_or_time_and_kind() {
        val row = SessionRow(id = 1, tmuxName = "s", lastActivityAt = now - 240, kind = "shell")
        assertEquals("4 min · shell", row.supportingLine(now))
        assertEquals("4 min", row.copy(kind = "work").supportingLine(now))
        assertEquals(
            "4 min",
            row.copy(kind = "work", currentActivity = "⏵⏵ bypass permissions on (shift+tab to cycle)").supportingLine(now),
        )
        assertEquals("Reading a.kt", row.copy(currentActivity = "Reading a.kt").supportingLine(now))
        assertEquals("☐ Recreate turanga?", row.copy(currentActivity = "waiting for input: ☐ Recreate turanga?").supportingLine(now))
    }

    @Test
    fun the_new_fields_parse_and_default() {
        val row = json.decodeFromString(
            SessionRow.serializer(),
            """{"id":7,"tmux_name":"s","last_prompt":"go on","usage_cost_micros":1840000,"usage_model":"sonnet","branch":"feat/x","last_turn_at":10,"started_at":5,"last_stop_at":11,"parent_session_id":3}""",
        )
        assertEquals("go on", row.lastPrompt)
        assertEquals(1_840_000L, row.usageCostMicros)
        assertEquals("sonnet", row.usageModel)
        assertEquals("feat/x", row.branch)
        assertEquals(10L, row.lastTurnAt)
        assertEquals(5L, row.startedAt)
        assertEquals(11L, row.lastStopAt)
        assertEquals(3L, row.parentSessionId)
        val bare = json.decodeFromString(SessionRow.serializer(), """{"id":8}""")
        assertEquals(null, bare.lastPrompt)
        assertEquals(null, bare.usageCostMicros)
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.model.RelativeTimeTest' --tests 'dev.claudefleet.mobile.model.SessionRowTest'`
Expected: FAIL — unresolved `relativeTime`, `lastPrompt`, `supportingLine`.

- [ ] **Step 3: Implement**

`RelativeTime.kt`:
```kotlin
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
```

In `SessionRow.kt`, add after `val tags: List<String> = emptyList(),`:
```kotlin
    @SerialName("last_prompt") val lastPrompt: String? = null,
    @SerialName("last_stop_at") val lastStopAt: Long? = null,
    @SerialName("last_turn_at") val lastTurnAt: Long? = null,
    @SerialName("started_at") val startedAt: Long? = null,
    @SerialName("usage_cost_micros") val usageCostMicros: Long? = null,
    @SerialName("usage_model") val usageModel: String? = null,
    @SerialName("parent_session_id") val parentSessionId: Long? = null,
    val branch: String? = null,
```
and replace the body:
```kotlin
) {
    val isBackground: Boolean get() = tmuxName.startsWith("bg:")

    /** The agent's own label; for a background agent its prompt; else the tmux name. */
    val displayName: String
        get() {
            friendlyName?.takeIf { it.isNotBlank() }?.let { return it }
            if (!isBackground) return tmuxName
            lastPrompt?.takeIf { it.isNotBlank() }?.let { return it.take(60) }
            return "Background · ${tmuxName.removePrefix("bg:").take(4)}"
        }

    /** The rows the "needs attention" filter keeps. */
    val needsAttention: Boolean get() = claudeStatus == "blocked" || stuckKind != null

    /**
     * The row's second line: the sanitised activity when there is one, else
     * how long ago the session did anything and what kind of session it is.
     */
    fun supportingLine(nowSeconds: Long): String? {
        Activity.sanitize(currentActivity)?.let { return it }
        val age = relativeTime(lastActivityAt, nowSeconds)
        val kindLabel = kind?.takeIf { it != "work" && it.isNotBlank() }
        return listOfNotNull(age, kindLabel).joinToString(" · ").ifEmpty { null }
    }
}
```

- [ ] **Step 4: Run to verify they pass**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.model.*'`
Expected: PASS, and the existing `ConversationMergeTest`/`ConvItemTest` still green.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/RelativeTime.kt shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/SessionRow.kt shared/src/commonTest/kotlin/dev/claudefleet/mobile/model/RelativeTimeTest.kt shared/src/commonTest/kotlin/dev/claudefleet/mobile/model/SessionRowTest.kt
git commit -m "feat(model): name background sessions and give every row an age, from fields the hub already sends"
```

---

### Task 3: Status tones, the theme, and the chip

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/theme/StatusTone.kt`, `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/theme/FleetTheme.kt`
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/components/StatusChip.kt`, `shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt:149` (wrap in `FleetTheme`)
- Test: `shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/theme/StatusToneTest.kt`

**Interfaces:**
- Produces: `enum class StatusTone(val label: String, val outlined: Boolean, val dotted: Boolean) { WORKING, IDLE, BLOCKED, STUCK, FAILED, COMPLETED, STOPPED, UNKNOWN }`, `fun StatusTone.Companion.of(claudeStatus: String?, stuckKind: String?): StatusTone`, `fun statusLabel(claudeStatus: String?, stuckKind: String?): String`.
- Produces: `@Composable fun FleetTheme(content: @Composable () -> Unit)`, `data class FleetStatusColors(val dot: Color, val container: Color, val onContainer: Color)`, `val LocalStatusColors: ProvidableCompositionLocal<(StatusTone) -> FleetStatusColors>`.

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.claudefleet.mobile.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class StatusToneTest {
    @Test
    fun stuck_outranks_every_status() {
        assertEquals(StatusTone.STUCK, StatusTone.of("working", "press_enter"))
        assertEquals(StatusTone.STUCK, StatusTone.of(null, "oom"))
    }

    @Test
    fun each_status_has_its_tone_and_unknown_is_quiet() {
        assertEquals(StatusTone.WORKING, StatusTone.of("working", null))
        assertEquals(StatusTone.IDLE, StatusTone.of("idle", null))
        assertEquals(StatusTone.BLOCKED, StatusTone.of("blocked", null))
        assertEquals(StatusTone.FAILED, StatusTone.of("failed", null))
        assertEquals(StatusTone.COMPLETED, StatusTone.of("completed", null))
        assertEquals(StatusTone.STOPPED, StatusTone.of("stopped", null))
        assertEquals(StatusTone.UNKNOWN, StatusTone.of(null, null))
        assertEquals(StatusTone.UNKNOWN, StatusTone.of("", null))
        assertEquals(StatusTone.UNKNOWN, StatusTone.of("hibernating", null), "a status this build does not know is drawn quietly, not as an error")
    }

    @Test
    fun labels_read_as_words_and_unknown_is_a_dash() {
        assertEquals("press enter", statusLabel("working", "press_enter"))
        assertEquals("working", statusLabel("working", null))
        assertEquals("—", statusLabel(null, null))
        assertEquals("hibernating", statusLabel("hibernating", null), "an unknown word is still shown so a person can read it")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.ui.theme.StatusToneTest'`
Expected: FAIL — unresolved `StatusTone`.

- [ ] **Step 3: Implement**

`StatusTone.kt`:
```kotlin
package dev.claudefleet.mobile.ui.theme

/**
 * The colour a session's state is drawn in. The vocabulary is the hub's
 * (`claude_status`, `stuck_kind` in `pane_intel.rs`); the ranking is the
 * operator's: being stuck outranks any status because it is the thing a
 * person has to go and clear, and amber (BLOCKED) versus red (STUCK, FAILED)
 * is the whole triage — answer a question, or go fix the terminal.
 */
enum class StatusTone(val outlined: Boolean = false, val dotted: Boolean = false) {
    WORKING, IDLE, BLOCKED, STUCK, FAILED(outlined = true), COMPLETED, STOPPED(outlined = true), UNKNOWN(outlined = true, dotted = true);

    companion object {
        fun of(claudeStatus: String?, stuckKind: String?): StatusTone = when {
            !stuckKind.isNullOrBlank() -> STUCK
            else -> when (claudeStatus) {
                "working" -> WORKING
                "idle" -> IDLE
                "blocked" -> BLOCKED
                "failed" -> FAILED
                "completed" -> COMPLETED
                "stopped" -> STOPPED
                else -> UNKNOWN
            }
        }
    }
}

/** The word on the chip: the stuck kind, the status, or a dash when the hub has said nothing. */
fun statusLabel(claudeStatus: String?, stuckKind: String?): String = when {
    !stuckKind.isNullOrBlank() -> stuckKind.replace('_', ' ')
    claudeStatus.isNullOrBlank() -> "—"
    else -> claudeStatus
}
```

`FleetTheme.kt`:
```kotlin
package dev.claudefleet.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class FleetStatusColors(val dot: Color, val container: Color, val onContainer: Color)

val LocalStatusColors = staticCompositionLocalOf<(StatusTone) -> FleetStatusColors> {
    error("FleetTheme is not applied")
}

private fun light(tone: StatusTone) = when (tone) {
    StatusTone.WORKING -> FleetStatusColors(Color(0xFF2F6BFF), Color(0xFFE3ECFF), Color(0xFF0B3D91))
    StatusTone.IDLE -> FleetStatusColors(Color(0xFF8A8891), Color(0xFFEEEDF2), Color(0xFF4B4A52))
    StatusTone.BLOCKED -> FleetStatusColors(Color(0xFFE58A00), Color(0xFFFFE8C2), Color(0xFF6B3D00))
    StatusTone.STUCK -> FleetStatusColors(Color(0xFFD32F2F), Color(0xFFFFDAD6), Color(0xFF93000A))
    StatusTone.FAILED -> FleetStatusColors(Color(0xFFD32F2F), Color(0xFFFFDAD6), Color(0xFF93000A))
    StatusTone.COMPLETED -> FleetStatusColors(Color(0xFF1E8E3E), Color(0xFFD6F0DD), Color(0xFF0F5A2A))
    StatusTone.STOPPED -> FleetStatusColors(Color.Transparent, Color.Transparent, Color(0xFF4B4A52))
    StatusTone.UNKNOWN -> FleetStatusColors(Color.Transparent, Color.Transparent, Color(0xB34B4A52))
}

private fun dark(tone: StatusTone) = when (tone) {
    StatusTone.WORKING -> FleetStatusColors(Color(0xFF7FA3FF), Color(0xFF1B2D55), Color(0xFFB7CBFF))
    StatusTone.IDLE -> FleetStatusColors(Color(0xFF8A8891), Color(0xFF2C2B33), Color(0xFFC6C4CE))
    StatusTone.BLOCKED -> FleetStatusColors(Color(0xFFFFB74D), Color(0xFF4A3000), Color(0xFFFFD08A))
    StatusTone.STUCK -> FleetStatusColors(Color(0xFFFF8A80), Color(0xFF5C1A17), Color(0xFFFFB4AB))
    StatusTone.FAILED -> FleetStatusColors(Color(0xFFFF8A80), Color(0xFF5C1A17), Color(0xFFFFB4AB))
    StatusTone.COMPLETED -> FleetStatusColors(Color(0xFF7CD292), Color(0xFF143D25), Color(0xFF9FE0B4))
    StatusTone.STOPPED -> FleetStatusColors(Color.Transparent, Color.Transparent, Color(0xFFC6C4CE))
    StatusTone.UNKNOWN -> FleetStatusColors(Color.Transparent, Color.Transparent, Color(0xB3C6C4CE))
}

/** The app's theme: Material 3 light or dark by the system setting, plus the status tokens. */
@Composable
fun FleetTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalStatusColors provides if (dark) ::dark else ::light) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
```

`StatusChip.kt` — replace the file body (keep the package and the KDoc's first paragraph):
```kotlin
@Composable
fun StatusChip(
    claudeStatus: String?,
    stuckKind: String? = null,
    modifier: Modifier = Modifier,
) {
    val tone = StatusTone.of(claudeStatus, stuckKind)
    val colors = LocalStatusColors.current(tone)
    val text = statusLabel(claudeStatus, stuckKind)
    val border = when {
        tone.dotted -> BorderStroke(1.dp, colors.onContainer.copy(alpha = 0.4f))
        tone.outlined -> BorderStroke(1.dp, colors.onContainer.copy(alpha = 0.6f))
        else -> null
    }
    Surface(
        modifier = modifier.semantics { contentDescription = "status: $text" },
        color = colors.container,
        contentColor = colors.onContainer,
        shape = MaterialTheme.shapes.small,
        border = border,
    ) {
        AnimatedContent(targetState = text, label = "status") { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** The 10dp dot a row leads with. */
@Composable
fun StatusDot(claudeStatus: String?, stuckKind: String?, modifier: Modifier = Modifier) {
    val tone = StatusTone.of(claudeStatus, stuckKind)
    val colors = LocalStatusColors.current(tone)
    val stroke = if (tone.outlined) BorderStroke(1.dp, colors.onContainer.copy(alpha = 0.5f)) else null
    Box(
        modifier = modifier.size(10.dp).clip(CircleShape).background(colors.dot)
            .then(if (stroke != null) Modifier.border(stroke, CircleShape) else Modifier),
    )
}
```
Imports needed: `androidx.compose.animation.AnimatedContent`, `androidx.compose.foundation.BorderStroke`, `androidx.compose.foundation.background`, `androidx.compose.foundation.border`, `androidx.compose.foundation.layout.Box`, `androidx.compose.foundation.layout.size`, `androidx.compose.foundation.shape.CircleShape`, `androidx.compose.ui.draw.clip`, `androidx.compose.ui.semantics.contentDescription`, `androidx.compose.ui.semantics.semantics`, `dev.claudefleet.mobile.ui.theme.*`. Delete `onColorFor`.

In `App.kt` line 149 replace `MaterialTheme {` with `FleetTheme {` and import `dev.claudefleet.mobile.ui.theme.FleetTheme`.

- [ ] **Step 4: Run the tests and the Android build**

Run: `./gradlew :shared:jvmTest && ./gradlew :androidApp:assembleDebug`
Expected: tests PASS; `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/theme shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/components/StatusChip.kt shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/theme/StatusToneTest.kt
git commit -m "feat(ui): status tones with light and dark values, so amber means answer and red means go fix the terminal"
```

---

### Task 4: Errors in plain language, and empty states

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/Friendly.kt`
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/components/Banners.kt` (`ErrorBanner`), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionViewModel.kt` (`error` becomes `Friendly?`; `E_NO_TRANSCRIPT` is not an error), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionsViewModel.kt` (`error: Friendly?`), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionScreen.kt` (empty states), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionsScreen.kt` and `HostsScreen.kt`/`HostsViewModel.kt` (banner call sites)
- Test: `shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/FriendlyTest.kt`; extend `shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/SessionViewModelTest.kt` if it exists (check `ls shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/`), else create it with the fake `SessionActions` pattern from `HostsViewModelTest.kt`.

**Interfaces:**
- Produces: `data class Friendly(val title: String, val body: String, val isError: Boolean, val details: String? = null)`; `fun friendly(t: Throwable): Friendly`; `const val NO_TRANSCRIPT = "E_NO_TRANSCRIPT"`.
- `SessionUiState.error: Friendly?`, `SessionUiState.silent: Boolean` (true when the last read said `E_NO_TRANSCRIPT`), `SessionsUiState.error: Friendly?`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.net.HubError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FriendlyTest {
    @Test
    fun a_tool_refusal_hides_the_code_in_details() {
        val f = friendly(HubError.Tool("E_FORBIDDEN", "kill_session is fleet-wide session control"))
        assertEquals("The hub refused that", f.title)
        assertEquals("kill_session is fleet-wide session control", f.body)
        assertTrue(f.isError)
        assertEquals("E_FORBIDDEN: kill_session is fleet-wide session control", f.details)
    }

    @Test
    fun no_transcript_is_not_an_error() {
        val f = friendly(HubError.Tool("E_NO_TRANSCRIPT", "no transcript for claude session 0b63c561-66fd on htz"))
        assertFalse(f.isError)
        assertEquals("Nothing has been said yet", f.title)
        assertFalse("0b63c561" in f.body, "the UUID never reaches the summary line")
    }

    @Test
    fun a_missing_session_reads_as_gone() {
        val f = friendly(HubError.Tool("E_NOTFOUND", "no session 21520"))
        assertEquals("This session is gone", f.title)
    }

    @Test
    fun transport_and_unknown_throwables_keep_the_token_rule() {
        val f = friendly(RuntimeException("Bearer abc123"))
        assertFalse("abc123" in f.body)
        assertNull(f.details)
        assertEquals("Something went wrong", f.title)
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.ui.FriendlyTest'`
Expected: FAIL — unresolved `Friendly`/`friendly`.

- [ ] **Step 3: Implement**

`Friendly.kt`:
```kotlin
package dev.claudefleet.mobile.ui

import dev.claudefleet.mobile.net.HubError

/** What a banner shows: a title, a sentence, and the raw hub text behind a Details expander. */
data class Friendly(val title: String, val body: String, val isError: Boolean, val details: String? = null)

const val NO_TRANSCRIPT = "E_NO_TRANSCRIPT"

/**
 * Plain language in front, the hub's own words behind "Details". Built on
 * [explain], which already decides what may be repeated at all, so the token
 * rule holds here by construction.
 */
fun friendly(t: Throwable): Friendly {
    val raw = explain(t)
    return when (t) {
        is HubError.Tool -> when (t.code) {
            NO_TRANSCRIPT -> Friendly("Nothing has been said yet", "Send a prompt to start.", isError = false, details = raw)
            "E_NOTFOUND" -> Friendly("This session is gone", "It was killed or the fleet no longer lists it.", isError = true, details = raw)
            "E_FORBIDDEN" -> Friendly("The hub refused that", t.message, isError = true, details = raw)
            "E_CONFIRM_REQUIRED" -> Friendly("Needs a confirmation on the desktop", "Approve it there; this screen will follow.", isError = true, details = raw)
            "E_BG_SESSION" -> Friendly("Runs outside tmux", "This session has no terminal to type into.", isError = true, details = raw)
            else -> Friendly("The hub refused that", t.message, isError = true, details = raw)
        }
        is HubError.Unauthorized -> Friendly("This device was signed out", raw, isError = true)
        is HubError.Transport -> Friendly("Cannot reach the hub", raw, isError = true)
        is HubError -> Friendly("The hub answered oddly", raw, isError = true)
        else -> Friendly("Something went wrong", raw, isError = true)
    }
}
```

`Banners.kt` — replace `ErrorBanner`:
```kotlin
@Composable
fun ErrorBanner(error: Friendly?, onDismiss: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    if (error == null || !error.isError) return
    var showDetails by remember(error) { mutableStateOf(false) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(error.title, style = MaterialTheme.typography.titleSmall)
                    Text(error.body, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (error.details != null) TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide" else "Details") }
                if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            AnimatedVisibility(showDetails && error.details != null) {
                Text(
                    text = error.details.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(8.dp),
                )
            }
        }
    }
}
```
(`Icons.Outlined.Warning` needs `compose.materialIconsCore` — added in Task 5; to keep this task green on its own, use `Text("!")` here and swap to the icon in Task 5.)

`SessionViewModel.kt`: change `Local.error: String?` and `SessionUiState.error: String?` to `Friendly?`; add `val silent: Boolean = false` to both `Local` and `SessionUiState`. Where a read fails (`runGeneration`'s catch, currently `error = explain(t)`), write:
```kotlin
val f = friendly(t)
local.update { it.copy(loading = false, refreshing = false, loaded = true, silent = !f.isError && f.title == "Nothing has been said yet", error = f.takeIf { e -> e.isError }) }
```
In `send()`'s catch: `error = friendly(t)`. `SessionsViewModel.kt` and `HostsViewModel.kt`: the same type change (`error: Friendly?`, `error = friendly(t)`), and every `ErrorBanner(state.error, …)` call site compiles unchanged.

`SessionScreen.kt`: after the banners and before the `LazyColumn`, add
```kotlin
if (state.loaded && turns.isEmpty()) {
    EmptyConversation(state)
} else { /* existing LazyColumn */ }
```
with
```kotlin
@Composable
private fun EmptyConversation(state: SessionUiState) {
    val text = when {
        state.session == null -> "This session was killed."
        state.session.kind == "shell" -> "Shell session — no conversation to show."
        state.silent -> "Nothing has been said yet — send a prompt to start."
        else -> "No turns yet."
    }
    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(32.dp))
    }
}
```
(`Modifier.weight` is a `ColumnScope` extension: make `EmptyConversation` an extension `ColumnScope.EmptyConversation`.)

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:jvmTest`
Expected: PASS; if `SessionViewModelTest` asserted on `error` as a `String`, update those assertions to `error?.body` / `error?.details`.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain shared/src/commonTest
git commit -m "feat(ui): errors in plain language with the hub's words behind Details, and an empty state instead of E_NO_TRANSCRIPT"
```

---

### Task 5: Icons, the tab badge, app bars, pull-to-refresh

**Files:**
- Modify: `shared/build.gradle.kts:128-146` (add `implementation(compose.materialIconsCore)` to `commonMain.dependencies`)
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/theme/FleetIcons.kt`
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt:290-300` (tabs), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionsScreen.kt` (`SessionsBar` → `TopAppBar` + `FilterChip`; `PullToRefreshBox`), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionScreen.kt` (`SessionBar` → `TopAppBar` with back arrow and a rotating refresh icon), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/HostsScreen.kt` (`PullToRefreshBox`), `Banners.kt` (swap `Text("!")` for `Icons.Outlined.Warning`)
- Test: `shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/NoLetterTabIconsTest.kt` (new source scan)

**Interfaces:**
- Produces: `object FleetIcons { val Sessions: ImageVector; val Hosts: ImageVector }`.
- `SessionsScreen(... onToggleNeedsAttention, onRefresh ...)` signature unchanged; `SessionsUiState.attentionCount` feeds the badge via a new `App.kt` read of `sessions.state`.

- [ ] **Step 1: Write the failing test** (a source scan, the repo's own pattern — see `ToolsTheAppMayCallTest`; use its `sourceRoot()` helper from `host/Repo.kt`)

```kotlin
package dev.claudefleet.mobile.host

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The tab bar showed the letters S, H, S. A letter is not an icon. */
class NoLetterTabIconsTest {
    @Test
    fun the_navigation_bar_uses_image_vectors_not_the_first_letter_of_the_tab_name() {
        val app = Repo.file("shared/src/commonMain/kotlin/dev/claudefleet/mobile/App.kt").readText()
        assertFalse("name.take(1)" in app, "App.kt still draws the first letter as the icon")
        assertTrue("Icon(" in app && "FleetIcons." in app, "App.kt does not draw FleetIcons in the NavigationBar")
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.host.NoLetterTabIconsTest'`
Expected: FAIL on the first assertion.

- [ ] **Step 3: Implement**

`shared/build.gradle.kts`, inside `commonMain.dependencies { … }` after `api(libs.compose.ui.backhandler)`:
```kotlin
            // Send, Refresh, ArrowBack, MoreVert, Warning, Check, Close, Settings.
            // The core set only: ~50 icons, no extended pack.
            implementation(compose.materialIconsCore)
```

`FleetIcons.kt` — two simple vectors drawn from primitives so no icon pack is needed for them:
```kotlin
package dev.claudefleet.mobile.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** The two tab icons the core icon set does not have: a speech bubble and a server rack. */
object FleetIcons {
    val Sessions: ImageVector by lazy {
        ImageVector.Builder("Sessions", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                // rounded bubble
                moveTo(4f, 3f); lineTo(20f, 3f); quadTo(22f, 3f, 22f, 5f)
                lineTo(22f, 15f); quadTo(22f, 17f, 20f, 17f); lineTo(9f, 17f)
                lineTo(5f, 21f); lineTo(5f, 17f); lineTo(4f, 17f); quadTo(2f, 17f, 2f, 15f)
                lineTo(2f, 5f); quadTo(2f, 3f, 4f, 3f); close()
                // hollow inside
                moveTo(5f, 6f); lineTo(19f, 6f); lineTo(19f, 14f); lineTo(7.5f, 14f); lineTo(6f, 15.5f)
                lineTo(6f, 14f); lineTo(5f, 14f); close()
            }
        }.build()
    }

    val Hosts: ImageVector by lazy {
        ImageVector.Builder("Hosts", 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black)) {
                // two shelves
                moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 10f); lineTo(3f, 10f); close()
                moveTo(3f, 14f); lineTo(21f, 14f); lineTo(21f, 20f); lineTo(3f, 20f); close()
                // hollow shelf interiors
                moveTo(5f, 6f); lineTo(19f, 6f); lineTo(19f, 8f); lineTo(5f, 8f); close()
                moveTo(5f, 16f); lineTo(19f, 16f); lineTo(19f, 18f); lineTo(5f, 18f); close()
            }
        }.build()
    }
}
```
(Fill rule: the default `NonZero` would fill the hollow paths too — set `pathFillType = PathFillType.EvenOdd` on both `path(...)` calls, import `androidx.compose.ui.graphics.PathFillType`.)

`App.kt` tabs (replace the `NavigationBar` block):
```kotlin
NavigationBar {
    val attention by sessions.state.collectAsState()
    for (entry in Tab.entries) {
        NavigationBarItem(
            selected = tab == entry,
            onClick = { nav.select(entry) },
            icon = {
                val icon = when (entry) {
                    Tab.Sessions -> FleetIcons.Sessions
                    Tab.Hosts -> FleetIcons.Hosts
                    Tab.Settings -> Icons.Outlined.Settings
                }
                if (entry == Tab.Sessions && attention.attentionCount > 0) {
                    BadgedBox(badge = { Badge { Text("${attention.attentionCount}") } }) {
                        Icon(icon, contentDescription = entry.name)
                    }
                } else {
                    Icon(icon, contentDescription = entry.name)
                }
            },
            label = { Text(entry.name) },
        )
    }
}
```
Imports: `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.outlined.Settings`, `androidx.compose.material3.Badge`, `androidx.compose.material3.BadgedBox`, `androidx.compose.material3.Icon`, `dev.claudefleet.mobile.ui.theme.FleetIcons`. `sessions` is the `SessionsViewModel` already remembered above the `Scaffold`.

`SessionsScreen.kt` — replace `SessionsBar` with:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsBar(
    needsAttentionOnly: Boolean,
    attentionCount: Int,
    status: ConnectionStatus,
    onToggleNeedsAttention: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text("Sessions", style = MaterialTheme.typography.titleLarge)
                val live = when (status) {
                    is ConnectionStatus.Connected -> "live"
                    is ConnectionStatus.Reconnecting -> "reconnecting…"
                    is ConnectionStatus.Offline -> "offline"
                }
                Text(live, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        actions = {
            FilterChip(
                selected = needsAttentionOnly,
                onClick = onToggleNeedsAttention,
                label = { Text("Needs attention") },
                leadingIcon = { Icon(Icons.Outlined.Warning, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                trailingIcon = if (attentionCount > 0) ({ Badge { Text("$attentionCount") } }) else null,
                modifier = Modifier.padding(end = 12.dp),
            )
        },
    )
}
```
and wrap the `LazyColumn` in
```kotlin
PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
    LazyColumn(modifier = Modifier.fillMaxSize()) { /* unchanged */ }
}
```
(`androidx.compose.material3.pulltorefresh.PullToRefreshBox`, `ExperimentalMaterial3Api`). Do the same `PullToRefreshBox` wrap in `HostsScreen.kt` around its list, and delete its "Refresh" text button if it has one.

`SessionScreen.kt` — replace `SessionBar` with a `TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }, title = { Column { name; host } }, actions = { StatusChip(...); IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, "Refresh", modifier = Modifier.rotate(angle)) } })` where `angle` is `rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)))` when `busy`, else `0f`.

`Banners.kt`: replace the temporary `Text("!")` with `Icon(Icons.Outlined.Warning, contentDescription = null)`.

- [ ] **Step 4: Run tests and build**

Run: `./gradlew :shared:jvmTest && ./gradlew :androidApp:assembleDebug`
Expected: PASS; `BUILD SUCCESSFUL`. Install on the phone (`adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk`) and confirm the three tabs show icons and the badge shows the attention count.

- [ ] **Step 5: Commit**

```bash
git add shared/build.gradle.kts shared/src
git commit -m "feat(ui): real tab icons with an attention badge, Material top bars, and pull-to-refresh instead of a Refresh button"
```

---

### Task 6: Row anatomy and the composer

**Files:**
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionsScreen.kt` (`SessionRowItem`, `HostHeader` as `stickyHeader`, `ProjectHeader`), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionScreen.kt` (`PromptBox`), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionsViewModel.kt` (`SessionsUiState.nowSeconds`)
- Test: `shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/SessionsViewModelTest.kt` (add: state carries a clock reading and it advances)

**Interfaces:**
- `SessionsUiState.nowSeconds: Long` (unix seconds, refreshed every 30 s by a ticker in the view model, injectable `clock: () -> Long`).
- `SessionsViewModel(fleet, scope, clock: () -> Long = { Clock.System.now().epochSeconds })` — use `kotlinx.datetime`? Not a dependency. Use `kotlin.time.TimeSource`? Simplest portable: `expect fun epochSeconds(): Long` in `Platform.kt` with `actual`s (`System.currentTimeMillis()/1000` on Android/JVM, `NSDate().timeIntervalSince1970.toLong()` on iOS).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun the_state_carries_a_clock_that_ticks() = runTest {
    var now = 1_000L
    val vm = SessionsViewModel(fakeFleet, backgroundScope, clock = { now })
    val first = vm.state.first { it.nowSeconds > 0 }
    assertEquals(1_000L, first.nowSeconds)
    now = 1_040L
    advanceTimeBy(31_000)
    assertEquals(1_040L, vm.state.value.nowSeconds)
}
```
(`fakeFleet` is the fake `FleetState` the existing tests in this file build; reuse it.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.ui.SessionsViewModelTest'`
Expected: FAIL — no `clock` parameter / `nowSeconds`.

- [ ] **Step 3: Implement**

`Platform.kt`: add `internal expect fun epochSeconds(): Long`; `Platform.android.kt`: `internal actual fun epochSeconds(): Long = System.currentTimeMillis() / 1000`; `Platform.ios.kt`: `internal actual fun epochSeconds(): Long = platform.Foundation.NSDate().timeIntervalSince1970.toLong()`; the JVM target (`jvmMain`, check `shared/src` for it — create `Platform.jvm.kt` there if the target has its own source set) uses the same as Android.

`SessionsViewModel.kt`: add `private val clock: () -> Long = { epochSeconds() }` constructor parameter and a `private val now = MutableStateFlow(clock())` ticked by
```kotlin
init { scope.launch { while (isActive) { delay(30_000); now.value = clock() } } }
```
fold `now` into the `combine` and set `nowSeconds = now` on the state. `SessionsUiState` gains `val nowSeconds: Long = 0`.

`SessionsScreen.kt` `SessionRowItem`:
```kotlin
@Composable
private fun SessionRowItem(row: SessionRow, nowSeconds: Long, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { StatusDot(row.claudeStatus, row.stuckKind) },
        headlineContent = {
            Column {
                Text(row.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val pct = row.contextPct
                if (pct != null) {
                    LinearProgressIndicator(
                        progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.width(60.dp).height(2.dp).padding(top = 2.dp),
                        color = if (pct >= 80) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        supportingContent = {
            val line = row.supportingLine(nowSeconds)
            if (line != null) Text(line, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                StatusChip(claudeStatus = row.claudeStatus, stuckKind = row.stuckKind)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    relativeTime(row.lastActivityAt, nowSeconds)?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    row.ciStatus?.let { ci ->
                        val tone = when (ci) { "passing" -> StatusTone.COMPLETED; "failing" -> StatusTone.FAILED; else -> StatusTone.IDLE }
                        Box(Modifier.padding(start = 4.dp).size(6.dp).clip(CircleShape).background(LocalStatusColors.current(tone).dot))
                    }
                }
            }
        },
    )
    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
}
```
Pass `state.nowSeconds` through from `SessionsScreen`. Make the host header a `stickyHeader(key = "host-${host.alias}") { HostHeader(...) }` (`@OptIn(ExperimentalFoundationApi::class)`), with `Surface(color = MaterialTheme.colorScheme.surfaceContainer)` and 40dp height. `ProjectHeader` padding `top = 12.dp, bottom = 4.dp`, style `labelLarge`, colour `secondary`.

`SessionScreen.kt` `PromptBox`:
```kotlin
@Composable
private fun PromptBox(state: SessionUiState, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            val why = when {
                state.readOnly -> "This device is paired read-only."
                !state.connected -> "The hub is offline; the prompt will not be delivered."
                state.session == null -> "This session is gone."
                else -> null
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = state.draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    enabled = !state.sending && !state.readOnly,
                    placeholder = { Text("Message ${state.session?.displayName ?: "session"}…") },
                    shape = CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (state.canSend) onSend() }),
                    maxLines = 6,
                )
                FilledIconButton(onClick = onSend, enabled = state.canSend, modifier = Modifier.size(48.dp)) {
                    if (state.sending) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "Send")
                }
            }
            if (why != null) Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
```

- [ ] **Step 4: Run tests and build; install and look**

Run: `./gradlew :shared:jvmTest && ./gradlew :androidApp:assembleDebug`
Expected: PASS; `BUILD SUCCESSFUL`. On the phone: rows show a dot, a name, a clean second line, a chip and an age; no ANSI text anywhere; the composer is a pill with a send icon.

- [ ] **Step 5: Commit**

```bash
git add shared/src
git commit -m "feat(ui): rows lead with a status dot and end with an age, the composer is a pill that says why it is disabled"
```

---

### Task 7: Connection truth — contract check, and Send that follows the hub, not the stream

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/net/HubContract.kt`
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/net/EventStream.kt:183-235` (`Ready.contract`), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/data/FleetRepository.kt` (refuse out-of-range; new `ConnectionStatus.Offline` reason), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/data/SessionActions.kt` (+ `ping()`), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/net/HubClient.kt` (+ `fleetHealth()`), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionViewModel.kt` (`connected` from a probe)
- Test: `shared/src/commonTest/kotlin/dev/claudefleet/mobile/net/HubContractTest.kt` (extend), `shared/src/commonTest/kotlin/dev/claudefleet/mobile/net/EventStreamTest.kt` (extend), `shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/SessionViewModelTest.kt` (extend)

**Interfaces:**
- `const val MIN_HUB_CONTRACT: Int`, `const val MAX_HUB_CONTRACT: Int` — copy the current values from `claude-fleet/src-tauri/src/backend/contract.rs` (`grep -n 'MIN_HUB_CONTRACT\|MAX_HUB_CONTRACT' …/contract.rs`) at implementation time and cite the commit in the KDoc.
- `sealed interface ContractVerdict { object Ok; data class HubTooOld(val revision: Int); data class AppTooOld(val revision: Int) }`, `fun contractVerdict(revision: Int?): ContractVerdict` (null → Ok: a hub that names no contract is pre-contract and is trusted, as the desktop does — verify this in `contract.rs` and mirror it).
- `HubEvent.Ready(version, kinds, contract: Int?)`.
- `SessionActions.ping(): Boolean` (true when `fleet_health` answers).
- `SessionUiState.connected` = last probe result OR stream connected.

- [ ] **Step 1: Write the failing tests**

In `EventStreamTest.kt`:
```kotlin
@Test
fun ready_carries_the_contract_revision_when_the_hub_names_one() {
    val ready = frameToEvent("ready", """{"version":"0.2.31","kinds":["session"],"contract":7}""")
    assertEquals(HubEvent.Ready(version = "0.2.31", kinds = listOf("session"), contract = 7), ready)
    val old = frameToEvent("ready", """{"version":"0.2.20","kinds":["session"]}""")
    assertNull((old as HubEvent.Ready).contract)
}
```
In `HubContractTest.kt`:
```kotlin
@Test
fun the_verdict_matches_the_desktops_range() {
    assertEquals(ContractVerdict.Ok, contractVerdict(null))
    assertEquals(ContractVerdict.Ok, contractVerdict(MIN_HUB_CONTRACT))
    assertEquals(ContractVerdict.Ok, contractVerdict(MAX_HUB_CONTRACT))
    assertEquals(ContractVerdict.HubTooOld(MIN_HUB_CONTRACT - 1), contractVerdict(MIN_HUB_CONTRACT - 1))
    assertEquals(ContractVerdict.AppTooOld(MAX_HUB_CONTRACT + 1), contractVerdict(MAX_HUB_CONTRACT + 1))
}
```
In `SessionViewModelTest.kt` (fake `SessionActions` gains `ping`):
```kotlin
@Test
fun send_is_allowed_while_the_stream_is_down_but_the_hub_answers() = runTest {
    fleet.status.value = ConnectionStatus.Reconnecting(attempt = 3, reason = "stream dropped")
    actions.pingAnswer = true
    val vm = SessionViewModel(1, fleet, actions, backgroundScope)
    vm.load().join(); vm.onDraftChange("go on")
    assertTrue(vm.state.first { it.loaded }.canSend)
}

@Test
fun send_is_refused_when_the_hub_itself_does_not_answer() = runTest {
    fleet.status.value = ConnectionStatus.Reconnecting(attempt = 3, reason = "stream dropped")
    actions.pingAnswer = false
    val vm = SessionViewModel(1, fleet, actions, backgroundScope)
    vm.load().join(); vm.onDraftChange("go on")
    assertFalse(vm.state.first { it.loaded }.canSend)
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.net.*' --tests 'dev.claudefleet.mobile.ui.SessionViewModelTest'`
Expected: FAIL — `contract` parameter, `contractVerdict`, `ping` unresolved.

- [ ] **Step 3: Implement**

`HubContract.kt`:
```kotlin
package dev.claudefleet.mobile.net

/**
 * The hub wire-contract revisions this build understands, mirrored from the
 * desktop's `src-tauri/src/backend/contract.rs` (claude-fleet <commit>). A hub
 * outside the range is refused the way the desktop refuses it: the banner says
 * which side is behind, and no row event from that hub is applied.
 */
const val MIN_HUB_CONTRACT: Int = <value>
const val MAX_HUB_CONTRACT: Int = <value>

sealed interface ContractVerdict {
    data object Ok : ContractVerdict
    data class HubTooOld(val revision: Int) : ContractVerdict
    data class AppTooOld(val revision: Int) : ContractVerdict
}

fun contractVerdict(revision: Int?): ContractVerdict = when {
    revision == null -> ContractVerdict.Ok
    revision < MIN_HUB_CONTRACT -> ContractVerdict.HubTooOld(revision)
    revision > MAX_HUB_CONTRACT -> ContractVerdict.AppTooOld(revision)
    else -> ContractVerdict.Ok
}

fun ContractVerdict.sentence(): String? = when (this) {
    ContractVerdict.Ok -> null
    is ContractVerdict.HubTooOld -> "This hub is too old for this app (contract $revision). Update the hub."
    is ContractVerdict.AppTooOld -> "This app is too old for this hub (contract $revision). Update the app."
}
```
(`<value>`: fill from `contract.rs` when implementing; never guess.)

`EventStream.kt`: `data class Ready(val version: String?, val kinds: List<String>, val contract: Int? = null)`; in the `ready` branch add `contract = (fields["contract"] as? JsonPrimitive)?.content?.toIntOrNull()`.

`FleetRepository.kt` (`follow`): on `HubEvent.Ready`, compute `contractVerdict(ready.contract)`; when not `Ok`, set `status` to `ConnectionStatus.Offline(verdict.sentence()!!)`, skip the resync, and ignore every later frame of this connection (keep the reconnect loop as it is, so an upgrade on either side is picked up on the next connect).

`HubClient.kt`: `suspend fun fleetHealth(): Boolean = call("fleet_health") { (it as? JsonObject)?.get("db_ready")?.asBooleanOrNull() == true }`. `SessionActions`: `suspend fun ping(): Boolean` (Hub impl: `runCatching { session.withClient { it.fleetHealth() } }.getOrDefault(false)`).

`SessionViewModel.kt`: replace `connected()` with a probe: keep `private val probe = MutableStateFlow<Boolean?>(null)`; whenever `fleet.status` is not `Connected`, launch (debounced 2 s, one in flight) `probe.value = actions.ping()`; `connected = status is Connected || probe.value == true`. `canSendNow` reads the same.

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :shared:jvmTest`
Expected: PASS. `ToolsTheAppMayCallTest` still passes (`fleet_health` is a client tool).

- [ ] **Step 5: Commit**

```bash
git add shared/src
git commit -m "feat(net): refuse a hub outside the contract range, and let Send follow the hub rather than the stream"
```

---

## Self-review (done while writing)

- Spec coverage: 0.1 → T1, T6; 0.2 → T2; 0.3 → T3; 0.4 → T5; 0.5 → T4; 0.6 → T6; 0.7 → T7. Platform connectivity (`no network` vs `hub unreachable`) is folded into T7's probe rather than a separate `expect`: the probe answers the question the banner needs and costs no platform code. Note this in the PR.
- Type consistency: `Friendly` (T4) is what `ErrorBanner` takes and what all three view models hold; `nowSeconds` (T6) is read by `supportingLine` and `relativeTime` (T2); `StatusDot`/`LocalStatusColors` (T3) are used by T6.
- Placeholders: `<value>` in T7 is a deliberate read-at-implementation-time from the sibling repo, not a TBD; the step says exactly where to read it.
