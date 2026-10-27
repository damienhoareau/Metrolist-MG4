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
adb shell wm density "${SCREEN_DENSITY:-240}"
adb shell settings put global policy_control "immersive.full=*" || true

adb install -r "$APK" || { echo "install failed"; exit 1; }
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell pm grant "$PKG" android.permission.RECORD_AUDIO || true

adb shell am start -n "$PKG/$ACT"
for t in 15 30 45; do
  sleep 15
  shot "launch-${t}s"
  dump_ui "$OUT/ui-$t.xml" || echo "ui dump $t failed" >> "$OUT/notes.txt"
done

adb logcat -d -t 4000 > "$OUT/logcat.txt" || true
adb shell wm size > "$OUT/screen.txt"; adb shell wm density >> "$OUT/screen.txt"
echo "done"
