# Phone as a pager — Phase 1 (the screen is a control) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A blocked agent is answered in one tap, a session can be steered, stopped and restarted from the phone, the transcript reads as prose, and what needs the operator is pinned on top of the list.

**Architecture:** `SessionActions` grows one method per hub tool (all in the hub's `CLIENT_TOOLS`); pure `commonMain` functions compute the "Waiting for you" card, the attention inbox membership and the transcript grouping; `SessionViewModel` owns the answer → wait → collapse flow; composables draw state. Works against today's hub (chips fall back to the terminal view) and lights up option chips and Enter/Esc when the hub carries `pending_input` and accepts `send_prompt { keys }` (plan `2026-09-21-pager-hub-contract.md` in claude-fleet).

**Tech Stack:** as Phase 0, plus `com.mikepenz:multiplatform-markdown-renderer-m3` (Task 7; evaluate the iOS footprint before adding — if `:shared:assemble` for `iosSimulatorArm64` grows the framework by more than 2 MB, use the minimal inline parser described in Task 7 instead).

**Spec:** `docs/superpowers/specs/2026-09-21-phone-as-pager-design.md`, sections 1.1–1.7. Phase 0 (`2026-09-21-pager-phase-0.md`) must be merged first: this plan uses `Friendly`, `StatusTone`, `LocalStatusColors`, `Activity`, `relativeTime`, `SessionUiState.silent`, `SessionsUiState.nowSeconds` from it.

## Global Constraints

Same as Phase 0 (no new dependency except the markdown renderer; token rule; one rule one place; `update {}`; `ToolsTheAppMayCallTest`; Conventional Commits; `./gradlew :shared:jvmTest` green before every commit; `:androidApp:assembleDebug` after UI tasks; branch `feat/ux-pager`; never push from a task). Additionally:

- Every write tool call is gated on `Credentials.canWrite` exactly as `send_prompt` is; a read-only device sees no chip, menu item or button that would be refused.
- `send_prompt` with an empty body is a bare Enter on the hub; a `keys` value is preferred when the hub's contract revision is ≥ `HUB_CONTRACT_KEYS` (Task 2 defines it; fill from the hub plan's merged contract bump).
- Nothing is sent by voice alone (Task 9): speech lands in the draft or in the REPL line with `submit: false`.

## File map

| File | Responsibility |
|---|---|
| `net/HubClient.kt`, `data/SessionActions.kt`, `model/Receipts.kt` | new tool calls and their result types |
| `model/SessionRow.kt` | `pendingInput: PendingInput?` |
| `model/PendingInput.kt` (new) | the wire type |
| `ui/Blocked.kt` (new) | `blockedCard(row, contract): BlockedCard?` — chips per state |
| `ui/components/BlockedCard.kt` (new) | draws it, terminal expander |
| `ui/SessionViewModel.kt` | answer flow, overflow actions, cost, older turns, new-reply flag |
| `ui/SessionScreen.kt` | card, menu, status strip, quick replies, markdown turns, tool groups, load-older, new-reply pill |
| `ui/components/StatusStrip.kt` (new) | `● working 2m14s · ctx 62 % · $1.84 · sonnet` |
| `ui/components/ToolRows.kt` (new) | grouped/collapsed tool rows |
| `model/Conversation.kt` | `context`, `events`, four new item kinds |
| `ui/QuickReplies.kt` (new) + `store/Prefs.kt` (new, expect/actual) | chips and draft history, persisted per device |
| `ui/Attention.kt` (new) | `attentionRows(sessions, hosts, now): List<AttentionRow>` |
| `ui/components/AttentionInbox.kt` (new), `ui/SessionsScreen.kt`, `ui/SessionsViewModel.kt` | the pinned inbox |
| `ui/Speech.kt` (new, expect) + `androidMain`/`iosMain` actuals | voice to draft |

---

### Task 1: The hub calls the phone was missing

