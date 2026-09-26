# Phone Attachments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A **+** beside the phone's composer that puts a file from the phone into the session's worktree and names it in the prompt.

**Architecture:** The phone POSTs raw bytes to a new `POST /attachment` route on the hub — shaped like the existing `/report` route, so it costs nothing against the MCP tool surface and carries no base64. The hub stages the file into `<worktree>/.claude-fleet-attachments/` on the session's host over SSH, reusing the machinery the desktop composer already uses, and answers with the absolute remote path. The phone appends that path to the prompt and sends it with the existing `send_prompt` tool.

**Tech Stack:** Rust (axum 0.8, tokio, rusqlite) in `claude-fleet`; Kotlin Multiplatform (Compose Multiplatform 1.12, Ktor, kotlinx.serialization) in `fleet-mobile`.

**Spec:** `docs/superpowers/specs/2026-09-26-phone-attachments-design.md` (in `fleet-mobile`)

## Two repositories

This plan spans two checkouts. Every task names its repo. Do not mix them in one commit.

| Repo | Path used below | Build |
|---|---|---|
| `claude-fleet` | `$FLEET` | `cargo test -p fleet-core`, `cargo clippy --workspace --all-targets -- -D warnings`, `cargo fmt --all --check` |
| `fleet-mobile` | `$MOBILE` | `./gradlew :shared:jvmTest`, `:shared:compileKotlinIosSimulatorArm64`, `:androidApp:assembleDebug` |

Set them once per session:

```bash
FLEET=/mnt/sda4/projects/github.com/martin-janci/claude-fleet
MOBILE=/mnt/sda4/projects/github.com/martin-janci/fleet-mobile-worktrees/mobile-ux
```

`fleet-mobile` needs the Android SDK on the environment for anything Android:
`export ANDROID_HOME="$HOME/Android/Sdk"`.

`claude-fleet`'s `cargo` builds need the Tauri system libraries (dbus, gtk/atk, pkg-config). Tasks 1–4 touch `fleet-core`, which builds without them: prefer `cargo test -p fleet-core` over `--workspace` where the task allows it.

## Global Constraints

- **Per-file ceiling:** `attachments::MAX_BYTES` = `10 * 1024 * 1024`. **Batch ceiling:** `attachments::MAX_TOTAL` = `25 * 1024 * 1024`. Both already exist in `crates/fleet-core/src/service/attachments.rs`; never restate the numbers, import the constants.
- **Staging directory:** `attachments::ATTACH_DIR` = `.claude-fleet-attachments`, relative to the worktree root. Never hardcode the string.
- **Shell quoting:** every value interpolated into an SSH/bash command string MUST go through `crate::shell::quote` (alias `shq`). No exceptions, no second quoter.
- **`full` tokens only** may upload. A `readonly` client gets `403`.
- **No new MCP tool and no new MCP tool parameter.** The whole point of the route is to leave the tool surface untouched. Do not run `REGEN_HUB_VERDICTS`; the verdict table covers Tauri commands and this adds none.
- **SQLite:** access goes through `Store` behind a `std::sync::Mutex`. Never hold the guard across an `.await`.
- **Upload timeout:** 60 s, the existing `UPLOAD_TIMEOUT_SECS`.

---

### Task 1: Move the upload machinery into `fleet-core`

**Repo:** `claude-fleet`

The hub cannot call `src-tauri`. Everything the route needs beyond `service::attachments` currently lives in the desktop crate. This task moves it down and leaves the Tauri command a thin wrapper — the repo's stated convention ("thin Tauri command handlers in `commands/` wrap the transport-agnostic logic in `service/`"). No behaviour changes.

**Files:**
- Create: `$FLEET/crates/fleet-core/src/service/attachments/transfer.rs`
- Modify: `$FLEET/crates/fleet-core/src/service/attachments.rs` → becomes `attachments/mod.rs`
- Modify: `$FLEET/src-tauri/src/commands/upload.rs:418` (`basenames_of`), `:434` (`run_script`), `:461` (`resolve_worktree_root`), `:476` (`last_nonempty_line`), `:491` (`transfer_all`), `:670` (`dedupe_names`), `:691` (`suffix_name`)
- Test: `$FLEET/crates/fleet-core/src/service/attachments/transfer.rs` (`#[cfg(test)] mod tests`)

**Interfaces:**
- Consumes: `crate::ssh::SshClient::{run, upload_file}`, `crate::ipc_error::{IpcError, codes}`, `crate::shell::quote`
- Produces, all `pub` from `crate::service::attachments`:
  - `pub fn basenames_of(paths: &[String]) -> Vec<String>`
  - `pub fn dedupe_names(names: &[String]) -> Vec<String>`
  - `pub fn last_nonempty_line(stdout: &str, host: &str) -> Result<String, IpcError>`
  - `pub const UPLOAD_TIMEOUT_SECS: u64 = 60;`
  - `pub async fn run_script(ssh: &Arc<SshClient>, host: &str, script: &str, timeout: Duration) -> Result<(), IpcError>`
  - `pub async fn resolve_worktree_root(ssh: &Arc<SshClient>, host: &str, tmux_name: &str, timeout: Duration) -> Result<String, IpcError>`
  - `pub async fn transfer_all(ssh: &Arc<SshClient>, host: &str, local_paths: &[String], names: &[String], dir: &str, timeout: Duration) -> Result<Vec<String>, IpcError>`

- [ ] **Step 1: Turn the module into a directory**

```bash
cd "$FLEET"
mkdir -p crates/fleet-core/src/service/attachments
git mv crates/fleet-core/src/service/attachments.rs crates/fleet-core/src/service/attachments/mod.rs
```

Append to `crates/fleet-core/src/service/attachments/mod.rs`, above its `#[cfg(test)] mod tests`:

```rust
mod transfer;
pub use transfer::{
    basenames_of, dedupe_names, last_nonempty_line, resolve_worktree_root, run_script,
    transfer_all, UPLOAD_TIMEOUT_SECS,
};
```

- [ ] **Step 2: Run the build to confirm the move alone compiles**

Run: `cd "$FLEET" && cargo build -p fleet-core 2>&1 | tail -20`
Expected: FAIL — `file not found for module transfer`. That is the next step's job.

- [ ] **Step 3: Create `transfer.rs` with the moved functions**

Copy the seven items listed under *Interfaces* out of `src-tauri/src/commands/upload.rs` verbatim, changing only: `pub` visibility, `fleet_core::` prefixes dropped, and `use` lines rewritten to `crate::`. The header:

```rust
//! Staging a file on the session's host: naming it, finding the worktree, and
//! moving the bytes.
//!
//! This was `src-tauri/src/commands/upload.rs` until the hub needed it too.
//! The desktop reaches it through the `upload_attachments` Tauri command; the
//! hub reaches it through `POST /attachment`. Both do the same three things in
//! the same order — resolve the worktree root, stage the directory, transfer —
//! so the order lives here rather than twice.

use crate::ipc_error::{codes, IpcError};
use crate::shell::quote;
use crate::ssh::SshClient;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

/// How long any one upload step may take.
pub const UPLOAD_TIMEOUT_SECS: u64 = 60;
```

Move `suffix_name` too, but leave it private to this module.

- [ ] **Step 4: Move the tests that came with them**

Move these from `src-tauri/src/commands/upload.rs`'s test module into `transfer.rs`'s: `resolve_worktree_root_takes_the_last_line`, `resolve_worktree_root_refuses_empty_output`, and every `dedupe_names`/`basenames_of` test. Leave tests about `UploadAllowList` and `check_paths_allowed` where they are — those stay desktop-only.

- [ ] **Step 5: Run the fleet-core tests**

