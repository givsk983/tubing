#!/system/bin/sh
[ "$BOOTMODE" = true ] || abort "Install from the Magisk app."
[ "$API" -ge 26 ] || abort "Android 8.0 or newer is required."
ui_print "- Install the companion Tubing APK, then configure App bindings in Settings."
ui_print "- Reboot to start foreground app detection."
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/monitor.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/supervisor.sh" 0 0 0755
