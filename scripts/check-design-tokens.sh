#!/usr/bin/env bash
#
# Is docs/design/tokens.json still byte-identical to claude-fleet's?
#
# The phone's copy of the Orbit Fleet tokens is claude-fleet's
# `docs/design/tokens.json`, unchanged (docs/design/README.md). Inside this
# repo `OrbitTokensDriftTest` holds `OrbitTokens.kt` to the copy, but nothing
# held the copy to its source: claude-fleet's review r10 added fourteen colours
# and the `size` group, and the phone's copy sat behind for days with every
# test green. This is the cross-repo half.
#
# Usage: scripts/check-design-tokens.sh [--ref <claude-fleet ref>] [--diff]
#   --ref <ref>  compare against that ref of claude-fleet (default: main)
#   --diff       print a unified diff of the two files when they differ
# Env:   CLAUDE_FLEET_REPO  owner/name (default martin-janci/claude-fleet)
#        GH_TOKEN           optional; with `gh` on PATH the file is read through
#                           the REST API (higher rate limit). Without it, the
#                           public raw URL. Both repos are public, so no secret
#                           is needed and none is asked for.
#
# Exit 0 when identical, 1 when they differ (the diff on stderr with --diff),
# 2 when claude-fleet's file could not be read: an unreadable source is not
# "no drift", and the caller decides whether that fails.
set -euo pipefail

REPO="${CLAUDE_FLEET_REPO:-martin-janci/claude-fleet}"
FILE=docs/design/tokens.json
ref=main diff=""
while [ $# -gt 0 ]; do
  case "$1" in
    --ref) ref="${2:?--ref needs a ref}"; shift 2 ;;
    --diff) diff=1; shift ;;
    -h|--help) sed -n '2,25p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "usage: scripts/check-design-tokens.sh [--ref <ref>] [--diff]" >&2; exit 2 ;;
  esac
done

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

fetched=""
if command -v gh >/dev/null 2>&1 && [ -n "${GH_TOKEN:-}" ]; then
  gh api -H "Accept: application/vnd.github.raw" "repos/$REPO/contents/$FILE?ref=$ref" >"$tmp" 2>/dev/null && fetched=1
fi
if [ -z "$fetched" ]; then
  curl -fsSL "https://raw.githubusercontent.com/$REPO/$ref/$FILE" -o "$tmp" 2>/dev/null && fetched=1
fi
if [ -z "$fetched" ] || [ ! -s "$tmp" ]; then
  echo "check-design-tokens.sh: could not read $FILE from $REPO@$ref" >&2
  exit 2
fi

if cmp -s "$tmp" "$root/$FILE"; then
  echo "$FILE matches $REPO@$ref"
  exit 0
fi
echo "check-design-tokens.sh: $FILE differs from $REPO@$ref — copy it here unchanged, then update OrbitTokens.kt until OrbitTokensDriftTest passes (docs/design/README.md)" >&2
if [ -n "$diff" ]; then
  diff -u "$root/$FILE" "$tmp" --label "fleet-mobile/$FILE" --label "$REPO@$ref/$FILE" >&2 || true
fi
exit 1