Run: `cd "$FLEET" && cargo test -p fleet-core attachments 2>&1 | tail -20`
Expected: PASS, with the moved tests now reported under `fleet-core`.

- [ ] **Step 6: Make the Tauri command a wrapper**

In `src-tauri/src/commands/upload.rs`, delete the seven moved items and import them:

```rust
use fleet_core::service::attachments::{
    basenames_of, dedupe_names, resolve_worktree_root, run_script, transfer_all,
    UPLOAD_TIMEOUT_SECS,
};
```

`upload_attachments` and `upload_to_session` keep their bodies unchanged — the call sites already read exactly like this.

- [ ] **Step 7: Verify the desktop crate still builds and its tests pass**

Run: `cd "$FLEET" && cargo test -p claude-fleet --lib upload 2>&1 | tail -20`
Expected: PASS. If the Tauri system libraries are missing on this machine the build script fails before compiling — that is an environment gap, not this change; say so and move on with `cargo check -p fleet-core` as the gate.

- [ ] **Step 8: Lint and format**

Run: `cd "$FLEET" && cargo fmt --all && cargo clippy -p fleet-core --all-targets -- -D warnings 2>&1 | tail -20`
Expected: clean.

- [ ] **Step 9: Commit**

```bash
cd "$FLEET"
git add crates/fleet-core/src/service/attachments src-tauri/src/commands/upload.rs
git commit -m "refactor(attachments): move the upload machinery into fleet-core

The hub is about to need what only the desktop crate could reach:
basenames_of, dedupe_names, resolve_worktree_root, run_script and
transfer_all were in src-tauri/src/commands/upload.rs. They are
transport-agnostic, so by the repo's own convention they belong in
service/ with the Tauri command as the wrapper. No behaviour change;
the tests moved with them."
```

---

### Task 2: The `POST /attachment` route

**Repo:** `claude-fleet`

**Files:**
- Create: `$FLEET/crates/fleet-core/src/mcp/attachment_route.rs`
- Modify: `$FLEET/crates/fleet-core/src/mcp/mod.rs` — the `mod` list, `build_app`'s signature, and the merged router at `:348`
- Modify: `$FLEET/src-tauri/src/lib.rs` and `$FLEET/crates/fleet-hub/src/serve.rs` — every `build_app` call site gains the new state argument

**Interfaces:**
- Consumes: Task 1's `resolve_worktree_root`, `run_script`, `transfer_all`, `basenames_of`, `dedupe_names`, `UPLOAD_TIMEOUT_SECS`; `attachments::{check_budget, stage_script, ATTACH_DIR}`; `mcp::auth::Caller`; `store::Store::get_session_by_id`
- Produces:
  - `pub struct AttachmentState { store: Arc<Mutex<Store>>, ssh: Arc<SshClient> }` with `pub fn new(store, ssh) -> Self`
  - `pub async fn handle_attachment(...) -> Response`
  - `pub struct AttachmentQuery { pub session_id: i64, pub name: String }`

- [ ] **Step 1: Write the failing test**

Create `crates/fleet-core/src/mcp/attachment_route.rs` with only the test module, copying the `app()` and `http()` harness from `report_route.rs`'s tests (lines 105–178) and adding `AttachmentState::new(Arc::clone(&store), Arc::new(SshClient::new()))` as `build_app`'s new argument. Then:

```rust
#[tokio::test]
async fn a_readonly_client_may_not_upload() {
    let (addr, _store) = app().await;
    let (st, _) = http(addr, "POST", "/attachment?session_id=1&name=a.txt", "ro-tok", "hi").await;
    assert_eq!(st, 403, "a readonly credential must not write bytes to a host");
}

#[tokio::test]
async fn an_unauthenticated_upload_is_refused() {
    let (addr, _store) = app().await;
    let (st, _) = http(addr, "POST", "/attachment?session_id=1&name=a.txt", "wrong", "hi").await;
    assert_eq!(st, 401);
}
```

Add `s.insert_client_token("phone-full", &auth::sha256_hex("full-tok"), "full").unwrap();` to the harness's store setup — later tests need it.

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd "$FLEET" && cargo test -p fleet-core attachment_route 2>&1 | tail -20`
Expected: FAIL to compile — `AttachmentState` not found.

- [ ] **Step 3: Write the handler**

Above the test module in the same file:

```rust
//! `POST /attachment`: a paired client stages one file in a session's
//! worktree. Sits behind `authorize`, like `/report`.
//! Spec: fleet-mobile docs/superpowers/specs/2026-09-26-phone-attachments-design.md
//!
//! Why a route and not an MCP tool: the tool surface is under budget
//! pressure, and `tools/call` would carry the bytes base64 through a
//! transport built for control messages. This takes them raw.

use super::auth::Caller;
use crate::ipc_error::codes;
use crate::service::attachments::{
    self, basenames_of, dedupe_names, resolve_worktree_root, run_script, transfer_all,
    UPLOAD_TIMEOUT_SECS,
};
use crate::ssh::SshClient;
use crate::store::Store;
use axum::extract::{Query, State};
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::{Extension, Json};
use serde::Deserialize;
use std::sync::{Arc, Mutex};
use std::time::Duration;

#[derive(Clone)]
pub struct AttachmentState {
    store: Arc<Mutex<Store>>,
    ssh: Arc<SshClient>,
}

impl AttachmentState {
    pub fn new(store: Arc<Mutex<Store>>, ssh: Arc<SshClient>) -> Self {
        AttachmentState { store, ssh }
    }
}

#[derive(Deserialize)]
pub struct AttachmentQuery {
    pub session_id: i64,
    /// The file's name as the phone knows it. Treated as a *suggestion*: it
    /// is reduced to a basename before it is used, so it cannot be a path.
    pub name: String,
}

pub async fn handle_attachment(
    State(state): State<AttachmentState>,
    Extension(caller): Extension<Caller>,
    Query(q): Query<AttachmentQuery>,
    body: axum::body::Bytes,
) -> Response {
    if crate::mcp::auth::refuses_peer(&caller).is_some() {
        return StatusCode::FORBIDDEN.into_response();
    }
    // Writing bytes onto a machine in the fleet is not something a readonly
    // credential does. `/report` accepts one because posting your own crash
    // changes nothing; this is not that.
    if caller.mode != crate::store::TokenMode::Full {
        return (StatusCode::FORBIDDEN, "attaching needs a full token").into_response();
    }

    // The name is reduced to a basename BEFORE anything else looks at it: a
    // `name` of `../../.ssh/authorized_keys` must land as
    // `authorized_keys` inside ATTACH_DIR, never outside it.
    let name = match basenames_of(&[q.name.clone()]).pop().filter(|n| !n.is_empty()) {
        Some(n) => n,
        None => return (StatusCode::BAD_REQUEST, "no usable filename").into_response(),
    };

    if let Err(why) = attachments::check_budget(&[(name.clone(), body.len() as u64)]) {
        return (StatusCode::PAYLOAD_TOO_LARGE, why).into_response();
    }

    // The guard is released before the first `.await`.
    let session = {
        let s = match state.store.lock() {
            Ok(s) => s,
            Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        };
        match s.get_session_by_id(q.session_id) {
            Ok(Some(row)) => row,
            Ok(None) => return (StatusCode::NOT_FOUND, "no such session").into_response(),
            Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        }
    };

    let timeout = Duration::from_secs(UPLOAD_TIMEOUT_SECS);
    let host = &session.host_alias;
    let root = match resolve_worktree_root(&state.ssh, host, &session.tmux_name, timeout).await {
        Ok(r) => r,
        Err(e) => return attach_error(e),
    };
    let dir = format!("{root}/{}", attachments::ATTACH_DIR);
    if let Err(e) = run_script(&state.ssh, host, &attachments::stage_script(&root), timeout).await {
        return attach_error(e);
    }

    // The bytes arrive in memory (the body limit is the per-file ceiling), and
    // `transfer_all` reads from disk, so they go to a temp file first. It is
    // removed whatever happens next.
    let tmp = match spill(&body, &name) {
        Ok(t) => t,
        Err(e) => return attach_error(e),
    };
    let local = vec![tmp.path().to_string_lossy().into_owned()];
    let names = dedupe_names(&[name]);
    let out = transfer_all(&state.ssh, host, &local, &names, &dir, timeout).await;
    drop(tmp);

    match out {
        Ok(paths) => match paths.into_iter().next() {
            Some(path) => Json(serde_json::json!({ "path": path })).into_response(),
            None => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        },
        Err(e) => attach_error(e),
    }
}

