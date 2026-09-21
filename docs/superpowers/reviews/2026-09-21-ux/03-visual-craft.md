# fleet-mobile — visual craft review

Reviewed: 10 screenshots (S25 Ultra, light) + the listed screens and components. Compose Multiplatform 1.12 / material3 1.9. Paths are relative to `shared/src/commonMain/kotlin/dev/claudefleet/mobile/`.

Verdict: the information architecture is right and the code is well-reasoned, but the surface is a wireframe — no icons, no theme, one grey for four states, raw terminal text where a summary should be. All fixable with M3 parts that already ship.

## 1. Audit

| Area | Finding | Evidence | Severity |
|---|---|---|---|
| Typography | Screen titles are `titleMedium` (16sp); M3 small top bars want `titleLarge` (22sp). Row title `bodyLarge` Regular has no weight over the 12sp grey activity line, so the eye lands on the chip, not the name. Project header `labelMedium` with 2dp bottom padding hugs the row below and floats off the divider above — hierarchy reads inverted. | `SessionsScreen.kt:95,155,145`; shot 01 | Medium |
| Colour / status semantics | `idle`, `stopped`, `unknown`, `completed` are all grey-ish; `blocked` and `failed` share `errorContainer`; a stuck session (needs a human *now*) looks like `failed`. `unknown` is the most frequent chip in shot 01 and reads as a real state. No named status tokens. | `StatusChip.kt:33-41`; shots 01, 03 | High |
| Spacing / density | Full-bleed `HorizontalDivider` after every row *and* every turn; host header band is 6dp tall; rows are ~60dp without an activity line and ~96dp with, so the rhythm jitters. Session body is empty white down to the composer. | `SessionsScreen.kt:116,176`; `SessionScreen.kt:173`; shots 01, 05 | Medium |
| Touch targets | Mostly fine (rows, `TextButton`s, `Switch` at 48dp+). But the busy Refresh collapses to `"…"`, which reads as an overflow menu (shot 07). Tool-call lines and prompt bubbles are inert. | `SessionScreen.kt:144,186` | Medium |
| Iconography | Zero icons. Tabs are `Text(entry.name.take(1))`, hence "S H S". Glyphs are `"·"`, `"✗"` and a leaked prompt chevron. Splash is `Text("claude-fleet")`; no brand mark. | `App.kt:297,200`; shot 01 | High |
| Raw pane text | `current_activity` is drawn verbatim: `0;16;27M0;16;27m` (an SGR mouse report), tofu boxes (Nerd-font private-use glyphs), `bypass permissions on (shift+tab to cycle) · <- 3 agents` on 9 of 10 rows. Background sessions show as `bg:11d6176e-5594-…`. Yet `SessionRow` already carries `contextPct`, `lastActivityAt`, `ciStatus`, `prUrl`, `kind`, `tags` — none drawn. | `SessionsScreen.kt:165-173`; `model/SessionRow.kt:38-49`; shots 01–03 | High |
| Empty / error / loading | Session screen has *no* empty state: no transcript means a blank body under a red `E_NO_TRANSCRIPT: no transcript for claude session 0b63c561-…` banner — an `E_*` code for a non-error. Loading is `"Refreshing…"` text; no skeleton, no indicator. | `Banners.kt:55-83`; `SessionScreen.kt:92-97`; shots 05, 06 | High |
| Motion | None: no `animateItem`, no `AnimatedContent` on status, hard-cut screen push, no pull-to-refresh. | `App.kt:305`; `SessionScreen.kt:92` | Medium |
| Dark theme | `MaterialTheme {}` with no `colorScheme`; `isSystemInDarkTheme` appears nowhere. On an OLED phone checked at night this is the first thing noticed. `onColorFor` matches containers by `==`, which breaks once custom tokens exist. | `App.kt:149`; `StatusChip.kt:63-71` | High |
| Accessibility | No `contentDescription` in the codebase. "Needs attention" `Text` and `Switch` are separate nodes; wrap in `Row.toggleable(role = Role.Switch)`. Busy `"…"` has no spoken meaning. Contrast passes (about 8:1 body, 4.5:1+ chips). | `SessionsScreen.kt:99-103` | Medium |
| Filter capture | Shot 04 is identical to 03 with the switch off — the filtered view is unverified. | shots 03/04 | Info |

## 2. Proposed visual system

### Status tokens
An `@Immutable data class FleetStatusColors(dot, container, onContainer)` per state, provided via `staticCompositionLocalOf` from a `FleetTheme { }` wrapper around `MaterialTheme(colorScheme = if (dark) darkColorScheme(...) else lightColorScheme(...))`. Stuck outranks status; `unknown` is deliberately quiet.

