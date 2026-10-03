#!/bin/bash
# Hellgram.app launcher: boots the runtime, syncs the newest release APK, opens Telegram.
set -u

BASE="$HOME/Library/HellgramMac"
export ANDROID_SDK_ROOT="$BASE/sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_AVD_HOME="$BASE/avd"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
EMU="$ANDROID_SDK_ROOT/emulator/emulator"
LOG="$BASE/launcher.log"
CACHE="$BASE/cache"
APK="$CACHE/hellgram-latest.apk"
MARKER="$CACHE/apk-marker"
REPO="${HELLGRAM_REPO:-HELBOYCODER/Hellgram}"
PKG="ua.entaytion.entinygram"
ACT="$PKG/org.telegram.ui.LaunchActivity"

[ -f "$LOG" ] && [ "$(wc -c <"$LOG")" -gt 2000000 ] && : >"$LOG"
exec >>"$LOG" 2>&1
echo "---- $(date) launch ----"

notify() { osascript -e "display notification \"$1\" with title \"Hellgram\"" >/dev/null 2>&1 || true; }
fail() { notify "$1"; echo "FATAL: $1" >&2; exit 1; }

[ -x "$EMU" ] || fail "Runtime missing. Re-run macos/install.command."
mkdir -p "$CACHE"

# single instance
lock="$BASE/.launcher.lock"
if [ -f "$lock" ] && kill -0 "$(cat "$lock" 2>/dev/null)" 2>/dev/null; then
  echo "another launcher is running, exiting"; exit 0
fi
echo $$ >"$lock"; trap 'rm -f "$lock"' EXIT

# 1. ensure emulator is up
serial=$("$ADB" devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ {print $1; exit}')
if [ -z "$serial" ]; then
  if ! pgrep -f "qemu.*hellgram|emulator.*@hellgram" >/dev/null 2>&1; then
    echo "starting emulator"
    nohup "$EMU" @hellgram -gpu host -no-boot-anim -no-metrics >>"$LOG" 2>&1 &
  fi
  "$ADB" wait-for-device >/dev/null 2>&1 || true
  booted=""
  for _ in $(seq 1 150); do
    booted=$("$ADB" -e shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n ')
    [ "$booted" = "1" ] && break
    sleep 2
  done
  [ "$booted" = "1" ] || fail "Android runtime did not finish booting in ~5 min. See $LOG"
  serial=$("$ADB" devices | awk '/^emulator-[0-9]+[[:space:]]+device$/ {print $1; exit}')
  [ -n "$serial" ] || fail "device not available after boot. See $LOG"
fi
echo "device: $serial"

# 2. fetch newest Hellgram APK from GitHub releases
asset_json() {
  if command -v gh >/dev/null 2>&1; then
    gh api "repos/$REPO/releases/latest" 2>/dev/null && return
  fi
  curl -fsSL -H 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null
}
pick_apk() {
  node -e '
    let d = "";
    process.stdin.on("data", c => d += c).on("end", () => {
      const r = JSON.parse(d);
      const a = (r.assets || []).filter(x => x.name.endsWith(".apk"));
      const best = a.find(x => x.name === "hellgram-latest.apk") || a[0];
      if (!best) process.exit(4);
      console.log(best.browser_download_url + "\t" + best.updated_at + "\t" + best.name);
    });' 2>/dev/null || python3 -c '
import json, sys
r = json.load(sys.stdin)
a = [x for x in r.get("assets", []) if x["name"].endswith(".apk")]
best = next((x for x in a if x["name"] == "hellgram-latest.apk"), a[0] if a else None)
if not best: sys.exit(4)
print(best["browser_download_url"] + "\t" + best["updated_at"] + "\t" + best["name"])
'
}
info=$(asset_json | pick_apk) || true
if [ -n "${info:-}" ]; then
  url=${info%%$'\t'*}; rest=${info#*$'\t'}; stamp=${rest%%$'\t'*}; name=${rest##*$'\t'}
  if [ "$stamp" != "$(cat "$MARKER" 2>/dev/null)" ] || [ ! -f "$APK" ]; then
    echo "updating APK: $name ($stamp)"
    notify "Updating Hellgram APK..."
    if curl -fL --retry 3 -o "$APK.part" "$url"; then
      mv -f "$APK.part" "$APK"
      out=$("$ADB" install -r -t "$APK" 2>&1) || true
      echo "$out" | tail -3
      if echo "$out" | grep -q "Success"; then
        printf '%s' "$stamp" >"$MARKER"
        notify "Hellgram updated."
      else
        notify "APK update failed: $(echo "$out" | tr -d '"\\\n' | tail -c 160)"
      fi
    fi
  fi
else
  echo "could not resolve latest APK asset"
fi

# 3. open Hellgram
"$ADB" shell am start -n "$ACT" >/dev/null 2>&1 \
  || "$ADB" shell monkey -p "$PKG" 1 >/dev/null 2>&1 \
  || fail "could not start Hellgram activity"
notify "Hellgram is ready."
echo "launched"