fn attach_error(e: crate::ipc_error::IpcError) -> Response {
    tracing::warn!(code = %e.code, error = %e.message, "[attachment] failed");
    let status = if e.code == codes::E_HOST_OFFLINE {
        StatusCode::BAD_GATEWAY
    } else {
        StatusCode::INTERNAL_SERVER_ERROR
    };
    (status, e.message).into_response()
}

/// Write the body to a temp file `transfer_all` can read.
fn spill(bytes: &[u8], name: &str) -> Result<tempfile::NamedTempFile, crate::ipc_error::IpcError> {
    use std::io::Write;
    let mut f = tempfile::Builder::new()
        .prefix("fleet-attach-")
        .suffix(&format!("-{name}"))
        .tempfile()
        .map_err(|e| crate::ipc_error::IpcError::new(codes::E_UPLOAD, format!("temp file: {e}")))?;
    f.write_all(bytes)
        .map_err(|e| crate::ipc_error::IpcError::new(codes::E_UPLOAD, format!("temp write: {e}")))?;
    Ok(f)
}
```

If `tempfile` is not already a `fleet-core` dependency, add it to `crates/fleet-core/Cargo.toml` and run `cargo deny check` before committing.

- [ ] **Step 4: Register the route**

In `crates/fleet-core/src/mcp/mod.rs`, add `pub mod attachment_route;` beside the other route modules. Add a parameter `attachment_state: attachment_route::AttachmentState` to `build_app`. Merge it into the **authorized** router, immediately after the `/report` merge at `:348`:

```rust
        // `/attachment` is `/report`'s shape with a bigger body and a
        // different destination: behind `authorize`, its own state, its own
        // limit. The limit is the per-file ceiling itself, so a file too big
        // is refused by the transport rather than after it is in memory.
        .merge(
            axum::Router::new()
                .route(
                    "/attachment",
                    axum::routing::post(attachment_route::handle_attachment),
                )
                .layer(axum::extract::DefaultBodyLimit::max(
                    crate::service::attachments::MAX_BYTES as usize,
                ))
                .with_state(attachment_state),
        )
```

- [ ] **Step 5: Update every `build_app` call site**

Run: `cd "$FLEET" && grep -rn "build_app(" --include='*.rs' crates/ src-tauri/ | grep -v "fn build_app"`

Each call gains `attachment_route::AttachmentState::new(<the store it already passes>, <the ssh it already passes>)`. In the test harnesses that pass `SshClient::new()` to `HookState`, reuse the same `Arc`.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd "$FLEET" && cargo test -p fleet-core attachment_route 2>&1 | tail -20`
Expected: PASS — both the 403 and the 401.

- [ ] **Step 7: Lint and format**

Run: `cd "$FLEET" && cargo fmt --all && cargo clippy -p fleet-core --all-targets -- -D warnings 2>&1 | tail -20`
Expected: clean.

- [ ] **Step 8: Commit**

```bash
cd "$FLEET"
git add crates/fleet-core/src/mcp/attachment_route.rs crates/fleet-core/src/mcp/mod.rs crates/fleet-core/Cargo.toml src-tauri crates/fleet-hub
git commit -m "feat(hub): POST /attachment stages one file in a session worktree

Shaped like /report: behind authorize, its own state, its own body limit
— which is the per-file ceiling itself, so an oversized file is refused
by the transport. full tokens only: writing bytes onto a machine in the
fleet is not what a readonly credential does.

No MCP tool and no new tool parameter: the surface is under budget
pressure and tools/call would carry the bytes base64."
```

---

### Task 3: The filename cannot be a path

**Repo:** `claude-fleet`

Task 2 wrote the guard. This task is the evidence that it holds — the one thing here that turns a file upload into a host compromise if it is wrong.

**Files:**
- Modify: `$FLEET/crates/fleet-core/src/mcp/attachment_route.rs` (test module)
- Modify: `$FLEET/crates/fleet-core/src/service/attachments/transfer.rs` (test module)

**Interfaces:**
- Consumes: Task 1's `basenames_of`, `dedupe_names`; Task 2's route
- Produces: nothing new

- [ ] **Step 1: Write the failing tests**

In `transfer.rs`'s test module:

```rust
#[test]
fn a_filename_can_never_be_a_path() {
    // Every one of these must reduce to a bare name. If any keeps a slash
    // or a `..`, the route writes outside ATTACH_DIR.
    for hostile in [
        "../../.ssh/authorized_keys",
        "/etc/passwd",
        "..",
        ".",
        "a/b/c.txt",
        r"..\..\windows\system32",
    ] {
        let got = basenames_of(&[hostile.to_string()]).pop().unwrap();
        assert!(!got.contains('/'), "{hostile} kept a slash: {got}");
        assert_ne!(got, "..", "{hostile} reduced to a parent reference");
        assert_ne!(got, ".", "{hostile} reduced to a self reference");
    }
}

#[test]
fn a_name_with_nothing_usable_in_it_reduces_to_empty() {
    // The route turns this into 400 rather than inventing a name.
    assert!(basenames_of(&["".to_string()]).pop().unwrap().is_empty());
}
```

- [ ] **Step 2: Run them to see which pass**

Run: `cd "$FLEET" && cargo test -p fleet-core a_filename_can_never 2>&1 | tail -20`
Expected: FAIL on at least `..`, `.` and the backslash case — `basenames_of` uses `Path::file_name`, which returns `None` for `..` and keeps a Windows-style backslash name whole on Unix.

- [ ] **Step 3: Harden `basenames_of`**

In `transfer.rs`:

```rust
/// The last path component of each input, as a name that cannot escape a
/// directory. `Path::file_name` alone is not enough: it answers `None` for
/// `..`, and on Unix a `\` is an ordinary character, so a Windows-style path
/// arrives as one long "name". Anything with no usable component left
/// becomes an empty string, which every caller must treat as a refusal.
pub fn basenames_of(paths: &[String]) -> Vec<String> {
    paths
        .iter()
        .map(|p| {
            let last = p.rsplit(['/', '\\']).next().unwrap_or("");
            if last == "." || last == ".." { "" } else { last }.to_string()
        })
        .collect()
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd "$FLEET" && cargo test -p fleet-core attachments 2>&1 | tail -20`
Expected: PASS, including the tests moved in Task 1.

- [ ] **Step 5: Add the route-level tests**

In `attachment_route.rs`'s test module:

```rust
#[tokio::test]
async fn a_traversing_filename_is_refused_rather_than_escaping_the_directory() {
    let (addr, _store) = app().await;
    let (st, body) = http(
        addr,
        "POST",
        "/attachment?session_id=1&name=..%2F..%2F.ssh%2Fauthorized_keys",
        "full-tok",
        "ssh-rsa AAAA",
    )
    .await;
    // Either the session lookup refuses it first (404) or it is staged as a
    // bare name — never a 2xx naming a path outside ATTACH_DIR.
    assert_ne!(st, 200, "a traversing name was accepted: {body}");
    assert!(!body.contains(".ssh"), "the answer named a path outside the dir: {body}");
}

#[tokio::test]
async fn a_body_over_the_per_file_ceiling_is_refused() {
    let (addr, _store) = app().await;
    let big = "x".repeat(fleet_core_max_bytes() + 1);
    let (st, _) = http(addr, "POST", "/attachment?session_id=1&name=big.bin", "full-tok", &big).await;
    assert_eq!(st, 413, "the body limit layer should refuse before the handler runs");
}

fn fleet_core_max_bytes() -> usize {
    crate::service::attachments::MAX_BYTES as usize
}
```

