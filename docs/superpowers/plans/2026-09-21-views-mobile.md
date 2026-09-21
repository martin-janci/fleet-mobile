# Views, filters, scrolling, markdown — mobile (fleet-mobile) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tapping a host shows its sessions; the session screen opens on the latest turn or where you left it, offers a jump-to-bottom pill and turn-by-turn stepping, and renders assistant text as Markdown.

**Architecture:** Filter state lives in `SessionsViewModel.Local` and is applied in the pure `groupSessions`; navigation carries an optional host alias on `Screen.Sessions`; per-session scroll memory is an in-memory map in `SessionViewModel`'s companion (app lifetime); Markdown via `multiplatform-markdown-renderer-m3` behind a size check, else the inline `MiniMarkdown`.

**Tech Stack:** as Phase 0. Branch `feat/views-ux` stacked on `feat/ux-pager`.

**Spec:** `docs/superpowers/reviews/2026-09-21-views/ux-views-analysis.md` §B (mobile) and Phase 1 plan Task 7 (markdown + new-reply pill slice, pulled forward).

## Global Constraints
Phase 0's (token rule, one rule one place, `update {}`, `ToolsTheAppMayCallTest`, `jvmTest` + `assembleDebug` green before each commit, Conventional Commits, no trailers, no raw bytes < 0x20). The only new dependency allowed is the markdown renderer, and only if `:shared:linkDebugFrameworkIosSimulatorArm64` (or the closest iOS framework task) grows by ≤ 2 MB; otherwise `MiniMarkdown.kt`.

---

### Task 1: Host tap → filtered Sessions with a clear chip

**Files:** `ui/HostsScreen.kt` (`HostLineItem` gets `onClick`), `ui/Navigator.kt` (`Screen.Sessions` carries `hostAlias: String? = null`; `tabOf` unchanged; `open`/`select` keep the "leave state behind" rule), `ui/SessionsViewModel.kt` (`Local.hostFilter: String?`, `setHostFilter(alias: String?)`, applied in `groupSessions` before grouping), `ui/SessionsScreen.kt` (an `InputChip` "host: mefistos ✕" beside the Needs-attention chip; the empty state says "No sessions on mefistos"), `App.kt` (wire `onOpenHost = { nav.showSessionsFor(it) }`); tests `SessionsViewModelTest.kt`, `NavigatorTest.kt`.

- [ ] Step 1: failing tests — `groupSessions(..., hostFilter = "mefistos")` keeps only that host's groups and `attentionCount` still counts the whole fleet; `nav.showSessionsFor("mefistos")` yields `Screen.Sessions(hostAlias = "mefistos")` on the Sessions tab; `setHostFilter(null)` clears.
- [ ] Step 2: RED. Step 3: implement. Step 4: GREEN + `assembleDebug`. Step 5: commit `feat(sessions): tap a host to see its sessions, with a chip to clear the filter`.

### Task 2: Markdown for assistant text

**Files:** `gradle/libs.versions.toml` + `shared/build.gradle.kts` (renderer, gated by the size check — record both framework sizes in the report), or `ui/components/MiniMarkdown.kt` (fenced code blocks split first; `**bold**`, `*italic*`, `` `code` ``, `- ` lists via `buildAnnotatedString`; headings demoted to bold), `ui/SessionScreen.kt` (`ConvItem.Text` branch), test `ui/components/MiniMarkdownTest.kt` (if the inline parser is used: block splitting and inline spans are pure and testable) or a jvmTest source scan that `ConvItem.Text` no longer renders through a bare `Text(item.text)`.

- [ ] Step 1: failing test. Step 2: RED. Step 3: implement. Step 4: GREEN + `assembleDebug`; screenshot a turn with bold, a list and a code fence. Step 5: commit `feat(session): assistant text renders as Markdown`.

### Task 3: Open on the latest turn or where you left it; jump-to-bottom; turn stepping

**Files:** `ui/SessionViewModel.kt` (`companion object { internal val scrollMemory = mutableMapOf<Long, ScrollAnchor>() }`, `data class ScrollAnchor(val itemKey: String, val atBottom: Boolean)`, `rememberScroll(anchor)` called by the screen on dispose, `recallScroll(): ScrollAnchor?`; `SessionUiState.newReply: Boolean` set when the tail grows while the reader is not at the bottom; `onAtBottom(Boolean)`), `ui/SessionScreen.kt` (on first `loaded`, if a recalled anchor exists and `!atBottom`, `scrollToItem` its index; else scroll to newest — the existing effect; an `AssistChip("↓ Latest")` / `"↓ New reply"` floating above the composer when `!atBottom`; two small `IconButton`s "prev turn" / "next turn" in the bar's overflow area that scroll to the adjacent `turn-N` key relative to the first visible item), pure helpers in `ui/TurnNav.kt` (`adjacentTurn(firstVisibleIndex, turnCount, truncated, delta): Int`), tests `SessionViewModelTest.kt`, `ui/TurnNavTest.kt`.

- [ ] Step 1: failing tests — scroll memory round-trip; `newReply` true after a refetch while `onAtBottom(false)`, false after `onAtBottom(true)`; `adjacentTurn` clamps at both ends and accounts for the truncation-note item offset (reuse `newestItemIndex`'s reasoning).
- [ ] Step 2: RED. Step 3: implement. Step 4: GREEN + `assembleDebug`; screenshot with the pill visible. Step 5: commit `feat(session): open where you left off, jump to the latest, step turn by turn`.

## Self-review
- Analysis coverage: M1→T1, M2→T2, M3→T3 (plus prev/next turn from the operator's ask).
- Names used once: `Screen.Sessions.hostAlias`, `setHostFilter`, `ScrollAnchor`, `scrollMemory`, `adjacentTurn`, `newReply`, `onAtBottom`.
