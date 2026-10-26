#!/system/bin/sh
# Pure parsers kept separate so OEM samples can be checked without running a monitor.
foreground_identity() {
  activities=$1
  line=$(printf '%s\n' "$activities" | grep 'topResumedActivity' | head -n 1)
  [ -n "$line" ] || line=$(printf '%s\n' "$activities" | grep 'mResumedActivity' | head -n 1)
  printf '%s\n' "$line" | sed -n 's/.* u\([0-9][0-9]*\) \([A-Za-z][A-Za-z0-9_.]*\)\/.*/\1|\2/p'
}
foreground_package() {
  identity=$(foreground_identity "$1")
  printf '%s\n' "${identity#*\|}"
}

device_unlocked() {
  printf '%s\n' "$1" | grep -q 'mWakefulness=Awake' || return 1
  printf '%s\n' "$2" | grep -Eq 'showing=true|mShowingLockscreen=true|isStatusBarKeyguard=true' && return 1
  # Unknown keyguard output fails closed rather than launching from a lockscreen.
  printf '%s\n' "$2" | grep -Eq 'showing=false|mShowingLockscreen=false|isStatusBarKeyguard=false'
}
