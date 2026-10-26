#!/system/bin/sh
MODDIR=${0%/*}
CONFIG=/data/adb/tubing-automation
PATH=/system/bin:/system/xbin:$PATH
export PATH
umask 077
. "$MODDIR/detection.sh"
while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 5; done

# Android mksh marks descriptors >= 3 close-on-exec, so an external flock cannot
# use fd 9 opened with exec. Standard input is inherited and holds the lock for
# this shell's lifetime. The monitor never reads from its original stdin.
exec 0>"$MODDIR/monitor.lock"
flock -n 0 || exit 0
status=
report_status() {
  [ "$status" = "$1" ] && return
  status=$1
  printf '%s\npid=%s\n%.512s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$$" "$status" > "$MODDIR/status.log"
}
query() { timeout -s KILL 4 "$@" </dev/null 2>/dev/null; }
heartbeat=0
beat() {
  now=$(cut -d. -f1 /proc/uptime)
  if [ "$((now-heartbeat))" -ge 10 ] || [ "$heartbeat" = 0 ]; then
    printf '%s\n' "$now" > "$MODDIR/heartbeat"
    heartbeat=$now
  fi
}
report_status 'Monitor started'
previous=
candidate=
interval=2
errors=0
while [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; do
  beat
  sleep "$interval" </dev/null
  if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ]; then break; fi
  [ -s "$CONFIG/packages" ] || { report_status 'Waiting for bindings: open Tubing settings and sync'; previous=; candidate=; interval=10; continue; }
  power=$(query dumpsys -t 3 power)
  if [ -z "$power" ]; then
    errors=$((errors+1)); interval=$((errors*3)); [ "$interval" -le 15 ] || interval=15
    report_status 'Power query failed/timed out; backing off'; candidate=; continue
  fi
  printf '%s\n' "$power" | grep -q 'mWakefulness=Awake' || { report_status 'Paused: screen asleep'; previous=; candidate=; interval=5; continue; }
  activities=$(query dumpsys -t 3 activity activities)
  identity=$(foreground_identity "$activities")
  pkg=${identity#*\|}
  profile=${identity%%\|*}
  # Unknown samples must not re-arm an app that is still open.
  if [ -z "$pkg" ]; then
    errors=$((errors+1)); interval=$((errors*3)); [ "$interval" -le 15 ] || interval=15
    report_status 'Foreground unknown/query timed out; backing off'; candidate=; continue
  fi
  errors=0
  grep -Fxq "$pkg" "$CONFIG/packages" || { report_status 'Monitoring: foreground app is not bound'; previous=$identity; candidate=; interval=4; continue; }
  interval=2
  [ "$previous" = "$identity" ] && continue
  [ "$candidate" = "$identity" ] || { report_status "Confirming bound app: $pkg"; candidate=$identity; continue; }
  # Query user/keyguard only for a bound-app transition. Final launch rechecks in App.
  policy=$(query dumpsys -t 3 window policy)
  device_unlocked "$power" "$policy" || { report_status 'Paused: screen locked or unknown keyguard'; previous=; candidate=; interval=5; continue; }
  user=$(query am get-current-user)
  [ "$user" = 0 ] || { report_status 'Current user query failed or secondary user; backing off'; previous=; candidate=; interval=10; continue; }
  previous=$identity
  report_status "Dispatching binding: $pkg"
  result=$(timeout -s KILL 6 am start-foreground-service --user 0 \
    -n com.stealth.rootexecutor/.AutomationService --es package "$pkg" --ei profileUser "$profile" </dev/null 2>&1)
  # Bound output size: keep the latest dispatch for diagnostics, never script input.
  rc=$?
  printf '%s\n%.2048s\n' "$pkg" "$result" > "$MODDIR/last-trigger.log"
  if printf '%s' "$result" | grep -q 'Blocked by AutoLaunch'; then
    report_status 'Blocked by AutoLaunch: allow Tubing autostart in system settings'
  elif [ "$rc" != 0 ] || printf '%s' "$result" | grep -Eq 'Error:|Exception|not allowed'; then
    report_status 'Dispatch failed/timed out: see last-trigger.log'
  else
    report_status 'Request dispatched; see Tubing for actual execution result'
  fi
done
report_status 'Monitor stopped: module disabled or removed'
