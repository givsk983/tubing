#!/system/bin/sh
MODDIR=${0%/*}
# Magisk's late_start service stage must return; the monitor waits for boot itself.
/system/bin/sh "$MODDIR/supervisor.sh" </dev/null >/dev/null 2>&1 &