- [ ] **Step 6: Run them**

Run: `cd "$FLEET" && cargo test -p fleet-core attachment_route 2>&1 | tail -20`
Expected: PASS, four tests.

- [ ] **Step 7: Lint, format, commit**

```bash
cd "$FLEET"
cargo fmt --all && cargo clippy -p fleet-core --all-targets -- -D warnings
git add crates/fleet-core/src/mcp/attachment_route.rs crates/fleet-core/src/service/attachments/transfer.rs
git commit -m "test(attachments): a filename from a phone can never be a path

basenames_of leaned on Path::file_name, which answers None for '..' and
treats a backslash as an ordinary Unix character — so '..' and a
Windows-style path both got through. Reduce on both separators and
refuse '.' and '..' outright; a name with nothing usable left is empty,
which the route turns into 400."
```

---

### Task 4: Document the route

**Repo:** `claude-fleet`

**Files:**
- Modify: `$FLEET/docs/hub.md` — the *Pair a phone* section

**Interfaces:**
- Consumes: Task 2's route shape
- Produces: nothing code-facing

- [ ] **Step 1: Add the section**

After the *Pair a phone* subsection on client capabilities, add:

````markdown
### Attaching a file

A `full` client may stage one file in a session's worktree:

```
POST /attachment?session_id=<id>&name=<filename>
Authorization: Bearer <client token>
Content-Type: application/octet-stream

<raw bytes>
```

It answers `{"path": "/abs/path/on/the/host/<name>"}`. Put that path in the
prompt (`send_prompt`) and the agent can read it without a permission
prompt — the file lands under the worktree root, in
`.claude-fleet-attachments/`, which is excluded untracked so it never shows
as a change.

Not an MCP tool on purpose: the bytes travel raw rather than base64 through
`tools/call`, and the tool surface is left alone.

- **`full` only.** A `readonly` client gets `403` — this writes to a machine
  in the fleet, unlike `/report`, which any client may post to.
- **One file per request**, at most 10 MB (`MAX_BYTES`). The 25 MB batch
  ceiling is the client's to keep across requests.
- **The name is a suggestion.** It is reduced to a bare filename before use,
  so it cannot address a directory; a name with nothing usable in it is
  `400`. Collisions within a send get `-1`, `-2`, … before the extension.
````

- [ ] **Step 2: Check the docs test still passes**

Run: `cd "$FLEET" && cargo test -p fleet-core reference_is_current 2>&1 | tail -10`
Expected: PASS unchanged — that test generates `docs/control-api-reference.md` from the MCP tool router, and this task adds no tool. If it fails, something in Task 2 added a tool that should not exist; go back.

- [ ] **Step 3: Commit**

```bash
cd "$FLEET"
git add docs/hub.md
git commit -m "docs(hub): how a phone attaches a file"
```

---

### Task 5: `withAttachments` and the budget mirror on the phone

**Repo:** `fleet-mobile`

Pure logic, no UI, no network. Written first so the UI tasks have something tested to call.

**Files:**
- Create: `$MOBILE/shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/Attachment.kt`
- Test: `$MOBILE/shared/src/commonTest/kotlin/dev/claudefleet/mobile/model/AttachmentTest.kt`

**Interfaces:**
- Consumes: nothing
- Produces:
  - `data class PickedFile(val name: String, val size: Long, val bytes: ByteArray)`
  - `const val ATTACH_MAX_BYTES: Long`, `const val ATTACH_MAX_TOTAL: Long`
  - `fun fmtBytes(n: Long): String`
  - `fun checkBudget(files: List<Pair<String, Long>>): String?` — the refusal sentence, or `null`
  - `fun withAttachments(draft: String, paths: List<String>): String`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.claudefleet.mobile.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The phone's half of the attachment contract. Both halves are ports of the
 * desktop's — `src/lib/attach_prompt.ts` for the prompt, and
 * `crates/fleet-core/src/service/attachments.rs` for the budget and its
 * wording — so these are the same vectors both of those assert.
 */
class AttachmentTest {
    @Test
    fun a_draft_with_no_attachments_is_unchanged() {
        assertEquals("hello", withAttachments("hello", emptyList()))
    }

    @Test
    fun paths_go_in_a_block_after_the_draft() {
        assertEquals(
            "hello\n\nAttached files:\n/a/one.png\n/a/two.log",
            withAttachments("hello", listOf("/a/one.png", "/a/two.log")),
        )
    }

    @Test
    fun an_empty_draft_is_the_block_alone() {
        assertEquals("Attached files:\n/a/one.png", withAttachments("   ", listOf("/a/one.png")))
    }

    @Test
    fun the_budget_wording_is_the_hosts_own() {
        assertNull(checkBudget(listOf("small.png" to 1024L)))
        assertEquals(
            "big.png is 10.0 MB — the limit is 10 MB.",
            checkBudget(listOf("big.png" to ATTACH_MAX_BYTES + 1)),
        )
        val nine = 9L * 1024 * 1024
        assertEquals(
            "c.bin would make 27.0 MB in total — the limit is 25 MB in total.",
            checkBudget(listOf("a.bin" to nine, "b.bin" to nine, "c.bin" to nine)),
        )
    }

    @Test
    fun exactly_on_the_line_is_allowed_and_one_byte_more_is_not() {
        assertNull(checkBudget(listOf("edge.bin" to ATTACH_MAX_BYTES)))
        assertEquals(
            "edge.bin is 10.0 MB — the limit is 10 MB.",
            checkBudget(listOf("edge.bin" to ATTACH_MAX_BYTES + 1)),
        )
    }

    @Test
    fun fmt_bytes_reads_the_way_the_host_says_it() {
        assertEquals("512 B", fmtBytes(512))
        assertEquals("1.0 MB", fmtBytes(1024 * 1024))
        assertEquals("11.0 MB", fmtBytes(11L * 1024 * 1024))
    }
}
```

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd "$MOBILE" && ./gradlew :shared:jvmTest --tests "dev.claudefleet.mobile.model.AttachmentTest" 2>&1 | grep -E "^e:|BUILD"`
Expected: FAIL to compile — `withAttachments` unresolved.

- [ ] **Step 3: Write the implementation**