**Files:**
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/net/HubClient.kt`, `shared/src/commonMain/kotlin/dev/claudefleet/mobile/data/SessionActions.kt`, `shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/Receipts.kt`
- Test: `shared/src/commonTest/kotlin/dev/claudefleet/mobile/net/HubClientTest.kt` (extend, `MockEngine` pattern already there)

**Interfaces:** (all on `HubClient` and mirrored on `SessionActions`; `HubSessionActions` delegates through `session.withClient`)
```kotlin
suspend fun sendKeys(sessionId: Long, key: String): SendPromptResult          // send_prompt {keys}
suspend fun capture(sessionId: Long, maxLines: Int = 40): String              // capture_session (plain text result)
suspend fun waitForTurn(sessionId: Long, turn: Long, timeoutS: Int = 30): WaitResult // wait_for_session {until:"turn_gt"}
suspend fun restart(sessionId: Long): Unit                                    // restart_session
suspend fun safeKill(sessionId: Long): Unit                                   // safe_kill_session
suspend fun kill(sessionId: Long): Unit                                       // kill_session
suspend fun setTags(sessionId: Long, tags: List<String>): Unit                // set_session_tags
suspend fun rename(sessionId: Long, friendlyName: String): Unit               // set_friendly_name
suspend fun usage(sessionId: Long): UsageSummary                              // usage_report {session_id}
```
`data class WaitResult(val status: String, @SerialName("claude_status") val claudeStatus: String? = null, @SerialName("turn_seq") val turnSeq: Long = 0)`; `data class UsageSummary(@SerialName("cost_micros") val costMicros: Long = 0, val model: String? = null)` — read the exact field names from `claude-fleet/docs/control-api-reference.md` (`usage_report`, `wait_for_session`) at implementation time and fix the `@SerialName`s to match; the tests below use the names as the reference prints them today.

- [ ] **Step 1: Write the failing tests** (one per call; the pattern: a `MockEngine` that asserts the tool name and arguments in the JSON-RPC body and answers a `result`)

```kotlin
@Test
fun keys_are_sent_as_the_keys_argument_with_an_empty_prompt() = runTest {
    val client = clientAnswering { body ->
        assertEquals("send_prompt", body.tool())
        assertEquals("Escape", body.args()["keys"]?.jsonPrimitive?.content)
        assertEquals("", body.args()["prompt"]?.jsonPrimitive?.content)
        """{"delivered":true,"session_id":7,"turn_seq_before":3}"""
    }
    assertEquals(3L, client.sendKeys(7, "Escape").turnSeqBefore)
}

@Test
fun capture_returns_the_pane_text_verbatim() = runTest {
    val client = clientAnswering { body -> assertEquals("capture_session", body.tool()); assertEquals(40, body.args()["max_lines"]?.jsonPrimitive?.int); "\"❯ 1. Yes\\n  2. No\"" }
    assertEquals("❯ 1. Yes\n  2. No", client.capture(7))
}

@Test
fun wait_for_turn_passes_the_turn_and_a_timeout() = runTest {
    val client = clientAnswering { body ->
        assertEquals("wait_for_session", body.tool())
        assertEquals("turn_gt", body.args()["until"]?.jsonPrimitive?.content)
        assertEquals(3, body.args()["turn"]?.jsonPrimitive?.int)
        """{"status":"satisfied","claude_status":"idle","turn_seq":4}"""
    }
    assertEquals("satisfied", client.waitForTurn(7, 3).status)
}

@Test
fun lifecycle_and_metadata_calls_name_their_tools() = runTest {
    for ((call, tool, argKey) in listOf<Triple<suspend (HubClient) -> Unit, String, String>>(
        Triple({ it.restart(7) }, "restart_session", "session_id"),
        Triple({ it.safeKill(7) }, "safe_kill_session", "session_id"),
        Triple({ it.kill(7) }, "kill_session", "session_id"),
        Triple({ it.setTags(7, listOf("wip")) }, "set_session_tags", "tags"),
        Triple({ it.rename(7, "ADR") }, "set_friendly_name", "friendly_name"),
    )) {
        val client = clientAnswering { body -> assertEquals(tool, body.tool()); assertTrue(argKey in body.args()); "7" }
        call(client)
    }
}
```
(`clientAnswering`, `tool()`, `args()` are the helpers `HubClientTest` already has or trivially gets: parse the request body as JSON, read `params.name` / `params.arguments`.)

- [ ] **Step 2: Run to verify they fail** — `./gradlew :shared:jvmTest --tests 'dev.claudefleet.mobile.net.HubClientTest'` → unresolved references.

- [ ] **Step 3: Implement** — one `call(...)` per method in `HubClient` following `sendPrompt`'s shape; `capture` deserialises with `{ (it as JsonPrimitive).content }` (the tool returns plain text, which `payloadOf` hands over as a primitive — check `payloadOf` and, if it only accepts objects, extend it to pass a primitive through); `kill`/`restart`/`safeKill`/`setTags`/`rename` deserialise with `{ }` (ignore the payload). Add the same nine methods to the `SessionActions` interface and `HubSessionActions`. Add `WaitResult` and `UsageSummary` to `Receipts.kt`.

- [ ] **Step 4: Run** — `./gradlew :shared:jvmTest` → PASS, including `ToolsTheAppMayCallTest` (all nine are client tools; if it fails, the tool is admin-only and must not be called — remove it and say so in the commit).

- [ ] **Step 5: Commit** — `git commit -m "feat(net): the session calls a phone needs — keys, capture, wait, restart, kill, tags, rename, usage"`

---

### Task 2: `pending_input` and the blocked card, as data

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/PendingInput.kt`, `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/Blocked.kt`
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/SessionRow.kt` (`pendingInput`), `shared/src/commonMain/kotlin/dev/claudefleet/mobile/net/HubContract.kt` (`HUB_CONTRACT_KEYS`)
- Test: `shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/BlockedTest.kt`, `SessionRowTest.kt` (extend)

**Interfaces:**
```kotlin
@Serializable data class PendingOption(val n: Int, val label: String, val selected: Boolean = false)
@Serializable data class PendingInput(val kind: String, val question: String? = null, val options: List<PendingOption> = emptyList())
// SessionRow: @SerialName("pending_input") val pendingInput: PendingInput? = null

