#!/bin/bash
# Reversible debloat for Google TV Streamer (kirkwood), user 0 only.
# Only uses `pm disable-user` + `settings put`; undo with restore.sh.
set -u
TV=${TV:-192.168.0.140:5555}
ADB=${ADB:-adb}
s(){ "$ADB" -s "$TV" "$@"; }
LOG=$(dirname "$0")/disabled-by-debloat.txt

PKGS=(
  # ads / Privacy Sandbox
  com.google.android.adservices.api
  com.google.android.ext.adservices.api
  com.google.android.ondevicepersonalization.services
  com.google.android.federatedcompute
  # telemetry / feedback
  com.google.android.feedback
  com.android.tv.feedbackconsent
  com.google.android.tv.remotecontrol.logging
  com.google.android.partnersetup
  # junk / partner auto-installs
  android.autoinstalls.config.google.sabrina
  com.android.gallery3d
  com.google.android.videos
  com.google.android.play.games
  # Google Home panel + Ambient (user opted out)
  com.google.android.apps.tv.dreamx
)

# Fall back to the basic screensaver before disabling dreamx
s shell settings put secure screensaver_components com.android.dreams.basic/.Colors
s shell settings put secure screensaver_default_component com.android.dreams.basic/.Colors

for p in "${PKGS[@]}"; do
  if out=$(s shell pm disable-user --user 0 "$p" 2>&1) && [[ $out == *disabled-user* ]]; then
    grep -qx "$p" "$LOG" 2>/dev/null || echo "$p" >> "$LOG"
    echo "OK   $p"
  else
    echo "SKIP $p: $out"
  fi
done

for k in window_animation_scale transition_animation_scale animator_duration_scale; do
  s shell settings put global $k 0.5
done

# Launcher swap, only if our launcher is installed (otherwise there'd be no usable Home).
if s shell pm path com.mihir.tvhome | grep -q package:; then
  # launcherx: stock home with ads; tvlauncher: legacy home;
  # setupwraith: setup wizard's RecoveryActivity outranks any third-party HOME (needs setup complete)
  for p in com.google.android.apps.tv.launcherx com.google.android.tvlauncher com.google.android.tungsten.setupwraith; do
    [ "$p" = com.google.android.tungsten.setupwraith ] && [ "$(s shell settings get secure user_setup_complete)" != 1 ] && { echo "SKIP $p: setup incomplete"; continue; }
    out=$(s shell pm disable-user --user 0 "$p" 2>&1)
    [[ $out == *disabled-user* ]] && { grep -qx "$p" "$LOG" 2>/dev/null || echo "$p" >> "$LOG"; echo "OK   $p"; } || echo "SKIP $p: $out"
  done
  s shell cmd package set-home-activity --user 0 com.mihir.tvhome/.MainActivity
  s shell am force-stop com.android.tv.settings
  s shell input keyevent KEYCODE_HOME
fi
