# Attaching a file from the phone

*2026-09-26*

Today the phone can type a prompt and send it. It cannot attach anything.
This is the design for a **+** beside the composer that puts a file from the
phone into the session's worktree and names it in the prompt — the same thing
the desktop composer's `⌾` already does, reaching the same place by a
different road.

## Why this is not a small change

The desktop's road does not exist on a phone. Its pipeline is:

1. `pick_attachments` — a Tauri command, so a **native OS dialog on the
   machine running the app**. It returns local filesystem paths and mints a
   one-shot entry per path in `UploadAllowList`.
2. The composer draws a chip per file; `attachment_preview` makes thumbnails.
3. On send, `upload_attachments` reads those local files and **streams them
   over the session host's ControlMaster** (`SshClient::upload_file`) into
   `<worktree>/.claude-fleet-attachments/`.
4. `withAttachments` (`src/lib/attach_prompt.ts`) appends the resulting
   absolute remote paths to the prompt text.
5. `send_prompt`.

Steps 1 and 3 assume the bytes and the SSH client are on the same machine. On
a phone the bytes are on the phone and `fleet-core` is on the hub. Everything
between — the budget, the staging directory, the git exclude, the filename
rules — is host-side and reusable as it stands.

## Decision: a hub HTTP route, not an MCP tool

My first read of this was that it needed a new MCP tool. It does not, and a
tool would be the worse of the two.

The hub's MCP surface is under budget pressure — a single added tool
*parameter* has overflowed it before. Beyond that, `tools/call` on `/mcp` is
JSON-RPC over SSE framing: a file would have to travel base64 (+33 %), so a
10 MB attachment becomes a ~13.3 MB JSON string threaded through a transport
built for control messages.

The hub already serves four routes that are not MCP tools, and one of them is
the exact shape needed. `POST /report` is how a phone posts its own errors: it
sits behind the `authorize` layer, carries its own state, and declares its own
`DefaultBodyLimit` from a constant in `fleet-proto`
(`report::BODY_MAX = 64 KB`). An attachment route is that, with a bigger limit
and a different destination.

**`POST /attachment`** — raw bytes, no base64, no MCP surface cost at all.

### The route

```
POST /attachment?session_id=<id>&name=<filename>
Authorization: Bearer <client token>
Content-Type: application/octet-stream
Content-Length: <n>

<raw bytes>
```

Answering `{ "path": "/abs/path/on/the/host/<name>" }`.

- Registered in `crates/fleet-core/src/mcp/mod.rs` on the merged router
  **before** the `authorize` layer is applied, exactly as `/report` is — that
  is what puts it behind the bearer token.
- `DefaultBodyLimit::max(attachments::MAX_BYTES)`. The existing per-file
  ceiling (10 MB) becomes the route's body cap, so a file too big is refused
  by the transport rather than after it has been read.
- The handler reuses, unchanged:
  `service::attachments::check_budget`, `ATTACH_DIR`, `root_script`,
  `stage_script`, and `transfer_all`'s remote branch.
- One file per request. Several attachments are several requests, and the
  25 MB batch ceiling is the phone's to enforce across them (see *Budgets*).

### Who may call it

**`full` only.** `/report` deliberately accepts a `readonly` client, because
posting your own crash is not a privilege. This is not that: it writes bytes
onto the filesystem of a machine in the fleet. A `readonly` credential gets
`403`, and the composer hides the **+** the same way it already hides Send.

## The phone side

### Picking a file

A fourth `expect`/`actual` family, alongside `Platform`, `Secrets` and
`QrScanner`:

```kotlin
// ui/pick/FilePicker.kt
expect fun filePickerSupported(): Boolean

data class PickedFile(val name: String, val size: Long, val bytes: ByteArray)

@Composable
expect fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit
```

- **Android** — `ActivityResultContracts.OpenMultipleDocuments`, read through
  the returned `content://` URI. No storage permission: the picker runs
  outside the app and hands back a grant per URI.
- **iOS** — `UIDocumentPickerViewController` in `.open` mode, wrapped in
  `startAccessingSecurityScopedResource`.

> **A gap worth stating.** `UIDocumentPicker` sees **Files**, and an iOS
> screenshot goes to **Photos**, which is not Files. So on iPhone the single
> most likely attachment — a screenshot — cannot be reached through this
> picker unless the user first saves it to Files. Android does not have the
> problem: its system picker lists Photos as a provider. Adding
> `PHPickerViewController` later is purely additive — another `actual`
> producing the same `PickedFile`, and nothing else in this design moves.
> It is left out here because the brief asked for one "any file" flow.

### Composer

- A **+** left of the prompt field, hidden when the credential is `readonly`.
- A chip strip above the field, one per pending file: name, size, a `×`. The
  desktop draws thumbnails via `attachment_preview`; the phone already holds
  the bytes, so an image chip can render its own preview with no round trip.