sealed interface Answer { data class Option(val n: Int, val label: String) : Answer; data object Enter : Answer; data object Escape : Answer; data object Interrupt : Answer; data class Text(val text: String) : Answer }
data class BlockedCard(val headline: String, val answers: List<Answer>, val explain: String? = null, val offerRestart: Boolean = false, val terminalAvailable: Boolean = true)
fun blockedCard(row: SessionRow, hubContract: Int?): BlockedCard?   // null when not blocked/stuck
const val HUB_CONTRACT_KEYS: Int = <the revision the hub plan lands; until merged, Int.MAX_VALUE so keys are never offered>
```

- [ ] **Step 1: Write the failing tests**

```kotlin
class BlockedTest {
    private fun row(status: String? = "blocked", stuck: String? = null, activity: String? = null, pending: PendingInput? = null) =
        SessionRow(id = 1, tmuxName = "s", claudeStatus = status, stuckKind = stuck, currentActivity = activity, pendingInput = pending)

    @Test fun not_blocked_means_no_card() { assertNull(blockedCard(row(status = "working"), 9)) }

    @Test
    fun options_become_answers_and_the_question_is_the_headline() {
        val p = PendingInput("permission", "Recreate turanga?", listOf(PendingOption(1, "Yes", true), PendingOption(3, "No")))
        val card = blockedCard(row(pending = p, activity = "waiting for input: ☐ Recreate turanga?"), HUB_CONTRACT_KEYS)!!
        assertEquals("Recreate turanga?", card.headline)
        assertEquals(listOf(Answer.Option(1, "Yes"), Answer.Option(3, "No"), Answer.Enter, Answer.Escape), card.answers)
    }

    @Test
    fun without_pending_input_the_headline_comes_from_the_activity_line_and_only_keys_are_offered() {
        val card = blockedCard(row(activity = "waiting for input: ☐ Recreate turanga?"), HUB_CONTRACT_KEYS)!!
        assertEquals("☐ Recreate turanga?", card.headline)
        assertEquals(listOf(Answer.Enter, Answer.Escape), card.answers)
    }

    @Test
    fun an_old_hub_offers_the_terminal_and_no_key_chips() {
        val card = blockedCard(row(activity = "waiting for input: x?"), hubContract = HUB_CONTRACT_KEYS - 1)!!
        assertTrue(card.answers.isEmpty()); assertTrue(card.terminalAvailable)
    }

