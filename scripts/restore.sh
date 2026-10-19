#!/usr/bin/env bash
# Undo everything recorded in changes.log on the Mi TV, newest change first.
#
#   restore.sh [IP] [--dry-run] [--remove-launcher] [--from-backup]
#
#   IP                 TV address (default 192.168.1.3)
#   --dry-run          print the adb commands without running them
#   --remove-launcher  also uninstall the Calm launcher
#   --from-backup      ignore changes.log; re-enable every package that is disabled now
#                      but was enabled in the backup snapshot (belt and braces)
#
# Nothing here uninstalls system apps or deletes data; it only re-enables and resets.
set -uo pipefail

DIR="$(cd "$(dirname "$0")/.." && pwd)"
IP=192.168.1.3 DRY=0 RMLAUNCHER=0 FROMBACKUP=0
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY=1 ;;
    --remove-launcher) RMLAUNCHER=1 ;;
    --from-backup) FROMBACKUP=1 ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) IP="$arg" ;;
  esac
done
TV="$IP:5555"
LOG="${CHANGES_LOG:-$DIR/changes.log}"   # override only for testing a subset
LAUNCHER=com.calm.launcher

run() {
  if [ "$DRY" = 1 ]; then echo "[dry-run] adb -s $TV $*"; else adb -s "$TV" "$@" </dev/null; fi
}

if [ "$DRY" = 0 ]; then
  adb connect "$TV" >/dev/null
  state=$(adb -s "$TV" get-state 2>&1)
  if [ "$state" != device ]; then
    echo "TV not reachable/authorised at $TV ($state). Accept the USB-debugging prompt on the TV and retry." >&2
    exit 1
  fi
fi

if [ "$FROMBACKUP" = 1 ]; then
  B="$DIR/backup/$(cat "$DIR/backup/LATEST")"
  echo "Re-enabling packages disabled since backup $B"
  now=$(adb -s "$TV" shell pm list packages -d </dev/null | tr -d '\r' | sed 's/package://' | sort)
  comm -23 <(echo "$now") "$B/disabled-at-backup.txt" | while read -r p; do
    [ -n "$p" ] && run shell pm enable "$p"
  done
else
  [ -s "$LOG" ] || { echo "changes.log is empty, nothing to undo."; }
  # Newest first. Fields are tab separated: time, action, then action-specific.
  tail -r "$LOG" 2>/dev/null | while IFS=$'\t' read -r _ action f1 f2 f3 _f4; do
    case "$action" in
      disable)  run shell pm enable "$f1" ;;                       # f1=pkg
      enable)   : ;;                                               # a manual re-enable needs no undo
      setting)  # f1=ns f2=key f3=old value
        if [ "$f3" = null ] || [ -z "$f3" ]; then run shell settings delete "$f1" "$f2"
        else run shell settings put "$f1" "$f2" "$f3"; fi ;;
      home)     # f1=new home f2=previous home
        if [ -n "$f2" ] && [ "$f2" != "-" ]; then
          run shell cmd role add-role-holder android.app.role.HOME "${f2%%/*}" 0
          run shell cmd package set-home-activity "$f2"
        fi ;;
      *) echo "skip unknown log line: $action" ;;
    esac
  done
fi

if [ "$RMLAUNCHER" = 1 ]; then
  run shell pm enable com.google.android.tvlauncher
  run shell cmd role add-role-holder android.app.role.HOME com.google.android.tvlauncher 0
  run shell cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
  run uninstall "$LAUNCHER"
fi

run shell input keyevent KEYCODE_HOME
echo "Restore finished."