- Files upload **on send**, not on pick — matching the desktop, and meaning a
  file queued and then removed never leaves the phone.
- A failed upload leaves the draft and the chips alone and says which file
  failed. The desktop's rule is the precedent: "a prompt without its
  attachment is a worse outcome than a prompt not sent".

### Composing the prompt

`withAttachments` ported to Kotlin against the test vectors in
`attach_prompt.ts` — the same method as the spiral, so the two clients build
one string rather than two similar ones:

```
<draft>

Attached files:
/abs/path/one
/abs/path/two
```

The alternative — teaching `send_prompt` an `attachments` parameter and
letting the hub compose the text — is cleaner in principle and is exactly the
MCP-surface cost this design exists to avoid.

`tooLong` is **not** ported. It guards a 128 KiB `MAX_ARG_STRLEN` ceiling, and
attachment paths are tens of bytes. Noted as an adjacent finding below.

### Budgets

`MAX_BYTES` (10 MB) and `MAX_TOTAL` (25 MB) are already in
`service::attachments` and already enforced host-side. The phone mirrors them
for UX — refusing a file at pick time rather than after an upload — the way
`src/lib/attachments.ts` mirrors them on the desktop. The desktop keeps the
two in step with a test that reads the TypeScript and fails on drift; the
phone needs the equivalent, reading the Rust constants.

## Security

This is the first path by which a phone pushes arbitrary bytes onto the
filesystem of a machine in the fleet. What holds:

- **The filename is not a path.** `name` goes through `basenames_of` and
  `dedupe_names` before it is used. A `name` of `../../.ssh/authorized_keys`
  must land as `authorized_keys` inside `ATTACH_DIR`, never outside it. This
  gets its own test with hostile names — it is the one thing here that turns
  a file upload into a host compromise.
- **The destination is not caller-chosen.** It is derived on the host from
  the session's own tmux pane (`root_script`), so the caller names a session,
  never a directory.
- **`full` only**, above.
- **Both ceilings**, above — `check_budget` runs host-side regardless of what
  the phone believes.
- **Quoting.** Every value interpolated into the SSH command goes through
  `crate::shell::quote`. `stage_script` and `root_script` already do; the new
  handler must not build a path string by hand.
- **The staging directory stays git-excluded**, via `stage_script`'s
  `--git-common-dir` write, so an attachment never shows up as a change the
  user has to explain.

## Not in scope

- `PHPickerViewController` / camera capture (above).
- Resumable or chunked upload. One request per file, 10 MB cap; a phone on a
  bad link retries the file.
- Cleaning `ATTACH_DIR` up. The desktop does not either; if it grows into a
  problem it is one problem for both clients.
- A server-side prompt-length bound (below).

## Adjacent finding, not fixed here

`PROMPT_MAX_BYTES` (120 KiB, modelling Linux's `MAX_ARG_STRLEN`) is enforced
**only in the desktop frontend**, in `attach_prompt.ts`. There is no such
check in `fleet-core`: no `validate::prompt`, nothing in
`service/sessions/prompt.rs`. So a long prompt sent from the phone today —
before any of this — reaches `tmux send-keys` unguarded and surfaces as a raw
"Argument list too long". Attachments do not make it worse (paths are small)
and fixing it belongs in `claude-fleet`, not here.

## Testing

**`claude-fleet`**
- Route tests beside `report_route.rs`'s: a `full` token uploads and gets a
  path; a `readonly` token gets 403; no token gets 401; a body over
  `MAX_BYTES` is refused by the limit layer.
- Hostile-filename tests: traversal, absolute paths, empty, a name that is
  only dots, duplicates within one send.
- A budget test crossing `MAX_TOTAL` across successive requests.

**`fleet-mobile`**
- `commonTest` for the Kotlin `withAttachments` against the TS vectors.
- `commonTest` for the pick-time budget refusals and their wording.
- A drift test reading the Rust constants, as the desktop's reads the TS.
- `HubClientTest` for the new call: it is not `tools/call`, so it needs its
  own framing test.
- A device test for the composer: chips appear, `×` removes, **+** is absent
  on a `readonly` credential.

## Work, in order

1. **`claude-fleet`** — `POST /attachment`: route, handler, auth gate, limit,
   tests. Self-contained; nothing on the phone needs it to exist to compile.
2. **`claude-fleet`** — document it in `docs/hub.md` under *Pair a phone*.
   No `REGEN_HUB_VERDICTS` run: the verdict table covers Tauri commands, and
   this adds none.
3. **`fleet-mobile`** — `withAttachments` + budget mirror in `commonMain`,
   with tests. No UI yet.
4. **`fleet-mobile`** — the `expect`/`actual` picker, both platforms.
5. **`fleet-mobile`** — `HubClient.uploadAttachment`, then the composer UI
   and its wiring into send.

Steps 1–2 and 3 are independent and can run in parallel. 4 and 5 need 3.
