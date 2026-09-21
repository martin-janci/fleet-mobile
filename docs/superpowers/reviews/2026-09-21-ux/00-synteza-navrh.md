# fleet-mobile: návrh UX prerábky (syntéza piatich UX reviews)

Dátum: 2026-09-21. Vstup: 10 screenshotov z Galaxy S25 Ultra proti `fleet.rlt.sk` (44 sessions, 5 hostov, 1 blokovaná) a päť nezávislých reviews (zoznam a triáž, obrazovka session, vizuálny dizajn, platforma, operátorský workflow). Reporty sú priložené v angličtine.

## Diagnóza v troch vetách

1. **Appka je okno, nie ovládač.** Volá 6 z ~75 nástrojov, ktoré hub klientovi povoľuje (`list_sessions`, `list_hosts`, `list_projects`, `session_conversation`, `send_prompt`, `/pair`). Kill, restart, tagy, diff, spawn, tasky, inbox, cena – všetko je na hube dostupné dnes, bez zmeny hubu, a telefón to nevie.
2. **Jediný dôvod otvoriť telefón nefunguje.** Blokovaná session je 7. riadok s rovnakou váhou ako `idle`; po otvorení sa otázka agenta („Recreate turanga?“) nikde nezobrazí, Enter sa poslať nedá (prázdny prompt je odmietnutý), voľby nie sú tlačidlá.
3. **Dáta, ktoré už prichádzajú, sa nekreslia.** `context_pct`, `ci_status`, `pr_url`, `tags`, `last_activity_at`, `kind` sú v modeli a nikde na obrazovke; namiesto nich riadok vypĺňa surový text z terminálu (`bypass permissions on (shift+tab to cycle)`, ANSI smeti `0;16;27M`). Tab bar má písmená S/H/S, chyby sú `E_*` kódy s UUID, tmavý režim neexistuje, appka žije len v popredí.

## Cieľ: z okna „pager“

Tri princípy, ktoré sa zhodli vo všetkých piatich reviews:

- **Pozornosť najprv.** Čo potrebuje človeka, je hore, farebne odlíšené (jantárová = odpovedz na otázku, červená = choď opraviť terminál) a spočítané v badge.
- **Odpoveď na jeden ťuk.** Otázka agenta s jeho voľbami ako tlačidlá, Enter/Esc, rýchle odpovede, kill/restart v menu.
- **Telefón sa ozve sám.** Notifikácia pri prechode do `blocked`/`failed`/CI red, s akciami Odpovedať/Schváliť, bez otvorenia appky.

## Fázy

### Fáza 0 – rýchle výhry (S, bez zmeny hubu, ~1–2 dni)
- Sanitizovať `current_activity` (ANSI, PUA znaky, známy boilerplate); keď nič neostane, ukázať „pred 3 min · shell“.
- `bg:<uuid>` riadky pomenovať z `friendly_name` / `last_prompt`.
- Skutočné ikony v tab bare + badge s počtom „needs you“; pull-to-refresh; „Refresh“ a „…“ preč.
- Farebné tokeny stavov (working/idle/blocked/stuck/failed/completed/stopped/unknown) so svetlou aj tmavou sadou; `unknown` ako tichá pomlčka, nie slovo; tmavý režim (`isSystemInDarkTheme`).
- Chyby bez `E_*`: `E_NO_TRANSCRIPT` = prázdny stav „Zatiaľ nič, pošli prvý prompt“; ostatné s „Podrobnosti“.
- Relatívny čas na riadku, `ci_status` bodka, kontextový prúžok.
- Parsovať `contract` z `ready` rámca (version-skew ochrana, ktorú desktop má a telefón nie); banner rozlíšiť „bez siete / hub nedostupný / stream spadol“, Send viazať na `/mcp`, nie na SSE.

### Fáza 1 – ovládač (M, ~1 týždeň; dve malé zmeny hubu)
- **Karta „Čaká na teba“** nad composerom pri `blocked`/`stuck_kind`: otázka + voľby ako chipy (1 · Yes / 2 / 3 · No / Enter / Esc), „zobraziť terminál“ cez `capture_session`; `press_enter` = jedno veľké Enter; `auth_menu`/`oom` = vysvetlenie + Restart. Po odpovedi `wait_for_session {turn_gt}` a karta zmizne.
- **Overflow menu session:** premenovať, tagy, Restart, Safe kill (s progresom `safe_kill_state`), Kill now (hold to confirm), `E_CONFIRM_REQUIRED` vysvetlené.
- **Stavový prúžok:** `● working 2m14s · ctx 62 % · $1.84 · model`; pri ≥ 80 % kontextu oranžový s chipom `/compact`.
- Markdown v odpovediach, kódové bloky s copy; `subagent`/`compact` položky ako jednoriadkové záznamy namiesto „(unsupported item)“.
- Rýchle odpovede (go on · yes · run the tests · /clear · vlastné), história promptov, mikrofón (STT do draftu, `submit:false`, odoslať druhým ťukom).
- „Načítať starších 20 ťahov“ + pilulka „↓ Nová odpoveď“ pri scrollovaní hore.
- **Inbox pozornosti** pripnutý nad zoznamom (nahradí prepínač), s rozšíreným členstvom: blocked, stuck, failed, CI failing, ghost, nedostupný host, kontext > 85 %, `unknown` dlhšie než 5 min.

