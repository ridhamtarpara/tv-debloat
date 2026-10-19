#!/usr/bin/env bash
# Helpers for the Mi TV debloat. Every state change goes through here so it lands in changes.log.
#   tv.sh shot <name>                 screenshot -> screens/NN-name.png
#   tv.sh disable <pkg>...            pm disable-user (denylist-checked, logged)
#   tv.sh enable <pkg>...             pm enable (logged)
#   tv.sh setting <ns> <key> <val>    settings put, logging the old value
#   tv.sh health                      HOME keypress + crash buffer check
set -euo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
TV="${TV:-192.168.1.3:5555}"
LOG="$DIR/changes.log"
A=(adb -s "$TV")

# Packages that must never be disabled: core UI, input, Play, TV inputs, and Calm itself.
# (The Google TV launcher was allowed after the user chose to make Calm the Home screen.)
DENY='^(android|com\.android\.(systemui|settings|tv\.settings|packageinstaller|providers\..*|phone|bluetooth|inputdevices|shell|externalstorage|permissioncontroller|keychain|location\.fused|se|server\.telecom|networkstack.*|wifi.*|tethering|vending|webview)|com\.google\.android\.(gms|gsf|leanbacklauncher|tv\.remote\.service|katniss|permissioncontroller|ext\.services|webview|packageinstaller|inputmethod\..*|leanback\.ime)|com\.google\.android\.apps\.tv\.launcherx|com\.droidlogic\..*|com\.mediatek\..*|com\.amlogic\..*|com\.xiaomi\.mitv\.(tvinput|settings|system.*)|com\.calm\.launcher)$'

ts() { date '+%Y-%m-%dT%H:%M:%S'; }

pkg_state() {  # enabled | disabled-user | disabled | missing
  local line
  line=$("${A[@]}" shell pm list packages -d 2>/dev/null | tr -d '\r' | grep -x "package:$1" || true)
  if [ -n "$line" ]; then
    "${A[@]}" shell dumpsys package "$1" | tr -d '\r' | grep -q 'enabled=3' && echo disabled-user || echo disabled
  elif "${A[@]}" shell pm list packages 2>/dev/null | tr -d '\r' | grep -qx "package:$1"; then
    echo enabled
  else
    echo missing
  fi
}

cmd="${1:-}"; shift || true
case "$cmd" in
  shot)
    mkdir -p "$DIR/screens"
    n=$(ls "$DIR/screens" | wc -l | tr -d ' ')
    f="$DIR/screens/$(printf '%02d' "$n")-${1:-shot}.png"
    "${A[@]}" exec-out screencap -p > "$f"
    echo "$f"
    ;;
  disable)
    for p in "$@"; do
      if [[ "$p" =~ $DENY ]]; then echo "REFUSED (denylist): $p" >&2; continue; fi
      prior=$(pkg_state "$p")
      [ "$prior" = missing ] && { echo "skip (not installed): $p"; continue; }
      [ "$prior" != enabled ] && { echo "already $prior: $p"; continue; }
      out=$("${A[@]}" shell pm disable-user --user 0 "$p" 2>&1 | tr -d '\r')
      echo "$out"
      echo "$out" | grep -q 'new state: disabled' && printf '%s\tdisable\t%s\t%s\n' "$(ts)" "$p" "$prior" >> "$LOG"
    done
    ;;
  enable)
    for p in "$@"; do
      out=$("${A[@]}" shell pm enable "$p" 2>&1 | tr -d '\r'); echo "$out"
      printf '%s\tenable\t%s\t-\n' "$(ts)" "$p" >> "$LOG"
    done
    ;;
  setting)
    ns="$1" key="$2" val="$3"
    old=$("${A[@]}" shell settings get "$ns" "$key" | tr -d '\r')
    "${A[@]}" shell settings put "$ns" "$key" "$val"
    printf '%s\tsetting\t%s\t%s\t%s\t%s\n' "$(ts)" "$ns" "$key" "$old" "$val" >> "$LOG"
    echo "$ns/$key: $old -> $val"
    ;;
  home)  # log the previous HOME before changing it: tv.sh home <component>
    prev=$("${A[@]}" shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tr -d '\r' | tail -1)
    "${A[@]}" shell cmd package set-home-activity "$1"
    "${A[@]}" shell cmd role add-role-holder android.app.role.HOME "${1%%/*}" 0
    printf '%s\thome\t%s\t%s\n' "$(ts)" "$1" "$prev" >> "$LOG"
    echo "home: $prev -> $1"
    ;;
  health)
    "${A[@]}" shell input keyevent KEYCODE_HOME; sleep 3
    echo "home now: $("${A[@]}" shell dumpsys activity activities | tr -d '\r' | grep -m1 -E 'mResumedActivity|ResumedActivity' | sed 's/^ *//')"
    c=$("${A[@]}" shell logcat -b crash -d -t 200 2>/dev/null | tr -d '\r' | grep -E 'FATAL EXCEPTION|Process:' | tail -6 || true)
    [ -n "$c" ] && echo "CRASHES:"$'\n'"$c" || echo "crash buffer: clean"
    ;;
  *) sed -n '2,8p' "$0"; exit 1 ;;
esac
