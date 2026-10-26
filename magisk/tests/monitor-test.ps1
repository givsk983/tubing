# Runs the production monitor with mocked Android queries in a temporary device
# directory. Does not install a module, invoke a real App binding or require Root.
param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference = 'Stop'
$module = Join-Path (Split-Path -Parent $PSScriptRoot) 'tubing-app-trigger'
$monitor = [IO.File]::ReadAllText((Join-Path $module 'monitor.sh')) -replace "`r`n", "`n"
$detection = [IO.File]::ReadAllText((Join-Path $module 'detection.sh')) -replace "`r`n", "`n"
$monitor = $monitor.Replace('MODDIR=${0%/*}', 'MODDIR=$fixture/module')
$monitor = $monitor.Replace('CONFIG=/data/adb/tubing-automation', 'CONFIG=$fixture/config')
$monitor = $monitor.Replace('. "$MODDIR/detection.sh"', $detection)
$prefix = @'
set -eu
fixture=$(mktemp -d /data/local/tmp/tubing-monitor-test.XXXXXX)
trap 'rm -rf "$fixture"' EXIT
mkdir "$fixture/module" "$fixture/config"
printf 'com.test.target\n' > "$fixture/config/packages"
step=0
getprop() { echo 1; }
sleep() {
  step=$((step + 1))
  [ "$step" -lt 12 ] || touch "$fixture/module/disable"
}
am() {
  case "$1" in
    get-current-user) echo 0 ;;
    start-foreground-service) printf '%s\n' "$*" >> "$fixture/calls"; echo 'Mock service started' ;;
    *) echo 'Unexpected am command' >&2; exit 1 ;;
  esac
}
timeout() { shift 3; "$@"; }
dumpsys() {
  [ "$1" != '-t' ] || shift 2
  case "$1" in
    power) echo 'mWakefulness=Awake' ;;
    window) echo 'showing=false' ;;
    activity)
      user=0
      case "$step" in 4|5|6) target=com.test.target; user=999 ;; 7|8) target=com.test.other ;; *) target=com.test.target ;; esac
      echo "topResumedActivity=ActivityRecord{123 u$user $target/.Main t1}"
      ;;
  esac
}
'@
$suffix = @'
[ -f "$fixture/calls" ] || { cat "$fixture/module/status.log"; echo 'FAIL: no dispatch'; exit 1; }
[ "$(wc -l < "$fixture/calls" | tr -d ' ')" = 3 ] || { cat "$fixture/calls"; cat "$fixture/module/status.log"; echo 'FAIL: expected three original/clone/reentry dispatches'; exit 1; }
grep -q -- '--ei profileUser 999' "$fixture/calls"
grep -q -- '--ei profileUser 0' "$fixture/calls"
grep -q 'Monitor stopped' "$fixture/module/status.log"
echo 'PASS: production monitor starts, confirms foreground, deduplicates, shares package rule across original/clone users, skips unbound apps, retriggers on re-entry, exits when disabled'
'@
$payload = ($prefix + "`n" + $monitor + "`n" + $suffix + "`n") -replace "`r`n", "`n"
$remoteRunner = 'testfile=$(mktemp /data/local/tmp/tubing-monitor-run.XXXXXX); cat > "$testfile"; sh "$testfile"; result=$?; rm -f "$testfile"; exit "$result"'
$result = $payload | adb -s $Serial shell $remoteRunner
$result | Write-Output
if ($LASTEXITCODE -ne 0) { throw 'Monitor integration test failed' }
if (($result -join "`n") -notmatch 'PASS: production monitor') { throw 'Monitor test did not complete' }