Zmeny hubu: (a) `send_prompt` prijme prázdny text / špeciálne klávesy (`keys: Enter|Escape|C-c`); (b) na riadku session `pending_input {kind, options[]}` z pane-intel, ktoré dialóg už parsuje a voľby zahadzuje.

### Fáza 2 – flotila na jeden pohľad (M, ~1–2 týždne)
- Trojriadková anatómia riadku (bodka + meno + stav · vek / projekt · host · ctx · $ / PR · CI · tagy), sticky zbaliteľné hlavičky hostov, prepínač triedenia (recency vs host), vyhľadávanie, filter chipy, hlavička so súčtami a „$ dnes“ (`fleet_health`).
- Swipe akcie (Nudge / Kill, Tag / Open) a long-press sheet.
- **Sheet „Čo zmenil“:** `repo_changes` → `repo_diff` (unified, +/−), commity, PR + CI.
- **Nová session z telefónu:** host, projekt, worktree/base branch alebo background, prompt s mikrofónom (`new_session` + `send_prompt`, alebo `new_bg_session`).
- **Tab Tasks:** `list_tasks` s výsledkami, `dispatch_task`, `cancel_task`, stream `?kinds=session,host,task`.
- Zbaliteľné tool volania s výsledkom – potrebuje hub: `session_tool_detail` ako MCP nástroj (dnes len Tauri IPC).

### Fáza 3 – telefón sa ozve (M–L)
- Android: foreground service nad existujúcim SSE čítačom, notifikácie na *prechody* stavov (→blocked, stuck, →failed, CI→failing, →completed s PR, host→unreachable), kanály `attention/failed/finished/ci/cost/service`, akcie Open / Reply (`RemoteInput` → `send_prompt`) / Approve (len keď hub povie druh promptu). Záložne WorkManager diff každých 15 min. Prepínač „Sledovať na pozadí“ v Settings.
- Deep linky `claudefleet://session/<id>`, `claudefleet://attention`, App Links `https://fleet.rlt.sk/s/<id>`; perzistovaný snapshot pre okamžitý studený štart; Glance widget (počet + top 3), Quick Settings dlaždica „What needs me?“.
- iOS úprimne: bez APNs to zostane oknom; APNs relay v hube (`register_device` + odosielač, ~200 riadkov Rustu) je samostatná, neskoršia položka.

Zmeny hubu: `needs_attention` dôvod na riadku (jedno miesto pravdy pre desktop, telefón aj push), `attention_digest` / `wait_for_attention` long-poll (per-session `wait_for_session` je pre telefón zlý primitív), `message:created` event, `list_sessions {needs_attention}` filter.

### Fáza 4 – 10× nápady (po fázach 0–3)
- **„Spýtaj sa flotily“** – jedno textové pole hore: `ensure_operator` + `run_prompt` na operátorskej session („čo je blokované a prečo“, „zabi všetko idle na mefistose nad 2 h“). Bez nového API; dôveryhodný klient už doručuje bez markera.
- **Ranný digest** – „odkedy si sa pozeral naposledy“: dokončené, PR zelené/červené, minuté $, kto sa pýta; každá karta s jednou naviazanou akciou.
- **Schvaľovanie z notifikácie / lock screenu** (Android Live Updates, neskôr iOS Live Activity).
- **Broadcast s fan-in** – long-press na projekt → `broadcast_prompt`, karta zbiera odpovede cez `session_transcript {since_turn}`.
- **Rozpočet ako alarm** – strop na session/deň, `session:budget_exceeded`, akcia Pause.
- Vizuálne: heat-mapa flotily v hlavičke, deterministické avatary agentov, ľavý farebný rail pri riadkoch, ktoré ťa potrebujú.

## Súhrn zmien na strane hubu (repo claude-fleet)

| Zmena | Odomkne | Fáza |
|---|---|---|
| `send_prompt` prázdny text / `keys` | Enter, Esc, C-c z telefónu | 1 |
| `pending_input {kind, options}` na riadku | tlačidlá odpovedí, Approve v notifikácii | 1 |
| `session_tool_detail` ako MCP nástroj | rozbaliteľné tool volania | 2 |
| `needs_attention` dôvod na riadku + `list_sessions {needs_attention}` | jednotná triáž, digest | 3 |
| `attention_digest` / `wait_for_attention` | lacný poll pre widget a notifikácie | 3 |
| `message:created` event, inbox v `fleet_health` | inbox bez pollingu | 3 |
| `session_conversation` fallback na pane pri `E_NO_TRANSCRIPT` | shell/tiché sessions bez chyby | 0–1 |
| push relay (APNs/FCM) | iOS notifikácie | neskôr |

## Ako by som to robil

Vývoj v repe `fleet-mobile` po fázach ako samostatné PR (subagent-driven, TDD podľa repo skillu: `jvmTest` ako brána, mutačný sweep na nové gate-y), hub zmeny ako malé PR v `claude-fleet` s regeneráciou referencie a kontraktu. Android najprv, iOS má rovnaký shared UI, takže fázy 0–2 idú na obe platformy zadarmo; fáza 3 je Android-first.
