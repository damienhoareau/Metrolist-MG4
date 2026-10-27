#!/usr/bin/env bash
# Runs inside the Android emulator (started by reactivecircus/android-emulator-runner).
# Installs the APK, launches Metrolist and saves screenshots + UI dumps + logcat to $OUT.
set -u
APK="$1"
OUT="${2:-preview-out}"
PKG=com.metrolist.music.mg4
ACT=com.metrolist.music.MainActivity
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }

# uiautomator dump often fails while the UI is animating: retry a few times.
dump_ui() {
  for i in 1 2 3 4 5 6; do
    if adb shell uiautomator dump /sdcard/ui.xml 2>&1 | grep -q "dumped to"; then
      adb pull /sdcard/ui.xml "$1" >/dev/null 2>&1 && return 0
    fi
    sleep 3
  done
  return 1
}

# Area the app gets on the head unit; hide the emulator's own system bars.
adb shell wm size "${SCREEN_SIZE:-1744x640}"
adb shell settings put global policy_control "immersive.full=*" || true

adb install -r "$APK" || { echo "install failed"; exit 1; }
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true

# The app now defaults to a 1.25x density scale, so an emulated density D gives
# an overall scale of D/240 * 1.25 compared with the previous 240 dpi look.
# Each entry is "emulated_dpi:overall_scale_label".
for entry in ${SCREEN_DENSITIES:-240:1.25x}; do
  dpi="${entry%%:*}"; label="${entry##*:}"
  adb shell am force-stop "$PKG"
  adb shell wm density "$dpi"
  sleep 3
  adb shell am start -n "$PKG/$ACT"
  sleep 25
  shot "scale-$label"
  dump_ui "$OUT/ui-$label.xml" || echo "ui dump $label failed" >> "$OUT/notes.txt"
done

# Player screens, driven by blind taps (uiautomator dumps fail on this UI).
# Coordinates assume the default 1744x640 screen at 240 dpi (overall scale 1.25x).
W="${SCREEN_SIZE:-1744x640}"; H="${W#*x}"; W="${W%x*}"
adb shell wm density 240
adb shell am force-stop "$PKG"
sleep 2
adb shell am start -n "$PKG/$ACT"
sleep 25
# First speed-dial card starts playback (shifted down by the 80 px head unit bar).
adb shell input tap $((W * 19 / 100)) $((H * 85 / 100))
sleep 20
shot player-1-after-card-tap
# Swipe up on the mini player to expand it.
adb shell input swipe $((W / 2)) $((H * 94 / 100)) $((W / 2)) $((H * 20 / 100)) 300
sleep 4
shot player-2-after-swipe
# Collapse, then try expanding with a tap on the mini player.
adb shell input keyevent KEYCODE_BACK
sleep 3
adb shell input tap $((W / 2)) $((H * 94 / 100))
sleep 4
shot player-3-after-tap

adb logcat -d -t 4000 > "$OUT/logcat.txt" || true
adb shell wm size > "$OUT/screen.txt"; adb shell wm density >> "$OUT/screen.txt"
echo "done"
