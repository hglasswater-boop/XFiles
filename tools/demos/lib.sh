# Shared helpers for scripted XFiles UI demos, driven over adb.
# Source this from a driver script; it expects $SERIAL (arg $1 or env).
#
# Rows are located by their on-screen text or accessibility labels via
# `uiautomator dump`, so scripted captures do not depend on fixed coordinates.

SERIAL="${SERIAL:-${1:-}}"
[[ -n "$SERIAL" ]] || { echo "lib.sh: no device serial" >&2; return 1 2>/dev/null || exit 1; }
adb() { command adb -s "$SERIAL" "$@"; }

_UI="$(mktemp)"
trap 'rm -f "$_UI"' EXIT

# Dump the current UI hierarchy. uiautomator can occasionally return an empty
# dump while Compose is settling, so retry a few times before failing.
dump_ui() {
  local i
  for i in 1 2 3 4; do
    adb shell uiautomator dump /sdcard/xf-ui.xml >/dev/null 2>&1 || true
    adb pull /sdcard/xf-ui.xml "$_UI" >/dev/null 2>&1 || true
    [[ -s "$_UI" ]] && grep -q '<hierarchy' "$_UI" && return 0
    sleep 0.4
  done
  return 1
}

# Echo "cx cy" for the first node whose visible text exactly equals $1.
bounds_center() {
  local text="$1"
  local re='text="'"$text"'"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"'
  local hit
  hit="$(grep -oE "$re" "$_UI" 2>/dev/null | head -1 | grep -oE 'bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"')"
  [[ -n "$hit" ]] || return 1
  echo "$hit" | sed -E 's/bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]"/\1 \2 \3 \4/' \
    | awk '{printf "%d %d", int(($1+$3)/2), int(($2+$4)/2)}'
}

tap_text() {
  local text="$1" pause="${2:-1.4}"
  dump_ui
  local xy; xy="$(bounds_center "$text")" || { echo "tap_text: '$text' not found" >&2; return 1; }
  echo "  tap  '$text'  @ $xy"
  adb shell input tap $xy
  sleep "$pause"
}

tap_xy() {
  echo "  tap  @ $1 $2"
  adb shell input tap "$1" "$2"
  sleep "${3:-1.4}"
}

# Tap an element by exact accessibility label.
tap_desc() {
  local desc="$1" pause="${2:-1.4}"
  dump_ui
  local xy; xy="$(python3 - "$_UI" "$desc" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
m = re.search(r'content-desc="' + re.escape(sys.argv[2]) + r'"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
PY
)"
  [[ -n "$xy" ]] || { echo "tap_desc: '$desc' not found" >&2; return 1; }
  echo "  tap  [$desc] @ $xy"
  adb shell input tap $xy
  sleep "$pause"
}

# Tap an element whose accessibility label starts with a dynamic prefix.
tap_desc_prefix() {
  local prefix="$1" pause="${2:-1.4}"
  dump_ui
  local xy; xy="$(python3 - "$_UI" "$prefix" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if not node.attrib.get("content-desc", "").startswith(sys.argv[2]):
        continue
    bounds = node.attrib.get("bounds", "")
    try:
        x1, y1, x2, y2 = map(int, bounds.replace("][", ",").strip("[]").split(","))
    except (TypeError, ValueError):
        continue
    print((x1 + x2) // 2, (y1 + y2) // 2)
    break
PY
)"
  [[ -n "$xy" ]] || { echo "tap_desc_prefix: '$prefix' not found" >&2; return 1; }
  echo "  tap  [$prefix…] @ $xy"
  adb shell input tap $xy
  sleep "$pause"
}

# Tick the Select action nearest the row whose label equals $1.
select_row() {
  local text="$1" pause="${2:-0.7}"
  dump_ui
  local xy; xy="$(python3 - "$_UI" "$text" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8", errors="replace").read()
labels = [m for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
          if m.group(1) == sys.argv[2]]
if labels:
    ly = (int(labels[0].group(3)) + int(labels[0].group(5))) // 2
    best = None
    for m in re.finditer(r'content-desc="Select"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        x1, y1, x2, y2 = map(int, m.groups())
        cy = (y1 + y2) // 2
        if best is None or abs(cy - ly) < abs(best[1] - ly):
            best = ((x1 + x2) // 2, cy)
    if best and abs(best[1] - ly) < 60:
        print(best[0], best[1])
PY
)"
  [[ -n "$xy" ]] || { echo "select_row: '$text' not found" >&2; return 1; }
  echo "  select '$text' @ $xy"
  adb shell input tap $xy
  sleep "$pause"
}

hold() { sleep "${1:-1.2}"; }

swipe_up() {
  local h w
  read -r w h < <(adb shell wm size | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/')
  adb shell input swipe $((w/2)) $((h*62/100)) $((w/2)) $((h*22/100)) 450
  sleep "${1:-1.0}"
}

_TS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/tree_state.py"

# True when the first storage root is high enough on screen to indicate that the
# pane is at the top of its list.
top_reached() {
  dump_ui || return 1
  local xy; xy="$(python3 "$_TS" "$_UI" center "Internal shared storage")"
  [[ -n "$xy" ]] || return 1
  (( ${xy#* } < 700 ))
}

scroll_top() {
  local h w i
  read -r w h < <(adb shell wm size | sed -E 's/.*: ([0-9]+)x([0-9]+).*/\1 \2/')
  for i in $(seq 1 14); do
    top_reached && break
    adb shell input swipe $((w/2)) $((h*20/100)) $((w/2)) $((h*90/100)) 160
  done
  sleep 0.2
}

# Collapse currently visible/remembered tree expansions from deepest to shallowest
# and return to the top. This is sufficient for the current scripted copy demo.
reset_shallow() {
  scroll_top
  local i xy
  for i in $(seq 1 12); do
    dump_ui
    xy="$(python3 "$_TS" "$_UI" collapse-deepest)"
    [[ -n "$xy" ]] || break
    echo "  reset: collapse @ $xy"
    adb shell input tap $xy
    sleep 0.4
    scroll_top
  done
}