| State | Light dot / container / on | Dark dot / container / on | Meaning |
|---|---|---|---|
| working | `#2F6BFF` / `#E3ECFF` / `#0B3D91` | `#7FA3FF` / `#1B2D55` / `#B7CBFF` | pulses |
| idle | `#8A8891` / `#EEEDF2` / `#4B4A52` | `#8A8891` / `#2C2B33` / `#C6C4CE` | quiet |
| blocked | `#E58A00` / `#FFE8C2` / `#6B3D00` | `#FFB74D` / `#4A3000` / `#FFD08A` | needs a person |
| stuck (any `stuck_kind`) | `#D32F2F` / `#FFDAD6` / `#93000A` | `#FF8A80` / `#5C1A17` / `#FFB4AB` | needs a person now; leading `Warning` icon |
| failed | `#D32F2F` / outlined `#FFDAD6` 1dp / `#93000A` | `#FF8A80` / outlined / `#FFB4AB` | red but outlined, with `Close` icon |
| completed | `#1E8E3E` / `#D6F0DD` / `#0F5A2A` | `#7CD292` / `#143D25` / `#9FE0B4` | `Check` icon |
| stopped | none / transparent, 1dp `outlineVariant` / `onSurfaceVariant` | same | |
| unknown | none / transparent, 1dp *dotted* / `onSurfaceVariant` at 70% | same | text "—" not "unknown" |

Amber vs red is the operator's whole triage: amber = answer a question, red = go fix the terminal.

### Row anatomy (`SessionRowItem`)
Use `ListItem(leadingContent, headlineContent, supportingContent, trailingContent)`; min height 72dp; 16dp horizontal padding; `HorizontalDivider(Modifier.padding(start = 56.dp))`, or drop dividers and use `surfaceContainerLow` cards per project.
- Leading (40dp box): 10dp status dot, or the agent avatar (see section 4). Working dot pulses.
- Headline: `titleMedium` (16sp, Medium 500), `maxLines = 1`. Background sessions: `friendlyName ?: "Background · ${tmuxName.removePrefix("bg:").take(4)}"`.
- Supporting (`bodyMedium` 14sp, `onSurfaceVariant`, 1 line): sanitized `current_activity` — strip `ESC[...]` sequences, `[0-9;]+[Mm]` runs, private-use glyphs `U+E000–U+F8FF`, and the known boilerplate strings; if nothing survives, show `"$relativeTime · $kind"` from `lastActivityAt` / `kind`.
- Trailing column, end-aligned: `StatusChip` (24dp tall, `labelMedium`, 8/4dp padding, `shapes.small`) over a `labelSmall` relative time ("4m"). When `contextPct != null`, a 2dp `LinearProgressIndicator` 60dp wide under the headline, `tertiary` past 80%. `ciStatus` is a 6dp dot after the time.
- Host header: `surfaceContainer` band, 40dp, `titleSmall` SemiBold + trailing count, as `stickyHeader`.
- Project header: `labelLarge`, 12dp top / 4dp bottom, `secondary`.

### App bar and navigation
- `TopAppBar(title = { Text("Sessions", style = titleLarge) }, scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior())` with `Modifier.nestedScroll(...)` on the column.
- Filter: `FilterChip(selected = needsAttentionOnly, label = "Needs attention", leadingIcon = Warning, trailingIcon = Badge("$n"))` replaces the labelled Switch.
- Refresh: `IconButton(Icons.Outlined.Refresh)` rotating while busy, plus `PullToRefreshBox` (material3 1.9) on every list.
- Tabs: `NavigationBarItem(icon = { BadgedBox(badge = { if (n > 0) Badge { Text("$n") } }) { Icon(painterResource(Res.drawable.ic_sessions), null) } })`. Ship the three tab icons as Compose Resources XML vectors (zero dependency, matching the ZXing-over-MLKit stance in `libs.versions.toml`); take Send/Refresh/ArrowBack/MoreVert/Warning/Check/Close from `material-icons-core`. Sessions = chat bubbles, hosts = server rack, settings = gear.
- Session bar: `TopAppBar(navigationIcon = IconButton(ArrowBack), title = name + host subtitle, actions = chip + overflow)`; the transcript scrolls under it.

### Chip and banner
`StatusChip` becomes `Surface(shape = shapes.small, color = token.container, border = if (outlined) BorderStroke(1.dp, token.onContainer.copy(alpha = 0.4f)) else null)` with an optional 14dp leading icon, `labelMedium`, and `Modifier.semantics { contentDescription = "status: $text" }`. Status changes animate via `AnimatedContent { (fadeIn(tween(200)) + scaleIn(initialScale = 0.92f)) togetherWith fadeOut(tween(150)) }`.

`ConnectionBanner` keeps `surfaceVariant` and gains a 16dp `CircularProgressIndicator(strokeWidth = 2.dp)` while reconnecting. `ErrorBanner` uses `errorContainer`, `Icons.Outlined.Warning` leading, `bodyMedium` text, actions on their own row ("Retry" / "Details").