```kotlin
package dev.claudefleet.mobile.model

import kotlin.math.round

/**
 * A file the person picked, held in memory until Send. Nothing leaves the
 * phone before then: a file queued and removed was never uploaded.
 */
class PickedFile(val name: String, val size: Long, val bytes: ByteArray)

/** Per-file ceiling. Mirrors `attachments::MAX_BYTES`. */
const val ATTACH_MAX_BYTES: Long = 10L * 1024 * 1024

/** Ceiling for one send's attachments together. Mirrors `attachments::MAX_TOTAL`. */
const val ATTACH_MAX_TOTAL: Long = 25L * 1024 * 1024

private const val MAX_BYTES_MB = ATTACH_MAX_BYTES / (1024 * 1024)
private const val MAX_TOTAL_MB = ATTACH_MAX_TOTAL / (1024 * 1024)

/**
 * Mirrors `fmt_bytes` in `service/attachments.rs`, so a refusal shown here
 * reads exactly like the one the hub would have sent back.
 */
fun fmtBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${round(n / 1024.0).toLong()} KB"
    else -> {
        val mb = n / (1024.0 * 1024.0)
        val tenths = round(mb * 10).toLong()
        "${tenths / 10}.${tenths % 10} MB"
    }
}

/**
 * Both ceilings, in order, returning the first refusal as the sentence to
 * show — or `null` when the batch fits. This is UX: it stops someone queueing
 * a file that will not go. The bound that actually holds is `check_budget` on
 * the host, which runs whatever this believes.
 */
fun checkBudget(files: List<Pair<String, Long>>): String? {
    var total = 0L
    for ((name, size) in files) {
        if (size > ATTACH_MAX_BYTES) {
            return "$name is ${fmtBytes(size)} — the limit is $MAX_BYTES_MB MB."
        }
        total += size
        if (total > ATTACH_MAX_TOTAL) {
            return "$name would make ${fmtBytes(total)} in total — " +
                "the limit is $MAX_TOTAL_MB MB in total."
        }
    }
    return null
}

/**
 * Put the staged remote paths into the prompt. A port of `withAttachments` in
 * the desktop's `src/lib/attach_prompt.ts`, so both clients build one string.
 */
fun withAttachments(draft: String, paths: List<String>): String {
    if (paths.isEmpty()) return draft
    val block = "Attached files:\n" + paths.joinToString("\n")
    return if (draft.trim().isEmpty()) block else "$draft\n\n$block"
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd "$MOBILE" && ./gradlew :shared:jvmTest --tests "dev.claudefleet.mobile.model.AttachmentTest" 2>&1 | grep -E "^e:|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Add the drift test**

The desktop keeps its TypeScript mirror honest with a test that reads the other language's source. Do the same, in `$MOBILE/shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/AttachmentDriftTest.kt`. It reads `claude-fleet`'s Rust constants when that checkout is beside this one, and skips when it is not — the same shape the repo's other host tests use for optional neighbours (see `Repo.kt`).

```kotlin
package dev.claudefleet.mobile.host

import dev.claudefleet.mobile.model.ATTACH_MAX_BYTES
import dev.claudefleet.mobile.model.ATTACH_MAX_TOTAL
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The phone's ceilings against the host's. They are the same two numbers in
 * two languages, and the host's are the ones that hold — so if they drift,
 * a phone refuses a file the hub would have taken, or (worse) queues one it
 * will refuse after the upload.
 *
 * Skipped when `claude-fleet` is not checked out beside this repo: this is a
 * cross-repo check, not a reason CI cannot run here.
 */
class AttachmentDriftTest {
    @Test
    fun the_ceilings_match_the_hosts() {
        // `Repo.root` climbs to settings.gradle.kts; `Repo.file` fails on a
        // missing path, so this builds the File directly and skips instead.
        val neighbour = File(Repo.root.parentFile, "claude-fleet/crates/fleet-core/src/service")
        val rust = File(neighbour, "attachments/mod.rs").takeIf { it.isFile }
            ?: File(neighbour, "attachments.rs").takeIf { it.isFile }
            ?: return
        val text = rust.readText()
        assertEquals(
            10L * 1024 * 1024,
            Regex("MAX_BYTES: u64 = ([^;]+);").find(text)!!.groupValues[1].evalMib(),
        )
        assertEquals(ATTACH_MAX_BYTES, 10L * 1024 * 1024)
        assertEquals(ATTACH_MAX_TOTAL, 25L * 1024 * 1024)
        assertEquals(
            25L * 1024 * 1024,
            Regex("MAX_TOTAL: u64 = ([^;]+);").find(text)!!.groupValues[1].evalMib(),
        )
    }

    /** `10 * 1024 * 1024` → 10485760. Only the one shape these constants use. */
    private fun String.evalMib(): Long =
        trim().split("*").map { it.trim().toLong() }.reduce(Long::times)
}
```

- [ ] **Step 6: Run the whole shared suite**

Run: `cd "$MOBILE" && ./gradlew :shared:jvmTest 2>&1 | grep -E "^e:|FAILED|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
cd "$MOBILE"
git add shared/src/commonMain/kotlin/dev/claudefleet/mobile/model/Attachment.kt \
        shared/src/commonTest/kotlin/dev/claudefleet/mobile/model/AttachmentTest.kt \
        shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/AttachmentDriftTest.kt
git commit -m "feat(attachments): the prompt block and the budget, on the phone

withAttachments ported from the desktop's attach_prompt.ts against its own
vectors, so both clients build one string rather than two similar ones. The
ceilings and their wording mirror service/attachments.rs; a host test reads
the Rust and fails on drift, the way the desktop's reads the TypeScript."
```

---

### Task 6: `HubClient.uploadAttachment`

**Repo:** `fleet-mobile`

**Files:**
- Modify: `$MOBILE/shared/src/commonMain/kotlin/dev/claudefleet/mobile/net/HubClient.kt`
- Test: `$MOBILE/shared/src/commonTest/kotlin/dev/claudefleet/mobile/net/HubClientTest.kt`

**Interfaces:**
- Consumes: Task 5's `PickedFile`; Task 2's route
- Produces: `suspend fun HubClient.uploadAttachment(sessionId: Long, file: PickedFile): String` — the staged absolute path, or throws `HubError`

- [ ] **Step 1: Write the failing test**

Append to `HubClientTest.kt`, following the mock-engine pattern already in that file:

```kotlin
@Test
fun upload_attachment_posts_raw_bytes_and_returns_the_staged_path() = runTest {
    var seenUrl: String? = null
    var seenAuth: String? = null
    var seenBody: ByteArray? = null
    // `client(...)` is the file's own MockEngine builder; `Calls` already
    // records every request, so nothing new is needed to inspect one.
    val (hub, calls) = client { _ ->
        """{"path":"/w/proj/.claude-fleet-attachments/a.png"}""" to HttpStatusCode.OK
    }
    val path = hub.uploadAttachment(7, PickedFile("a.png", 3, byteArrayOf(1, 2, 3)))
    assertEquals("/w/proj/.claude-fleet-attachments/a.png", path)

    val req = calls.requests.single()
    val url = req.url.toString()
    assertTrue(url.startsWith("$BASE/attachment"), url)
    assertTrue(url.contains("session_id=7"), url)
    assertTrue(url.contains("name=a.png"), url)
    assertEquals("Bearer tok-phone", req.headers["Authorization"])
    // Raw, not base64 and not JSON: the whole reason this is a route and not
    // a tool. A TextContent body here means it went out as a string.
    assertContentEquals(byteArrayOf(1, 2, 3), (req.body as ByteArrayContent).bytes())
}

@Test
fun a_refused_upload_raises_rather_than_returning_a_path() = runTest {
    val (hub, _) = client { _ -> "attaching needs a full token" to HttpStatusCode.Forbidden }
    val e = assertFailsWith<HubError> {
        hub.uploadAttachment(7, PickedFile("a.png", 1, byteArrayOf(1)))
    }
    assertTrue(e.message!!.contains("full token"), e.message!!)
}
```

Import `io.ktor.http.content.ByteArrayContent`. `client(...)` answers with
`jsonHeaders` for any body that does not start with `event:`, which is what
this route sends, so no change to the helper is needed.

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd "$MOBILE" && ./gradlew :shared:jvmTest --tests "dev.claudefleet.mobile.net.HubClientTest" 2>&1 | grep -E "^e:|BUILD"`
Expected: FAIL to compile — `uploadAttachment` unresolved.

- [ ] **Step 3: Write the implementation**

In `HubClient.kt`, beside the other calls:

```kotlin
    /**
     * Stage one file in a session's worktree and answer the absolute path it
     * landed at, ready to go into a prompt.
     *
     * Not a `tools/call`: `POST /attachment` is its own route precisely so the
     * bytes travel raw rather than base64 inside JSON-RPC. So it does not go
     * through [send], which sets the MCP content type and Accept headers.
     *
     * The name rides in the query string, and the hub treats it as a
     * suggestion — it is reduced to a bare filename there, so a name that
     * looks like a path cannot address one.
     */
    suspend fun uploadAttachment(sessionId: Long, file: PickedFile): String {
        val url = "$base/attachment?session_id=$sessionId&name=${file.name.encodeURLParameter()}"
        val response: HttpResponse = try {
            http.post(url) {
                contentType(ContentType.Application.OctetStream)
                if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
                setBody(file.bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: HubError) {
            throw e
        } catch (t: Throwable) {
            throw HubError.Transport(t)
        }
        val text = response.textWithin(MAX_RESPONSE_BYTES)
        // The hub answers a plain sentence on refusal, not JSON, and it is
        // written to be shown — so the existing variants carry it as-is.
        when (response.status) {
            HttpStatusCode.Unauthorized -> throw HubError.Unauthorized(text)
            HttpStatusCode.Forbidden -> throw HubError.Forbidden(text, base)
            else -> if (!response.status.isSuccess()) {
                throw HubError.Http(response.status.value, text)
            }
        }
        val path = (parseObject(text)["path"] as? JsonPrimitive)?.content
        return path ?: throw HubError.Http(response.status.value, "staged the file but did not say where")
    }
```

Add the imports Ktor needs: `io.ktor.http.encodeURLParameter`, `io.ktor.http.isSuccess`, `io.ktor.http.HttpStatusCode`. The variants used here are the ones already in `net/HubError.kt`: `Unauthorized(detail)`, `Forbidden(body, hub)` and `Http(status, body)`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd "$MOBILE" && ./gradlew :shared:jvmTest --tests "dev.claudefleet.mobile.net.HubClientTest" 2>&1 | grep -E "^e:|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Check the tool-surface guard still passes**

Run: `cd "$MOBILE" && ./gradlew :shared:jvmTest --tests "dev.claudefleet.mobile.net.ToolsTheAppMayCallTest" 2>&1 | grep -E "^e:|FAILED|BUILD"`
Expected: PASS unchanged — this adds no tool. If it fails, `uploadAttachment` was routed through `call`/`rpc` instead of its own `post`; fix that rather than the test.

- [ ] **Step 6: Commit**

```bash
cd "$MOBILE"
git add shared/src/commonMain/kotlin/dev/claudefleet/mobile/net/HubClient.kt \
        shared/src/commonTest/kotlin/dev/claudefleet/mobile/net/HubClientTest.kt
git commit -m "feat(net): uploadAttachment posts raw bytes to POST /attachment

Its own post, not send(): the route exists so the bytes travel raw rather
than base64 inside a JSON-RPC envelope, so it must not pick up the MCP
content type and Accept headers on the way out."
```

---

### Task 7: The file picker

**Repo:** `fleet-mobile`

The fourth `expect`/`actual` family, after `Platform`, `Secrets` and `QrScanner`. `PlatformActualsTest` counts them — expect to update it.

**Files:**
- Create: `$MOBILE/shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/pick/FilePicker.kt`
- Create: `$MOBILE/shared/src/androidMain/kotlin/dev/claudefleet/mobile/ui/pick/FilePicker.android.kt`
- Create: `$MOBILE/shared/src/iosMain/kotlin/dev/claudefleet/mobile/ui/pick/FilePicker.ios.kt`
- Create: `$MOBILE/shared/src/jvmMain/kotlin/dev/claudefleet/mobile/ui/pick/FilePicker.jvm.kt`
- Modify: `$MOBILE/shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/PlatformActualsTest.kt`

**Interfaces:**
- Consumes: Task 5's `PickedFile`
- Produces:
  - `expect fun filePickerSupported(): Boolean`
  - `@Composable expect fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit`

- [ ] **Step 1: Write the `expect` declarations**

```kotlin
package dev.claudefleet.mobile.ui.pick

import androidx.compose.runtime.Composable
import dev.claudefleet.mobile.model.PickedFile

/**
 * Picking a file to attach: the app's fourth `expect`/`actual` family, after
 * `Platform`, `Secrets` and `QrScanner`.
 *
 * It is the *whole* picker rather than a thin file-system wrapper, for the
 * same reason `QrScanner` is: Android's `OpenMultipleDocuments` and iOS's
 * `UIDocumentPickerViewController` have nothing in common but the result.
 *
 * The bytes come back in memory. That is deliberate and bounded — the
 * per-file ceiling is 10 MB (`ATTACH_MAX_BYTES`) and the caller refuses
 * anything bigger before it is held.
 *
 * **On iOS this sees Files, not Photos.** A screenshot lives in Photos, so it
 * is not reachable here until someone saves it to Files. Adding
 * `PHPickerViewController` later is additive: another source producing the
 * same [PickedFile], and nothing else changes.
 */
expect fun filePickerSupported(): Boolean

/**
 * Remember a launcher. Calling the returned function opens the system picker;
 * [onPicked] fires with what came back, and with an empty list if the person
 * cancelled — so a caller can always stop showing a spinner.
 */
@Composable
expect fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit
```

- [ ] **Step 2: Write the Android actual**

```kotlin
package dev.claudefleet.mobile.ui.pick

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import dev.claudefleet.mobile.model.PickedFile

/** Always: `OpenMultipleDocuments` is part of the platform, not an optional app. */
actual fun filePickerSupported(): Boolean = true

@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        // No storage permission is asked for and none is needed: the picker
        // runs outside this app and hands back a read grant per URI.
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        onPicked(uris.mapNotNull { read(context.contentResolver, it) })
    }
    // Every type. The system picker lists Photos among its providers, so a
    // screenshot is reachable here without a second, photo-specific flow.
    return { launcher.launch(arrayOf("*/*")) }
}

private fun read(resolver: android.content.ContentResolver, uri: Uri): PickedFile? {
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        ?: uri.lastPathSegment
        ?: return null
    val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    return PickedFile(name = name, size = bytes.size.toLong(), bytes = bytes)
}
```

- [ ] **Step 3: Write the iOS actual**

```kotlin
package dev.claudefleet.mobile.ui.pick

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.claudefleet.mobile.model.PickedFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeItem
import platform.darwin.NSObject
import platform.posix.memcpy

actual fun filePickerSupported(): Boolean = true

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit {
    // Held across recompositions: UIKit keeps only a weak reference to a
    // delegate, so a local would be collected before the person picks.
    val delegate = remember {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            var onResult: (List<PickedFile>) -> Unit = {}

            override fun documentPicker(
                controller: UIDocumentPickerViewController,
                didPickDocumentsAtURLs: List<*>,
            ) {
                onResult(didPickDocumentsAtURLs.filterIsInstance<NSURL>().mapNotNull { read(it) })
            }

            // Cancelling must still report, so the caller can stop waiting.
            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                onResult(emptyList())
            }
        }
    }
    delegate.onResult = onPicked
    return {
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeItem),
            asCopy = true,
        )
        picker.delegate = delegate
        picker.allowsMultipleSelection = true
        UIApplication.sharedApplication.keyWindow
            ?.rootViewController
            ?.presentViewController(picker, animated = true, completion = null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun read(url: NSURL): PickedFile? {
    // `asCopy = true` above puts the file in this app's temp directory, so it
    // is already ours to read and no security-scoped access is needed.
    val data: NSData = NSData.dataWithContentsOfURL(url) ?: return null
    val length = data.length.toInt()
    val bytes = ByteArray(length)
    if (length > 0) {
        bytes.usePinned { memcpy(it.addressOf(0), data.bytes, data.length) }
    }
    return PickedFile(name = url.lastPathComponent ?: "file", size = length.toLong(), bytes = bytes)
}
```