    @Test
    fun stuck_kinds_map_to_fixed_cards() {
        assertEquals(listOf(Answer.Enter), blockedCard(row(stuck = "press_enter"), HUB_CONTRACT_KEYS)!!.answers)
        assertEquals(listOf(Answer.Text("y"), Answer.Text("n")), blockedCard(row(stuck = "trust_prompt"), HUB_CONTRACT_KEYS)!!.answers)
        val auth = blockedCard(row(stuck = "auth_menu"), HUB_CONTRACT_KEYS)!!
        assertTrue(auth.answers.isEmpty()); assertTrue(auth.offerRestart); assertEquals("Needs a login on this host", auth.explain)
        assertTrue(blockedCard(row(stuck = "oom"), HUB_CONTRACT_KEYS)!!.offerRestart)
    }
}
```
And in `SessionRowTest`: `pending_input` parses (`{"id":1,"pending_input":{"kind":"input","options":[{"n":2,"label":"1 day"}]}}` → `options[0].selected == false`) and is null when absent.

- [ ] **Step 2: Run to verify they fail** — unresolved `blockedCard`, `PendingInput`.

- [ ] **Step 3: Implement**

`Blocked.kt`:
```kotlin
fun blockedCard(row: SessionRow, hubContract: Int?): BlockedCard? {
    val stuck = row.stuckKind
    val blocked = row.claudeStatus == "blocked"
    if (stuck == null && !blocked) return null
    val keys = hubContract != null && hubContract >= HUB_CONTRACT_KEYS
    val keyAnswers = if (keys) listOf(Answer.Enter, Answer.Escape) else emptyList()
    return when (stuck) {
        "press_enter" -> BlockedCard("Press Enter to continue", if (keys) listOf(Answer.Enter) else emptyList())
        "trust_prompt" -> BlockedCard("Trust this folder?", listOf(Answer.Text("y"), Answer.Text("n")))
        "auth_menu" -> BlockedCard("Login needed", emptyList(), explain = "Needs a login on this host", offerRestart = true)
        "reconnect" -> BlockedCard("Reconnecting to Anthropic", emptyList(), explain = "The REPL lost its connection", offerRestart = true)
        "oom" -> BlockedCard("Out of memory", emptyList(), explain = "The host ran out of memory", offerRestart = true)
        null -> {
            val p = row.pendingInput
            val headline = p?.question ?: Activity.pending(row.currentActivity) ?: "Waiting for input"
            val options = p?.options.orEmpty().map { Answer.Option(it.n, it.label) }
            BlockedCard(headline, options + keyAnswers)
        }
        else -> BlockedCard(stuck.replace('_', ' '), keyAnswers, offerRestart = true)
    }
}
```

- [ ] **Step 4: Run** — `./gradlew :shared:jvmTest` → PASS.
- [ ] **Step 5: Commit** — `git commit -m "feat(ui): the blocked card as data — options, keys and fixed cards per stuck kind"`

---

### Task 3: Answering from the card

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/components/BlockedCard.kt`
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionViewModel.kt`, `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionScreen.kt`, `shared/src/commonMain/kotlin/dev/claudefleet/mobile/data/FleetRepository.kt` (expose `hubContract: StateFlow<Int?>` from the last `Ready`)
- Test: `SessionViewModelTest.kt` (extend)

**Interfaces:**
- `SessionUiState.card: BlockedCard?`, `SessionUiState.answering: Boolean`, `SessionUiState.terminal: String?` (the capture, when expanded), `SessionUiState.stillWaiting: Boolean`.
- `SessionViewModel.answer(a: Answer): Job`, `SessionViewModel.showTerminal(): Job`, `SessionViewModel.hideTerminal()`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun answering_an_option_sends_its_number_waits_for_the_turn_and_clears_the_card() = runTest {
    fleet.rows.value = listOf(blockedRow(pending = PendingInput("permission", "Do it?", listOf(PendingOption(1, "Yes")))))
    fleet.contract.value = HUB_CONTRACT_KEYS
    val vm = SessionViewModel(1, fleet, actions, backgroundScope)
    vm.load().join()
    assertNotNull(vm.state.value.card)
    vm.answer(Answer.Option(1, "Yes")).let { job ->
        assertTrue(vm.state.first { it.answering }.answering)
        assertEquals("1", actions.sentPrompts.single())
        fleet.rows.value = listOf(blockedRow().copy(claudeStatus = "working"))   // the hub moved on
        job.join()
    }
    assertNull(vm.state.value.card); assertFalse(vm.state.value.answering)
    assertEquals(listOf(1L to 0L), actions.waited, "waited on turn_gt with the send's turn_seq_before")
}

@Test
fun enter_and_escape_go_through_keys() = runTest { /* same arrangement */ vm.answer(Answer.Enter).join(); assertEquals(listOf("Enter"), actions.sentKeys) }

@Test
fun a_timeout_keeps_the_card_and_says_still_waiting() = runTest {
    actions.waitAnswer = WaitResult(status = "timeout")
    /* arrange blocked row */ vm.answer(Answer.Enter).join()
    assertNotNull(vm.state.value.card); assertTrue(vm.state.value.stillWaiting)
}

@Test
fun show_terminal_captures_once_and_refreshes_on_session_events() = runTest {
    actions.captureAnswer = "❯ 1. Yes"
    vm.showTerminal().join(); assertEquals("❯ 1. Yes", vm.state.value.terminal)
    actions.captureAnswer = "❯ 2. No"; fleet.emitSessionChange(1); advanceTimeBy(SESSION_EVENT_DEBOUNCE.inWholeMilliseconds + 1)
    assertEquals("❯ 2. No", vm.state.value.terminal)
    vm.hideTerminal(); assertNull(vm.state.value.terminal)
}
```
(The fake `SessionActions` in the test file records `sentPrompts`, `sentKeys`, `waited`, and answers `waitAnswer`/`captureAnswer`; the fake `FleetState` gains `contract: MutableStateFlow<Int?>`.)

- [ ] **Step 2: Run to verify they fail.**

