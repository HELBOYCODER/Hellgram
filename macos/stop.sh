#!/bin/bash
# Quit the Hellgram Android runtime (keeps data and fast-boot snapshot).
BASE="$HOME/Library/HellgramMac"
export ANDROID_SDK_ROOT="$BASE/sdk"
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_AVD_HOME="$BASE/avd"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"

if "$ADB" devices 2>/dev/null | grep -q '^emulator-[0-9]*[[:space:]]*device'; then
  "$ADB" -e emu kill >/dev/null 2>&1
else
  pkill -f 'qemu.*hellgram|emulator.*@hellgram' 2>/dev/null
fi
osascript -e 'display notification "Hellgram stopped." with title "Hellgram"' >/dev/null 2>&1 || true