- [ ] **Step 4: Write the JVM actual**

`jvmMain` exists so the shared module compiles for host tests; `QrScanner.jvm.kt` is the precedent for a stub.

```kotlin
package dev.claudefleet.mobile.ui.pick

import androidx.compose.runtime.Composable
import dev.claudefleet.mobile.model.PickedFile

/** No picker off a device; the JVM target exists for host tests. */
actual fun filePickerSupported(): Boolean = false

@Composable
actual fun rememberFilePicker(onPicked: (List<PickedFile>) -> Unit): () -> Unit = { onPicked(emptyList()) }
```

- [ ] **Step 5: Update the actuals inventory**

Open `$MOBILE/shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/PlatformActualsTest.kt`, read what it asserts, and add `FilePicker` to the expected set with the same shape the other three use. Also update the README sentence that says the app has three `expect` declarations — `grep -n "three" $MOBILE/README.md`.

- [ ] **Step 6: Compile every target**

```bash
cd "$MOBILE"
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :shared:jvmTest 2>&1 | grep -E "^e:|FAILED|BUILD"
./gradlew :shared:compileKotlinIosSimulatorArm64 --max-workers=1 2>&1 | grep -E "^e:|BUILD"
./gradlew :androidApp:assembleDebug --max-workers=1 2>&1 | grep -E "^e:|BUILD"
```

Expected: three × `BUILD SUCCESSFUL`. Run them one at a time — a full `./gradlew build` links both iOS targets at once and has run this machine out of memory.

- [ ] **Step 7: Commit**

```bash
cd "$MOBILE"
git add shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/pick \
        shared/src/androidMain/kotlin/dev/claudefleet/mobile/ui/pick \
        shared/src/iosMain/kotlin/dev/claudefleet/mobile/ui/pick \
        shared/src/jvmMain/kotlin/dev/claudefleet/mobile/ui/pick \
        shared/src/jvmTest/kotlin/dev/claudefleet/mobile/host/PlatformActualsTest.kt README.md
git commit -m "feat(pick): a system file picker on both platforms

The fourth expect/actual family. Android OpenMultipleDocuments needs no
storage permission and lists Photos among its providers; iOS
UIDocumentPicker with asCopy=true puts the file in our own temp dir, so
no security-scoped access is needed — but it sees Files, which does not
include a screenshot. That gap is recorded in the expect's doc comment."
```

---

### Task 8: The composer

**Repo:** `fleet-mobile`

**Files:**
- Modify: `$MOBILE/shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionScreen.kt` — `PromptBox`
- Modify: `$MOBILE/shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui/SessionViewModel.kt` — `SessionUiState`, and the send path
- Modify: `$MOBILE/shared/src/commonMain/kotlin/dev/claudefleet/mobile/data/SessionActions.kt`
- Test: `$MOBILE/shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/SessionViewModelTest.kt`

**Interfaces:**
- Consumes: Task 5's `PickedFile`/`checkBudget`/`withAttachments`/`fmtBytes`, Task 6's `uploadAttachment`, Task 7's `rememberFilePicker`
- Produces:
  - `SessionUiState.attachments: List<PickedFile>` and `SessionUiState.attachError: String?`
  - `SessionViewModel.attach(files: List<PickedFile>)`, `.removeAttachment(name: String)`
  - `SessionUiState.canAttach: Boolean` — `!readOnly && !sending && session != null`

- [ ] **Step 1: Write the failing test**

In `SessionViewModelTest.kt`, following the fake-actions pattern already there:

```kotlin
@Test
fun a_readonly_device_may_not_attach() = runTest {
    val vm = viewModel(readOnly = true)
    assertFalse(vm.state.value.canAttach)
}

@Test
fun an_oversized_file_is_refused_at_pick_time_and_never_queued() = runTest {
    val vm = viewModel()
    vm.attach(listOf(PickedFile("huge.bin", ATTACH_MAX_BYTES + 1, ByteArray(0))))
    assertTrue(vm.state.value.attachments.isEmpty())
    assertEquals("huge.bin is 10.0 MB — the limit is 10 MB.", vm.state.value.attachError)
}

@Test
fun a_send_uploads_each_attachment_and_names_it_in_the_prompt() = runTest {
    val actions = FakeSessionActions(stagedPath = "/w/.claude-fleet-attachments/a.png")
    val vm = viewModel(actions = actions)
    vm.attach(listOf(PickedFile("a.png", 3, byteArrayOf(1, 2, 3))))
    vm.onDraftChange("look at this")
    vm.send()
    assertEquals(
        "look at this\n\nAttached files:\n/w/.claude-fleet-attachments/a.png",
        actions.lastPrompt,
    )
    // Spent: the chips go once their bytes are on the host.
    assertTrue(vm.state.value.attachments.isEmpty())
}

@Test
fun a_failed_upload_keeps_the_draft_and_the_chips() = runTest {
    val actions = FakeSessionActions(uploadFails = "the hub refused the upload (403)")
    val vm = viewModel(actions = actions)
    vm.attach(listOf(PickedFile("a.png", 3, byteArrayOf(1, 2, 3))))
    vm.onDraftChange("look at this")
    vm.send()
    assertNull(actions.lastPrompt, "nothing should have been sent")
    assertEquals("look at this", vm.state.value.draft)
    assertEquals(1, vm.state.value.attachments.size)
    assertEquals("the hub refused the upload (403)", vm.state.value.attachError)
}
```

Extend the existing fake in that file with `stagedPath`, `uploadFails` and `lastPrompt` rather than adding a second fake.

- [ ] **Step 2: Run it to make sure it fails**

Run: `cd "$MOBILE" && ./gradlew :shared:jvmTest --tests "dev.claudefleet.mobile.ui.SessionViewModelTest" 2>&1 | grep -E "^e:|BUILD"`
Expected: FAIL to compile — `canAttach`, `attach` unresolved.

- [ ] **Step 3: Add the state and the actions**

In `SessionActions.kt`, add to the interface and its real implementation:

```kotlin
    /** Stage one file on the session's host; answers the path it landed at. */
    suspend fun uploadAttachment(sessionId: Long, file: PickedFile): String
```

In `SessionUiState`:

```kotlin
    /** Files picked but not yet sent. Their bytes are still only on this phone. */
    val attachments: List<PickedFile> = emptyList(),
    /** The last refusal to show under the composer; cleared on the next pick. */
    val attachError: String? = null,
```

and, beside `canSend`:

```kotlin
    /** Attaching is a write, so a readonly credential does not get the button. */
    val canAttach: Boolean
        get() = !readOnly && !sending && session != null
```

- [ ] **Step 4: Add the view-model methods**

```kotlin
    /**
     * Queue files for the next send. Nothing leaves the phone here — the
     * upload is part of Send, so a file queued and then removed was never
     * transferred.
     *
     * The budget is checked across what is already queued as well, so three
     * nine-megabyte files are refused on the third rather than after two of
     * them are on the host.
     */
    fun attach(files: List<PickedFile>) {
        val next = state.value.attachments + files
        val refusal = checkBudget(next.map { it.name to it.size })
        if (refusal != null) {
            update { it.copy(attachError = refusal) }
            return
        }
        update { it.copy(attachments = next, attachError = null) }
    }

    fun removeAttachment(name: String) = update {
        it.copy(attachments = it.attachments.filterNot { a -> a.name == name }, attachError = null)
    }
```

In `send()`, before the prompt goes out:

