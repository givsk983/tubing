#!/system/bin/sh
# Feed detection.sh followed by this file to an Android sh; no device files changed.
set -eu
expect_package() {
  actual=$(foreground_package "$1")
  [ "$actual" = "$2" ] || { echo "FAIL package: expected $2, got $actual"; exit 1; }
}
expect_package 'topResumedActivity=ActivityRecord{212122609 u0 com.example.app/.MainActivity t368}' com.example.app
expect_package 'mResumedActivity: ActivityRecord{123 u0 com.legacy_app/.Main t2}' com.legacy_app
expect_package 'topResumedActivity=ActivityRecord{123 u10 com.work.app/.Main t2}' com.work.app
[ "$(foreground_identity 'topResumedActivity=ActivityRecord{123 u999 com.same.app/.Main t2}')" = '999|com.same.app' ]
[ "$(foreground_identity 'topResumedActivity=ActivityRecord{123 u0 com.same.app/.Main t2}')" = '0|com.same.app' ]
expect_package 'topResumedActivity=null
mResumedActivity: ActivityRecord{123 u0 com.background/.Main t2}' ''
expect_package 'mResumedActivity: ActivityRecord{123 u0 com.other/.Main t2}
topResumedActivity=ActivityRecord{123 u0 com.foreground/.Main t3}' com.foreground
expect_package '' ''
device_unlocked 'mWakefulness=Awake' 'showing=false'
if device_unlocked 'mWakefulness=Asleep' 'showing=false'; then exit 1; fi
if device_unlocked 'mWakefulness=Awake' 'showing=true'; then exit 1; fi
if device_unlocked 'mWakefulness=Awake' ''; then exit 1; fi
if device_unlocked '' 'showing=false'; then exit 1; fi
if device_unlocked 'mWakefulness=Awake' 'showing=false mShowingLockscreen=true'; then exit 1; fi
echo 'PASS: 14 foreground/profile/lockscreen parser checks'