- [ ] **Step 3: Implement**

`FleetRepository`: `val hubContract = MutableStateFlow<Int?>(null)` set from each `Ready`; add `val hubContract: StateFlow<Int?>` to `FleetState`.

`SessionViewModel`: fold `fleet.hubContract` into `combine`; `card = blockedCard(row, contract)`; `answer(a)`:
```kotlin
fun answer(a: Answer): Job = scope.launch {
    if (readOnly || local.value.answering) return@launch
    local.update { it.copy(answering = true, stillWaiting = false, error = null) }
    try {
        val receipt = when (a) {
            is Answer.Option -> actions.sendPrompt(sessionId, a.n.toString())
            is Answer.Text -> actions.sendPrompt(sessionId, a.text)
            Answer.Enter -> actions.sendKeys(sessionId, "Enter")
            Answer.Escape -> actions.sendKeys(sessionId, "Escape")
            Answer.Interrupt -> actions.sendKeys(sessionId, "C-c")
        }
        val wait = actions.waitForTurn(sessionId, receipt.turnSeqBefore, timeoutS = 30)
        local.update { it.copy(answering = false, stillWaiting = wait.status != "satisfied") }
        requestRead(first = false)
    } catch (e: CancellationException) { throw e } catch (t: Throwable) {
        local.update { it.copy(answering = false, error = friendly(t)) }
    }
}
```
The card disappears on its own when the row leaves `blocked` (it is derived), which is what the first test asserts. `showTerminal` sets `terminalShown = true` and captures; the existing session-event debounce path re-captures while shown.

