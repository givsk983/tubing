#!/system/bin/sh
MODDIR=${0%/*}
umask 077
exec 0>"$MODDIR/supervisor.lock"
flock -n 0 || exit 0
printf '%s\n' "$$" > "$MODDIR/supervisor.pid"
delay=3
while [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]; do
  /system/bin/sh "$MODDIR/monitor.sh" </dev/null > "$MODDIR/monitor-output.log" 2>&1
  if [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ]; then break; fi
  sleep "$delay" </dev/null
  delay=$((delay*2)); [ "$delay" -le 30 ] || delay=30
done
