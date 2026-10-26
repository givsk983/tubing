#!/system/bin/sh
MODDIR=${0%/*}
echo '=== Tubing App Trigger status ==='
cat "$MODDIR/module.prop"
echo
for item in status.log last-trigger.log monitor-output.log; do
  echo "=== $item ==="
  if [ -f "$MODDIR/$item" ]; then tail -n 25 "$MODDIR/$item"; else echo '(not available; reboot after installation)'; fi
done
echo '=== Synced target packages ==='
if [ -f /data/adb/tubing-automation/packages ]; then
  cat /data/adb/tubing-automation/packages
else
  echo 'No bindings synced. Open Tubing settings and tap sync.'
fi
