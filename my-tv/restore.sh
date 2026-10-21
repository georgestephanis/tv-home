#!/bin/bash
# Undo debloat.sh + the launcher swap, using the values saved in backup-latest/.
#   ./restore.sh                     re-enable everything, restore settings, Google TV home back
#   ./restore.sh --remove-launcher   ...and uninstall the custom launcher too
set -u
cd "$(dirname "$0")"
TV=${TV:-192.168.0.140:5555}
ADB=${ADB:-adb}
s(){ "$ADB" -s "$TV" "$@"; }
BK=backup-latest
"$ADB" connect "$TV" >/dev/null

# Re-enable every package we disabled (one pm call per shell: chained pm calls can silently stop).
while read -r p; do
  [ -z "$p" ] && continue
  grep -qx "package:$p" "$BK/packages-disabled.txt" && { echo "keep disabled (was already): $p"; continue; }
  echo "enable $p: $(s shell pm enable --user 0 "$p" </dev/null 2>&1 | tail -1)"  # </dev/null: adb shell would eat the loop's stdin
done < disabled-by-debloat.txt

# Settings back to their backed-up values; delete keys that didn't exist before.
restore_setting(){ # table key
  local v; v=$(grep -m1 "^$2=" "$BK/settings-$1.txt" | cut -d= -f2-)
  if [ -n "$v" ]; then s shell settings put "$1" "$2" "$v"; else s shell settings delete "$1" "$2" >/dev/null; fi
  echo "$1 $2 -> ${v:-<deleted>}"
}
restore_setting secure screensaver_components
restore_setting secure screensaver_default_component
for k in window_animation_scale transition_animation_scale animator_duration_scale; do restore_setting global $k; done

# Google TV home back as the default.
home=$(tail -1 "$BK/home-activity.txt")
s shell cmd package set-home-activity --user 0 "$home"
[ "${1:-}" = "--remove-launcher" ] && s shell pm uninstall com.mihir.tvhome
s shell input keyevent KEYCODE_HOME
echo "Restored. Home -> $home"
