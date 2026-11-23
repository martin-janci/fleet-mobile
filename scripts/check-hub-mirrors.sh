#!/usr/bin/env bash
# Warn when this app's copies of claude-fleet's rules have drifted from the
# hub's (review w19-7). Each pair below is a list or file the phone keeps by
# hand; nothing else notices when the hub's side changes, because both repos'
# tests stay green on their own copy.
#
#   scripts/check-hub-mirrors.sh <claude-fleet checkout>
#
# Warn-only: it prints every drift it finds and exits 0, so CI can run it
# beside the build without failing on a hub change the phone has not taken
# yet. Pass --strict to exit 1 on a drift. Needs python3.
set -euo pipefail

strict=0
if [[ "${1:-}" == "--strict" ]]; then strict=1; shift; fi
fleet="${1:-${CLAUDE_FLEET_DIR:-}}"
if [[ -z "$fleet" || ! -d "$fleet" ]]; then
  echo "usage: $0 [--strict] <claude-fleet checkout>   (or set CLAUDE_FLEET_DIR)" >&2
  exit 2
fi
here="$(cd "$(dirname "$0")/.." && pwd)"

python3 -I - "$here" "$fleet" "$strict" <<'PY'
import json, re, sys
from pathlib import Path

app, hub, strict = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3] == "1"
main = app / "shared/src/commonMain/kotlin/dev/claudefleet/mobile/model"
test = app / "shared/src/commonTest"
drift = []

def read(p):
    try:
        return p.read_text(encoding="utf-8")
    except OSError as e:
        drift.append(f"cannot read {p}: {e}")
        return ""

def block(text, start, end):
    i = text.find(start)
    if i < 0:
        return None
    j = text.find(end, i + len(start))
    return text[i + len(start): j if j >= 0 else None]

def strings(text):
    return re.findall(r'"((?:[^"\\]|\\.)*)"|\'((?:[^\'\\]|\\.)*)\'', text or "")

def words(text):
    return [a or b for a, b in strings(text)]

def compare(name, phone, fleet):
    if phone is None or fleet is None:
        drift.append(f"{name}: not found on {'the phone' if phone is None else 'the hub'} side")
    elif phone != fleet:
        drift.append(f"{name}: the phone has {phone!r}, the hub {fleet!r}")

# RISKY_WORDS: src/lib/quick_answer.ts (the hub's quick_answer.rs reads it).
kt = read(main / "RichBlocks.kt")
ts = read(hub / "src/lib/quick_answer.ts")
compare("RISKY_WORDS",
        words(block(kt, "internal val RISKY_WORDS = listOf(", ")")) if "RISKY_WORDS = listOf(" in kt else None,
        words(block(ts, "export const RISKY_WORDS = [", "]")) if "RISKY_WORDS = [" in ts else None)

# FLEET_PROMPT_MARKS: service/prompt_origin.rs.
kt = read(main / "FleetEvents.kt")
rs = read(hub / "crates/fleet-core/src/service/prompt_origin.rs")
m = re.search(r"pub const FLEET_PROMPT_MARKS: &\[&str\] = &\[(.*?)\];", rs, re.S)
compare("FLEET_PROMPT_MARKS",
        words(block(kt, "val FLEET_PROMPT_MARKS: List<String> = listOf(", ")")) if "FLEET_PROMPT_MARKS" in kt else None,
        words(m.group(1)) if m else None)

# RESUMABLE_LOST_REASONS: store/rows.rs.
kt = read(main / "SessionRow.kt")
rs = read(hub / "crates/fleet-core/src/store/rows.rs")
m = re.search(r"pub const RESUMABLE_LOST_REASONS: \[&str; \d+\] = \[(.*?)\];", rs, re.S)
k = re.search(r"val RESUMABLE_LOST_REASONS: Set<String> = setOf\((.*?)\)", kt, re.S)
compare("RESUMABLE_LOST_REASONS",
        sorted(words(k.group(1))) if k else None,
        sorted(words(m.group(1))) if m else None)

# LATIN, the accent fold: search_text.rs.
kt = read(main / "TextFold.kt")
rs = read(hub / "crates/fleet-core/src/search_text.rs")
k = re.search(r'private const val LATIN =((?:\s*"[^"]*"\s*\+?)+)', kt)
m = re.search(r'const LATIN: &\[u8; \d+\] = b"(.*?)";', rs, re.S)
compare("LATIN",
        "".join(words(k.group(1))) if k else None,
        m.group(1).replace("\\\n", "") if m else None)

# The chat-block cases: docs/chat-block-examples/blocks.json.
kt = read(test / "kotlin/dev/claudefleet/mobile/model/ChatBlockExamples.kt")
k = re.search(r'CHAT_BLOCK_EXAMPLES: String = """(.*?)"""', kt, re.S)
try:
    phone = json.loads(k.group(1).replace("${'$'}", "$")) if k else None
    fleet = json.loads(read(hub / "docs/chat-block-examples/blocks.json") or "null")
    compare("chat-block examples (case names)",
            [c.get("name") for c in phone["cases"]] if phone else None,
            [c.get("name") for c in fleet["cases"]] if fleet else None)
    if phone and fleet and phone != fleet and [c.get("name") for c in phone["cases"]] == [c.get("name") for c in fleet["cases"]]:
        drift.append("chat-block examples: same cases, different contents")
except (ValueError, KeyError, TypeError) as e:
    drift.append(f"chat-block examples: cannot parse ({e})")

# The form answer cases: docs/form-examples/answers.json.
kt = read(test / "kotlin/dev/claudefleet/mobile/model/FormAnswerExamples.kt")
k = re.search(r'FORM_ANSWER_EXAMPLES: String = """(.*?)"""', kt, re.S)
try:
    phone = json.loads(k.group(1)) if k else None
    fleet = json.loads(read(hub / "docs/form-examples/answers.json") or "null")
    if phone is None or fleet is None:
        drift.append("form answer cases: not found on " + ("the phone" if phone is None else "the hub") + " side")
    elif phone != fleet:
        drift.append("form answer cases: the phone's copy differs from docs/form-examples/answers.json")
except ValueError as e:
    drift.append(f"form answer cases: cannot parse ({e})")

# The palette golden file, copied unchanged.
compare("docs/design/palette-golden.json",
        read(app / "docs/design/palette-golden.json") or None,
        read(hub / "docs/design/palette-golden.json") or None)

# The pages registry fixture: page ids and descriptor keys.
def registry(p):
    try:
        r = json.loads(read(p) or "null")
    except ValueError as e:
        drift.append(f"{p}: cannot parse ({e})")
        return None
    if not isinstance(r, dict):
        return None
    pages = sorted(pg.get("id") for pg in r.get("pages", []) if isinstance(pg, dict))
    descs = r.get("descriptors", r.get("settings", []))
    keys = sorted(d.get("key") for d in descs if isinstance(d, dict)) if isinstance(descs, list) else sorted(descs)
    return pages, keys
phone = registry(test / "fixtures/pages-registry.json")
fleet = registry(hub / "src/lib/pages/registry.generated.json")
if phone and fleet:
    for i, what in enumerate(["page ids", "descriptor keys"]):
        if phone[i] != fleet[i]:
            gone = sorted(set(fleet[i]) - set(phone[i]))
            extra = sorted(set(phone[i]) - set(fleet[i]))
            drift.append(f"pages registry {what}: missing on the phone {gone}, only on the phone {extra}")

if drift:
    print("The phone's copies of hub rules have drifted (copy them again):")
    for d in drift:
        print("  - " + d)
    sys.exit(1 if strict else 0)
print("Every hub mirror matches.")
PY