### Error pattern (hide `E_*`)
Map before drawing: `fun HubError.friendly(): Friendly(title, body, isError)`. `E_NO_TRANSCRIPT` is not an error: render the body empty state "Nothing has been said yet — send a prompt to start" and suppress the banner. Unknown codes: title "The hub refused that", body = the hub's sentence. Every banner gets a "Details" `TextButton` toggling `AnimatedVisibility` over the raw code + message in `FontFamily.Monospace bodySmall` on `surfaceContainerHighest`, with a copy `IconButton`. Never ellipsize the host name.

### Skeleton / loading
When `loading && list.isEmpty()`: 6 placeholder `ListItem`s whose text is `Box(Modifier.height(14.dp).fillMaxWidth(0.6f).clip(RoundedCornerShape(4.dp)).background(shimmerBrush))`, `shimmerBrush` a `Brush.linearGradient` offset driven by `rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(1100)))`. When a snapshot exists: keep it and show an indeterminate 2dp `LinearProgressIndicator` under the app bar. Splash: brand mark + `CircularProgressIndicator`.

### Motion vocabulary
- **Live pulse** (working dot): scale 1 to 1.3, alpha 1 to 0.35, `infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse)`; gated by an `expect fun reduceMotion(): Boolean`.
- **New turn arrival**: `Modifier.animateItem()` on transcript items; newest turn `fadeIn() + slideInVertically { it / 4 }` over 250ms. If the reader is scrolled up, an `AssistChip("New reply")` floats above the composer and scrolls to it.
- **Status flip**: chip `AnimatedContent` as above; leading dot `animateColorAsState(tween(300))`.
- **Badge bump**: count change scales the `Badge` 1 to 1.15 to 1 in 150ms.
- **Screen push**: `AnimatedContent(screen)` with `slideInHorizontally { it } + fadeIn()` over 300ms; back reverses.
- **Send**: label crossfades to a 16dp spinner; the sent prompt appears at once as a 60%-alpha bubble until the hub echoes it.

### Composer
`TextField(shape = CircleShape)` with transparent indicators on `surfaceContainerHigh`, placeholder "Message ano…", `imeAction = ImeAction.Send`; Send is a 48dp `FilledIconButton(Icons.AutoMirrored.Outlined.Send)` — `primary` when enabled, `surfaceVariant` when not.

## 3. Top 10 by impact-to-effort

1. **Real tab icons + attention badge** — kills "S H S", gives the operator the count without opening the tab. **S**
2. **Dark theme**: `FleetTheme` with `lightColorScheme`/`darkColorScheme` + `isSystemInDarkTheme()`, dynamic colour on Android via expect/actual. **S**
3. **Sanitize `current_activity`** and fall back to relative time + kind; humanize `bg:` names. Nine rows of tofu become nine readable rows. **S**
4. **Status token set** + leading dot + amber/red split + quiet `unknown`. **M**
5. **Session empty state + plain-language errors with Details expander**; `E_NO_TRANSCRIPT` stops being red. **M**
6. **M3 `TopAppBar` + `FilterChip` + `PullToRefreshBox`**; delete the "Refresh" and "…" text buttons. **M**
7. **Row anatomy via `ListItem`**: inset dividers, sticky host headers, context bar, CI dot, time. **M**
8. **Skeleton + `LinearProgressIndicator`** loading states. **S**
9. **Composer restyle** (pill field, filled Send icon, IME send). **S**
10. **Motion vocabulary** (pulse, chip crossfade, `animateItem`, screen slide, New-reply pill). **M**

Fold into 1 and 6: `contentDescription`s and the `toggleable` merge for TalkBack.

## 4. Three 10x ideas

**Fleet heat-map header.** A collapsible `LargeTopAppBar` region: one card per host, each a wrap-grid of 12dp squares, one per session, coloured by status token, working squares pulsing, stuck ones ringed red. Tap a square to open the session. On scroll it collapses to a 4dp colour strip under the bar — the fleet's health at a glance from any scroll position. The same strip is the Wear tile and home widget.

**Agent identity system.** Deterministic avatar from `session.id`: hash to a hue pair from the palette plus one of 8 geometric marks, friendly-name initials on top. Used as row leading content, in the session bar, in notifications and the desktop sidebar, so "ano" looks the same everywhere. A `blocked` or stuck row grows a 4dp tinted left rail, so the list reads like a kanban of who needs you.

**Live Updates + lock-screen widget.** Android 16 Live Update (ongoing `ProgressStyle` notification): "3 working · 1 needs you", with the blocked question ("Recreate turanga?") carrying inline **Yes / No / Reply** actions via `send_prompt`. A Glance widget shows the heat-map strip; on iOS an ActivityKit Live Activity puts the attention count in the Dynamic Island. The operator answers an agent without unlocking the phone — the product's actual promise.
