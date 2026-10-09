# Mobile update adapter — design

**Date:** 2026-09-28
**Status:** built (2026-10-09), with the deviations below.

### What was built, and where it differs

- `X-Fleet-Client` is set once (`net/ClientHeader.kt`, `FleetClient.header`,
  from `AppContainer`'s `clientPlatform`) and added by `HubClient.send`,
  `HubClient.downloadFile` and `HubEventStream` — the hub's requests only,
  never GitHub's. Not in an `HttpClient` factory: the same client fetches
  GitHub releases.
- `HubClient.updateCheck` posts `/update/check`; `update/HubReleases.kt`
  turns the decision into the existing update card (`ReleaseInfo` gains
  `signerSha256`, `required`, `reason`). The download, the sha256 check and
  the installer are the ones that already existed (redesign 14.18).
- **GitHub stays as a fallback** for a hub too old to have `/update/check`
  (404) and for a build that cannot describe itself (a local `dev` build).
  Any other failure shows no card. This keeps phones paired to an older hub
  updating as they do today; F2 holds for every hub that can decide.
- The phone does **not** verify the minisign signature itself: it checks
  the APK's sha256 against the decision and its signing certificate against
  both the manifest's `signer_sha256` and the installed app's. The hub
  verified the signed channel and amendment the decision rests on.
- `update:decision` (kind `update`, a signal) re-checks two seconds later.
  There is no `next_check_secs` timer and no `/update/report`: the check
  itself and the header record what the phone runs.
- `update_required` shows on the card ("Update required: …" and the hub's
  reason), not as a full screen; `client_too_new` keeps today's hub-older
  sentence.
- `release.yml` publishes `fleet-mobile-<v>.apk`, `androidApp-release.apk`
  (for phones reading GitHub) and `SHA256SUMS`, builds with `-PgitSha`, and
  sends claude-fleet `repository_dispatch` `android-release` with
  `FLEET_DISPATCH_TOKEN` (skipped with a notice when the secret is unset).
**Scope:** the phone's part of the fleet-wide update design. The design
itself lives in claude-fleet,
`docs/superpowers/specs/2026-09-28-update-channel-design.md`. Read that
first: the protocol, the `Decision` statuses, the manifest and the security
rules are defined there, not here. This file covers only what the phone
adds on top. It is slice **S8** of that design.

## Fixed by the fleet design

- The phone is always paired, so it only ever uses the **hub channel**.
  There is no Git mode on mobile, and the phone never reads GitHub
  releases to decide anything (F2).
- The hub decides. The phone displays the decision and runs the platform
  installer. The one exception is the signed floor (U6): the phone refuses
  a target the publisher withdrew.
- A phone never downgrades itself. `rollback` and `client_too_new` are
  shown to the user and nothing more.

## 1. Tell the hub what is installed

- Every Ktor request carries `X-Fleet-Client: android/<versionName>
  (android-<abi>; build <sha7>; contract 0-5)`, or `ios/<CFBundleShortVersionString>
  (ios-arm64; …)`. It is added once, in the `HttpClient` factory in
  `net/HubClient.kt`, and the window comes from `HubContract.kt`
  (`MIN_HUB_CONTRACT`, `MAX_HUB_CONTRACT`), so it cannot drift.
- `build <sha7>`: `androidApp/build.gradle.kts` gets a `GIT_SHA`
  `buildConfigField` from `-PgitSha=` (set by `release.yml`), defaulting to
  `"unknown"`. On iOS it comes from an Info.plist key.

## 2. Ask, and keep asking when refused

- `net/UpdateClient.kt` calls `POST /update/check` (`update_proto: 1`):
  - on connect;
  - on the `/events` kind `update:decision`;
  - every `next_check_secs`;
  - and **also while `ConnectionStatus.Refused`** for a contract skew. The
    update wire is exempt from the contract gate (fleet design §6.5), and
    the case where the phone is refused is exactly the case where it must
    still learn what to install.
- `model/UpdateDecision.kt` mirrors the `status` enum: `up_to_date`,
  `update_available`, `update_required`, `client_too_new`, `rollback`,
  `hold`, `unknown`. An unknown value reads as `unknown`, never as a crash.
- A jvmTest reads claude-fleet's
  `crates/fleet-update/tests/decide_cases.json` when that checkout sits
  beside this one, in the way `HubContractDriftTest` does. It checks that
  every status in the fixture has a sentence here.

## 3. What the user sees

- **Settings → Updates:** the installed version (it is already shown), the
  hub's version (not shown today, though `_hubVersion` is recorded), the
  decision's sentence, and **Update** when there is an artifact.
- **`update_required`:** the `Refused` banner (`ui/components/Banners.kt`)
  becomes a full screen, "Update required to connect to this Hub", with the
  target version and **Update**. When the contract skew is the reason, it
  keeps today's `HubContract.sentence()` text under it.
- **`update_available` with `mandatory`:** a dismissible banner that shows
  the deadline.
- **`client_too_new`:** "This hub is older than this app. Ask the operator
  to update the hub." Nothing is offered to install.

## 4. Android installer (the platform layer)

The phases are from the fleet design's state machine (§7.2).

| phase | Android |
|---|---|
| downloading | a Ktor download of `artifact.url` into `cacheDir/updates/<sha256>.apk`, resumable; the Wi-Fi-only preference is honoured |
| verifying | sha256 equals the decision's `artifact.sha256`, which equals the value in the signed release manifest (the phone verifies the minisign signature with the public key in `BuildConfig`). The APK's signer certificate sha256 equals the manifest's `signer_sha256` **and** the installed app's (`PackageManager`), checked before handing the APK to the installer. |
| installing | a `PackageInstaller` session. The system always asks the user to confirm a non-store install; the app never tries to avoid that. |
| validating | the next launch finds the persisted `attempt` and reports `success` with its new `versionName`, or `failed` if it is still the old one after 24 h |
| rolling_back | none |

- **The manifest** adds `REQUEST_INSTALL_PACKAGES`. The first **Update**
  sends the user to "Install unknown apps" for this app. A denial becomes
  the decision's plain "Open download page" fallback.
- **`versionCode`** is the release workflow's `run_number`: monotonic, as
  Android requires. The manifest records it, so a stale APK is refused
  before download.

## 5. iOS

There is no side-load, so the adapter is `notify` only. **Update** opens the
artifact's link (TestFlight or the App Store, once one exists; the fleet
design's non-goal). Until then iOS shows the decision, and **Update** is
hidden.

## 6. Release job changes (`.github/workflows/release.yml`)

- Publish a versioned `fleet-mobile-<v>.apk`, not
  `androidApp-release.apk`, plus a `SHA256SUMS`.
- Build with `-PgitSha=${{ github.sha }}`.
- After publishing, send claude-fleet a `repository_dispatch` carrying `{version,
  sha256, size, version_code, signer_sha256}` so its workflow signs the
  manifest's Android amendment. That choice is claude-fleet's Open
  question 2; if the owner picks the other option, this step goes away.

## 7. Done when

- A phone paired to a hub whose channel has a newer APK shows **Update**,
  installs it after the system prompt, and reports `success` on the next
  launch.
- A phone refused for a contract skew still shows "Update required", with
  a working **Update**.
- A tampered APK (sha256 mismatch) or one with a foreign signer never
  reaches `PackageInstaller`.
