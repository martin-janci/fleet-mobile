# Design tokens

`tokens.json` is a copy of the Orbit Fleet design manual's tokens: colour in
both themes, type, spacing (with the phone's touch-min, phone-gutter,
phone-bar-h, tab-bar-h and phone-row-min), radius, shadow and durations. It is
the same file as claude-fleet's `docs/design/tokens.json` (redesign step 0.5);
the manual itself is the source of truth.

The phone holds the values as literals in
`shared/src/commonMain/.../ui/theme/OrbitTokens.kt`, because commonMain also
runs on Kotlin/Native where nothing reads this file. `FleetTheme.kt` builds
Material's colour scheme, shapes and the status colours from them.

Two tests in `shared/src/jvmTest/.../ui/theme/` keep it honest:

- `OrbitTokensDriftTest` fails when `OrbitTokens.kt` and this file disagree, in
  either direction.
- `OrbitThemeContrastTest` holds the pairs the phone makes from the tokens
  (status chips, metadata on each surface container) at 4.5:1 in both themes.

A third check holds the copy to its source, across the two repositories:
`scripts/check-design-tokens.sh` fetches claude-fleet main's
`docs/design/tokens.json` and compares it byte for byte (`--diff` prints the
difference). `.github/workflows/design-tokens.yml` runs it daily, on pushes to
main and on every PR: drift fails the scheduled run and any PR that touches the
copy or `OrbitTokens.kt`, and only warns on other PRs, since the drift almost
always starts in claude-fleet. Both repositories are public, so it needs no
secret.

To change a token: change the manual, copy its `tokens.json` here unchanged,
then update the literal in `OrbitTokens.kt`.