`BlockedCard.kt` composable: a `Card` with `tertiaryContainer` (amber) or `errorContainer` (stuck), headline `titleMedium`, `FlowRow` of `AssistChip`s for `answers` (label `"${n} · $label"` for options, `Enter`, `Esc`), a `TextButton("Restart")` when `offerRestart` (calls Task 4's restart), `explain` as `bodySmall`, `TextButton("Show terminal"/"Hide")`, and the capture in a horizontally scrollable monospace `Text` on `surfaceContainerHighest`. While `answering`, chips are disabled and a 16dp spinner shows; `stillWaiting` adds "still waiting…". Placed in `SessionScreen` between the list and the composer; hides the quick-reply row (Task 6).

- [ ] **Step 4: Run tests and build; install and answer a real blocked session from the phone.**
- [ ] **Step 5: Commit** — `git commit -m "feat(session): answer a blocked agent from a card — options, Enter, Esc — and watch it move on"`

---

### Task 4: The overflow menu

**Files:**
- Modify: `SessionViewModel.kt` (`rename`, `setTags`, `restart`, `safeKill`, `kill`; `SessionUiState.safeKillState`), `SessionScreen.kt` (`⋮` menu, confirm sheets, tag editor, rename dialog)
- Test: `SessionViewModelTest.kt` (extend)

**Interfaces:** `SessionViewModel.rename(name: String)`, `.setTags(tags: List<String>)`, `.restart()`, `.safeKill()`, `.kill()`: `Job`; `SessionUiState.canManage: Boolean` (= `!readOnly && session != null && !session.isController`), `SessionUiState.safeKillState: String?` (from the row's `safe_kill_state` — add `@SerialName("safe_kill_state") val safeKillState: String? = null` to `SessionRow`).

- [ ] **Step 1: Write the failing tests**
```kotlin
@Test fun restart_calls_the_hub_and_refetches() = runTest { vm.restart().join(); assertEquals(listOf(1L), actions.restarted); assertTrue(actions.conversationReads >= 2) }
@Test fun kill_is_refused_for_the_controller_and_for_a_readonly_device() = runTest {
    fleet.rows.value = listOf(row().copy(isController = true)); assertFalse(vm.state.first { it.loaded }.canManage); vm.kill().join(); assertTrue(actions.killed.isEmpty())
}
@Test fun a_confirm_required_refusal_reads_as_a_desktop_confirmation() = runTest {
    actions.killError = HubError.Tool("E_CONFIRM_REQUIRED", "confirm on the desktop"); vm.kill().join()
    assertEquals("Needs a confirmation on the desktop", vm.state.value.error?.title)
}
@Test fun safe_kill_state_follows_the_row() = runTest { fleet.rows.value = listOf(row().copy(safeKillState = "ready")); assertEquals("ready", vm.state.first { it.loaded }.safeKillState) }
```
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement** — each action: guard on `canManage`, set `busy`, call, `requestRead`, catch → `friendly`. Screen: `IconButton(MoreVert)` + `DropdownMenu` with *Rename…* (an `AlertDialog` with a `TextField`), *Tags…* (`FlowRow` of `InputChip`s with remove, plus an add field), *Restart* (`AlertDialog` confirm), *Retire safely* (calls `safeKill`; a `SuggestionChip` in the status strip shows `safeKillState` while non-null), *Kill now* (red text; the dialog's confirm button is enabled only after an 800 ms `LaunchedEffect` delay — "hold to confirm" on a phone is a delayed enable, not a gesture). Hidden when `!canManage`.
- [ ] **Step 4: Run tests and build.**
- [ ] **Step 5: Commit** — `git commit -m "feat(session): rename, tag, restart, retire safely and kill from the phone, with the desktop's confirmations explained"`

---

### Task 5: Status strip, and the conversation model stops dropping things

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/components/StatusStrip.kt`
- Modify: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/Conversation.kt` (`context`, `events`, item kinds `subagent`, `compact`, `command`, `interrupt`), `SessionViewModel.kt` (cost via `actions.usage`, cached 60 s), `SessionScreen.kt` (strip replaces the chip in the bar; `Item` renders the new kinds)
- Test: `ConvItemTest.kt` (extend), `SessionViewModelTest.kt` (extend), `ui/components/StatusStripTest.kt` (pure text builder)

**Interfaces:**
```kotlin
@Serializable data class ConvContext(val tokens: Long? = null, val window: Long? = null, val pct: Double? = null, val stale: Boolean = false)
// Conversation: val context: ConvContext? = null, val events: List<JsonElement> = emptyList()
// ConvItem: Subagent(agentType: String?, description: String?, result: String?, done: Boolean), Compact(preTokens: Long?), Command(name: String), Interrupt
fun statusStripText(row: SessionRow?, context: ConvContext?, costMicros: Long?, model: String?, nowSeconds: Long): String
```
Read the exact JSON shapes of the four kinds from `claude-fleet/crates/fleet-core/src/service/transcript.rs` (`ConvItem` enum, `#[serde(tag = "kind")]`) at implementation time and mirror the field names.

- [ ] **Step 1: Write the failing tests**
```kotlin
@Test fun context_and_new_kinds_parse_and_nothing_is_unsupported_anymore() { /* JSON with context{pct:62.5,stale:false} and items subagent/compact/command/interrupt → typed, and an invented kind still → Unsupported */ }
@Test fun the_strip_reads_working_with_elapsed_ctx_cost_and_model() {
    val row = SessionRow(id = 1, tmuxName = "s", claudeStatus = "working", lastTurnAt = 1_000, usageModel = "sonnet")
    assertEquals("● working 2 min · ctx 62 % · $1.84 · sonnet", statusStripText(row, ConvContext(pct = 62.4), 1_840_000, "sonnet", nowSeconds = 1_134))
    assertEquals("idle since 2 h", statusStripText(row.copy(claudeStatus = "idle", lastStopAt = 1_000), null, null, null, nowSeconds = 8_200))
}
```
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement** — model as above (`ConvItemSerializer` gains the four cases); `statusStripText` builds the pieces with `relativeTime`; `SessionViewModel` calls `actions.usage(sessionId)` once on load and no more than every 60 s on refetch, storing `costMicros`/`model` in `Local`; `StatusStrip` composable colours amber at `pct >= 80` and adds a `/compact` `AssistChip` that sends `/compact` through `send()`; `Item` renders `Subagent` as a chevron row, `Compact` as a divider "Compacted", `Command`/`Interrupt` as one-line system rows.
- [ ] **Step 4: Run tests and build.**
- [ ] **Step 5: Commit** — `git commit -m "feat(session): a status strip with elapsed time, context and cost, and no more (unsupported item)"`

---

### Task 6: Quick replies, draft history, and where they live

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/store/Prefs.kt` (`expect class Prefs { fun getStringList(key): List<String>; fun putStringList(key, value) }`), `androidMain/.../store/Prefs.android.kt` (`SharedPreferences`, JSON-encoded), `iosMain/.../store/Prefs.ios.kt` (`NSUserDefaults`), `commonTest` fake, `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/QuickReplies.kt`
- Modify: `SessionViewModel.kt` (`quickReplies`, `history`, `sendQuick(text)`, `recordHistory`), `SessionScreen.kt` (chip row above the composer; history sheet on the field's leading icon), `App.kt`/`AppContainer` (construct `Prefs`)
- Test: `ui/QuickRepliesTest.kt`

**Interfaces:** `class QuickReplies(prefs: Prefs) { val chips: StateFlow<List<String>>; fun add(text); fun remove(text); fun history(): List<String>; fun remember(text) }` with defaults `go on · yes · run the tests · commit and push · /clear · /compact`, history capped at 20, most recent first, de-duplicated.

- [ ] **Step 1: Write the failing tests** (defaults present on first run; `add` persists across a new instance over the same fake `Prefs`; `remember` keeps 20 and moves a repeat to the front).
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement** as specified; the chip row is a `LazyRow` of `SuggestionChip`s hidden while `state.card != null`; long-press on a chip opens a small edit/remove dialog; `+` adds the current draft as a chip.
- [ ] **Step 4: Run tests and build.**
- [ ] **Step 5: Commit** — `git commit -m "feat(session): quick replies and a draft history, kept on the device"`

---

### Task 7: Transcript depth — markdown, tool rows, older turns, new-reply pill

**Files:**
- Modify: `gradle/libs.versions.toml` + `shared/build.gradle.kts` (markdown renderer, if it passes the size check), `SessionScreen.kt`, `SessionViewModel.kt` (`loadOlder()`, `newReply` flag), `model/Conversation.kt` (`ConvItem.Tool` gains `name`, `target`, `at`, `endedAt`, `done`)
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/components/ToolRows.kt`, `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/Grouping.kt`
- Test: `ui/GroupingTest.kt`, `ConvItemTest.kt` (extend), `SessionViewModelTest.kt` (extend)

**Interfaces:** `sealed interface Rendered { data class One(val item: ConvItem) : Rendered; data class ToolGroup(val tools: List<ConvItem.Tool>) : Rendered }`, `fun group(items: List<ConvItem>): List<Rendered>` (three or more consecutive tools → one group), `fun toolDuration(at: String?, endedAt: String?): String?` ("2.3 s", null when either is missing or unparsable — ISO-8601 with a hand-written parser: no datetime dependency), `SessionViewModel.loadOlder(): Job` (asks `conversation(sessionId, turns = shown + 20)` up to 100 and merges via `appending`), `SessionUiState.canLoadOlder`, `SessionUiState.newReply: Boolean` (set when the tail grew while the reader was not at the bottom — the screen reports `atBottom` through `vm.onAtBottom(Boolean)`).

- [ ] **Step 1: Write the failing tests** (`group([T,T,T,Text,T])` → `[ToolGroup(3), One(Text), One(T)]`; `toolDuration("…:00.000Z","…:02.300Z") == "2.3 s"`; `Tool` parses `name`/`target`/`done`; `loadOlder` asks for 30 after a 10-turn read and never more than 100; `newReply` is true after a refetch while not at bottom and false after `onAtBottom(true)`).
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement** — markdown: `implementation("com.mikepenz:multiplatform-markdown-renderer-m3:<latest 0.3x>")` in `commonMain` and `Markdown(item.text)` for `ConvItem.Text`; if the iOS size check fails, write `MiniMarkdown.kt`: split fenced code blocks (```), render them monospace in a horizontal scroll with a copy button, and apply `buildAnnotatedString` for `**bold**`, `*italic*`, `` `code` `` and `- ` lists; either way headings are demoted to bold. `ToolRows`: a collapsed row `▸ Bash  pnpm test   2.3 s ✓`, expanding to the list; a group header "6 tool calls (1 failed) ▸". `TruncationNote` becomes a `TextButton("Load 20 earlier turns")` shown when `canLoadOlder`. The pill: `AssistChip("↓ New reply")` floating above the composer when `newReply`, scrolling to `newest` on tap.
- [ ] **Step 4: Run tests, build, and read a long session on the phone.**
- [ ] **Step 5: Commit** — `git commit -m "feat(session): markdown turns, collapsible tool groups, load earlier turns, and a new-reply pill"`

---

### Task 8: The attention inbox

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/Attention.kt`, `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/components/AttentionInbox.kt`
- Modify: `SessionsViewModel.kt` (`attention: List<AttentionRow>`, `attentionCount` derived from it, `firstUnknownAt` bookkeeping for the five-minute rule, `answer(sessionId, Answer)` delegating to `SessionActions`), `SessionsScreen.kt` (inbox pinned as the first `LazyColumn` item; the `FilterChip` from Phase 0 goes), `App.kt` (`SessionsViewModel` gets `actions` and `hubContract`)
- Test: `ui/AttentionTest.kt`, `SessionsViewModelTest.kt` (extend)

**Interfaces:**
```kotlin
enum class Reason { BLOCKED, STUCK, FAILED, CI_FAILING, GHOST, HOST_UNREACHABLE, CONTEXT_HIGH, UNKNOWN_TOO_LONG }
data class AttentionRow(val sessionId: Long?, val hostAlias: String, val title: String, val reason: Reason, val detail: String?, val card: BlockedCard?, val since: Long?)
fun attentionRows(sessions: List<SessionRow>, hosts: List<HostRow>, unknownSince: Map<Long, Long>, nowSeconds: Long, hubContract: Int?): List<AttentionRow>
```
Ordering: STUCK, BLOCKED, FAILED, CI_FAILING, HOST_UNREACHABLE, GHOST, CONTEXT_HIGH, UNKNOWN_TOO_LONG; within a reason, oldest `since` first. A host row (`sessionId == null`) per unreachable host that has sessions.

- [ ] **Step 1: Write the failing tests** (each reason produces a row; a session `unknown` for 4 min is not listed and for 6 min is; ordering; the count equals the rows; `SessionRow.needsAttention` is no longer used by the view model — assert `attentionCount` for a `failed` row is 1).
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement** — `attentionRows` as a pure fold; the view model keeps `unknownSince: MutableMap<Long, Long>` updated on each rows emission (first time a row is `claude_status == null`, record `now`; remove when it gets a status); `attentionCount = attention.size`. `AttentionInbox` composable: a `Card` headed "NEEDS YOU (n)" with one compact row per `AttentionRow` — dot, title, host, age, and the `BlockedCard`'s first three chips inline when present (tap → `vm.answer(sessionId, answer)` → same flow as Task 3 but without the wait: the inbox just refetches on the next event), an `↗` to open. Collapses to a one-line "Nothing needs you" when empty. Delete the `needsAttentionOnly` filter, `toggleNeedsAttentionOnly` and the `FilterChip`; `SessionsScreen`'s empty state loses its filter branch.
- [ ] **Step 4: Run tests and build; on the phone the inbox lists the blocked session first with its chips.**
- [ ] **Step 5: Commit** — `git commit -m "feat(sessions): an attention inbox pinned on top, with the same one-tap answers as the session card"`

---

### Task 9: Voice into the draft

**Files:**
- Create: `shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/Speech.kt` (`expect class SpeechInput { fun available(): Boolean; suspend fun listen(): String? }`), `androidMain/.../ui/Speech.android.kt` (`SpeechRecognizer` + `RECORD_AUDIO` runtime permission via `ActivityResultContracts.RequestPermission`; manifest permission in `androidApp`), `iosMain/.../ui/Speech.ios.kt` (`SFSpeechRecognizer` + `AVAudioEngine`; `NSSpeechRecognitionUsageDescription` and `NSMicrophoneUsageDescription` in `iosApp/Info.plist`)
- Modify: `SessionViewModel.kt` (`dictate(): Job` → draft; `stage(): Job` → `send_prompt {submit:false}` then a second tap submits with `sendKeys("Enter")` or an empty `sendPrompt`), `SessionScreen.kt` (mic `IconButton` in the composer; "Stage" vs "Send" when the draft came from voice), `SessionActions`/`HubClient` (`sendPrompt(sessionId, text, submit: Boolean = true)`)
- Test: `SessionViewModelTest.kt` (a fake `SpeechInput` returning "run the tests" fills the draft and marks it `fromVoice`; `stage()` sends with `submit=false`; `send()` after a staged draft sends Enter and clears)

- [ ] **Step 1: Write the failing tests.**
- [ ] **Step 2: Run to verify they fail.**
- [ ] **Step 3: Implement**, with the `actual`s marked in the repo skill's "runs only on a device" list and an `Info.plist`/manifest scan test in `jvmTest` (the repo's `IosHostTest`/`AndroidHostTest` pattern) asserting the two usage descriptions and the `RECORD_AUDIO` permission are declared.
- [ ] **Step 4: Run tests and build; dictate a prompt on the phone and watch it land in the REPL line before submitting.**
- [ ] **Step 5: Commit** — `git commit -m "feat(session): dictate a prompt, stage it in the REPL, submit with a second tap"`

---

## Self-review

- Spec coverage: 1.1 → T2, T3; 1.2 → T4; 1.3 → T5; 1.4 → T6, T9; 1.5 → T7; 1.6 is the hub plan (T2 gates on its contract revision); 1.7 → T8.
- Types: `Answer`/`BlockedCard` (T2) are consumed by T3 and T8; `WaitResult`/`SendPromptResult.turnSeqBefore` (T1) by T3; `friendly` (Phase 0) by every catch; `relativeTime` (Phase 0) by T5 and T8; `hubContract` on `FleetState` (T3) by T2's callers and T8.
- Read-only devices: `canManage`, the card, the inbox chips and the quick replies all gate on `readOnly` — tested in T3 and T4.
- Order: T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9; T5 and T6 could run in parallel on different files, but T7 and T3/T4 all edit `SessionScreen.kt`, so keep them sequential.