```kotlin
        // Upload first: the prompt names paths, so the paths have to exist.
        // A failure here leaves the draft AND the chips alone — a prompt
        // without its attachment is a worse outcome than a prompt not sent.
        val staged = mutableListOf<String>()
        for (file in state.value.attachments) {
            try {
                staged += actions.uploadAttachment(sessionId, file)
            } catch (e: HubError) {
                update { it.copy(sending = false, attachError = e.message ?: "the upload failed") }
                return
            }
        }
        val body = withAttachments(draft, staged)
```

and, once the send has succeeded, clear them: `update { it.copy(attachments = emptyList(), attachError = null) }`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `cd "$MOBILE" && ./gradlew :shared:jvmTest --tests "dev.claudefleet.mobile.ui.SessionViewModelTest" 2>&1 | grep -E "^e:|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Add the UI**

In `PromptBox`, above the `Row` that holds the field and the send button:

```kotlin
        if (state.attachments.isNotEmpty()) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            ) {
                items(state.attachments, key = { it.name }) { a ->
                    InputChip(
                        selected = false,
                        onClick = { onRemoveAttachment(a.name) },
                        label = { Text("${a.name} · ${fmtBytes(a.size)}", maxLines = 1) },
                        trailingIcon = { Icon(FleetIcons.Close, contentDescription = "Remove ${a.name}") },
                    )
                }
            }
        }
        state.attachError?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
```

and, inside that `Row`, before the `TextField`:

```kotlin
            // `filePickerSupported()` is false on the JVM target, which is
            // what host tests compose against — without it they would render
            // a button that cannot open anything.
            if (state.canAttach && filePickerSupported()) {
                val pick = rememberFilePicker(onPicked = onAttach)
                IconButton(onClick = pick, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(FleetIcons.Add, contentDescription = "Attach a file")
                }
            }
```

`PromptBox` gains `onAttach: (List<PickedFile>) -> Unit` and `onRemoveAttachment: (String) -> Unit`; thread both from `SessionScreen`'s parameters, which thread from `App.kt`'s `SessionRoute` to the view model, exactly as `onDraftChange` already does.

- [ ] **Step 7: Add the two icons**

`FleetIcons` has no `Add` or `Close`. Add both to `ui/theme/FleetIcons.kt` in the file's established style — a stroked `ImageVector.Builder("Add", 24.dp, 24.dp, 24f, 24f)`, `strokeLineWidth = 2f`, round caps:

```kotlin
    /** A plus: the attach affordance. */
    val Add: ImageVector by lazy {
        ImageVector.Builder("Add", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(12f, 5f); lineTo(12f, 19f)
                moveTo(5f, 12f); lineTo(19f, 12f)
            }
        }.build()
    }

    /** An ×: removes one queued attachment. */
    val Close: ImageVector by lazy {
        ImageVector.Builder("Close", 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(6f, 6f); lineTo(18f, 18f)
                moveTo(18f, 6f); lineTo(6f, 18f)
            }
        }.build()
    }
```

Check `NoLetterTabIconsTest` still passes — it asserts icons are drawings, not glyphs.

- [ ] **Step 8: Compile every target and run the suite**

```bash
cd "$MOBILE"
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :shared:jvmTest 2>&1 | grep -E "^e:|FAILED|BUILD"
./gradlew :shared:compileKotlinIosSimulatorArm64 --max-workers=1 2>&1 | grep -E "^e:|BUILD"
./gradlew :androidApp:assembleDebug --max-workers=1 2>&1 | grep -E "^e:|BUILD"
```

Expected: three × `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
cd "$MOBILE"
git add shared/src/commonMain/kotlin/dev/claudefleet/mobile/ui \
        shared/src/commonMain/kotlin/dev/claudefleet/mobile/data/SessionActions.kt \
        shared/src/commonTest/kotlin/dev/claudefleet/mobile/ui/SessionViewModelTest.kt
git commit -m "feat(session): attach a file to a prompt from the phone

A + beside the composer, hidden on a readonly credential the way Send
already is, and a chip per queued file. Files upload on Send, not on
pick: one queued and then removed never leaves the phone. A failed
upload keeps the draft and the chips — a prompt without its attachment
is worse than a prompt not sent."
```

---

### Task 9: The device test

**Repo:** `fleet-mobile`

Everything above is checkable off a device. The composer's layout is not: `SessionHeaderLayoutTest` exists because a header that measured fine in theory broke on a 320 dp phone.

**Files:**
- Create: `$MOBILE/androidApp/src/androidTest/kotlin/dev/claudefleet/mobile/android/ComposerAttachTest.kt`

**Interfaces:**
- Consumes: Task 8's composer
- Produces: nothing

- [ ] **Step 1: Write the test**

```kotlin
package dev.claudefleet.mobile.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.claudefleet.mobile.model.PickedFile
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The composer with files queued, on a narrow phone.
 *
 * A chip strip above a field that already grows to six lines is the kind of
 * thing that fits until someone attaches a file with a long name, so this
 * checks bounds rather than only that the nodes exist.
 */
@RunWith(AndroidJUnit4::class)
class ComposerAttachTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun a_queued_file_shows_a_chip_that_fits_and_the_button_is_reachable() {
        compose.setContent {
            Box(Modifier.width(WIDTH)) {
                sessionScreenWith(
                    attachments = listOf(
                        PickedFile("a-rather-long-screenshot-name.png", 2048, ByteArray(0)),
                    ),
                )
            }
        }
        compose.onNodeWithContentDescription("Attach a file").assertIsDisplayed()
        val chip = compose.onNodeWithText("a-rather-long-screenshot-name.png", substring = true)
        chip.assertIsDisplayed()
        assertTrue("the chip is cut off", chip.getBoundsInRoot().right <= WIDTH)
        compose.onNodeWithContentDescription(
            "Remove a-rather-long-screenshot-name.png",
        ).assertIsDisplayed()
    }

    @Test
    fun a_readonly_device_gets_no_attach_button() {
        compose.setContent { Box(Modifier.width(WIDTH)) { sessionScreenWith(readOnly = true) } }
        compose.onNodeWithContentDescription("Attach a file").assertDoesNotExist()
    }

    private companion object {
        val WIDTH = 320.dp
    }
}
```

Write `sessionScreenWith(...)` as a private `@Composable` in this file, modelled on `SessionHeaderLayoutTest`'s `compose.setContent` block — the same twenty-odd `SessionScreen` parameters with `{}` for every callback. Do not extract a shared helper; the two tests want different states and a shared one would grow parameters for both.

- [ ] **Step 2: Compile the instrumentation sources**

Run: `cd "$MOBILE" && ANDROID_HOME="$HOME/Android/Sdk" ./gradlew :androidApp:compileDebugAndroidTestKotlin --max-workers=1 2>&1 | grep -E "^e:|BUILD"`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run it on a device or emulator if one is available**

Run: `cd "$MOBILE" && ANDROID_HOME="$HOME/Android/Sdk" ./gradlew :androidApp:connectedAndroidTest --tests "*ComposerAttachTest" 2>&1 | tail -20`
Expected: PASS. With no device attached this fails with "No connected devices" — that is not a code failure. Say so plainly and let CI run it; do not claim the test passed.

- [ ] **Step 4: Commit**

```bash
cd "$MOBILE"
git add androidApp/src/androidTest/kotlin/dev/claudefleet/mobile/android/ComposerAttachTest.kt
git commit -m "test(composer): the attach chip fits a 320 dp phone"
```

---

## After the plan

Both repos need a PR, and `claude-fleet`'s must merge first — the phone's uploads 404 against a hub without the route. Neither is opened by this plan.

The phone shows an old hub's 404 as a plain transport failure. If that reads badly in practice, the fix is a capability probe in `HubCapabilities`, which is where the app already keeps "what this hub can do". Out of scope here; it needs a hub in front of it to judge.
