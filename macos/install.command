#!/bin/bash
# One-time Hellgram-for-Mac installer: runtime from our GitHub releases -> Hellgram.app.
set -euo pipefail

REPO="${HELLGRAM_REPO:-HELBOYCODER/Hellgram}"
RUNTIME_TAG="macos-runtime"
BASE="$HOME/Library/HellgramMac"
SDK="$BASE/sdk"
AVD_HOME="$BASE/avd"
CACHE="$BASE/cache"
APP_DIR="$HOME/Applications"
RAW="https://raw.githubusercontent.com/$REPO/main/macos"

step() { printf '\n\033[1;35m==> %s\033[0m\n' "$1"; }
die() { printf '\n\033[1;31mERROR: %s\033[0m\n' "$1" >&2; exit 1; }

[ "$(uname -m)" = "arm64" ] || die "Hellgram for Mac needs an Apple Silicon Mac (M1/M2/M3/M4).
Required: یک مک با تراشه Apple Silicon."
major=$(sw_vers -productVersion | cut -d. -f1)
[ "$major" -ge 12 ] || die "macOS 12 or newer required (found $(sw_vers -productVersion))."

command -v curl >/dev/null || die "curl missing."
JSON=""
if command -v node >/dev/null 2>&1; then JSON="node"
elif command -v python3 >/dev/null 2>&1; then JSON="python3"
else die "need node or python3 (Xcode Command Line Tools: xcode-select --install)."; fi

free_kb=$(df -k "$HOME" | awk 'NR==2{print $4}')
[ "$free_kb" -gt 7000000 ] || die "not enough free disk space: need ~8 GB, have $((free_kb / 1000000)) MB.
حداقل ۸ گیگ فضای دیسک آزاد لازم است."

api_get() {
  if command -v gh >/dev/null 2>&1; then
    gh api "repos/$REPO/releases/tags/$1" 2>/dev/null && return
  fi
  curl -fsSL -H 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/$REPO/releases/tags/$1"
}

# asset_url NAME -> prints "<url>\t<updated_at>"
asset_url() {
  api_get "$RUNTIME_TAG" | jget "asset" "$1"
}

jget() {
  case "$JSON" in
    node) node -e '
      let d = "";
      process.stdin.on("data", c => d += c).on("end", () => {
        const r = JSON.parse(d);
        if (process.argv[1] === "asset") {
          const a = (r.assets || []).find(x => x.name === process.argv[2]);
          if (!a) process.exit(3);
          console.log(a.browser_download_url + "\t" + a.updated_at);
        }
      });' "$@" ;;
    python3) python3 -c '
import json, sys
r = json.load(sys.stdin)
if sys.argv[1] == "asset":
    a = next((x for x in r.get("assets", []) if x["name"] == sys.argv[2]), None)
    if not a: sys.exit(3)
    print(a["browser_download_url"] + "\t" + a["updated_at"])
' "$@" ;;
  esac
}

fetch_asset() { # name dest
  local name="$1" dest="$2" info url stamp marker="$BASE/.${name}.stamp"
  info=$(asset_url "$name") || die "asset '$name' not found in release '$RUNTIME_TAG'.
Run the 'macos-runtime' GitHub Actions workflow first, or use --force-runtime."
  url=${info%%$'\t'*}; stamp=${info##*$'\t'}
  if [ "${FORCE_RUNTIME:-0}" != "1" ] && [ -f "$dest" ] && [ "$(cat "$marker" 2>/dev/null)" = "$stamp" ]; then
    printf '    %s already up to date\n' "$name"
    return 0
  fi
  printf '    downloading %s ...\n' "$name"
  curl -fL --retry 3 --progress-bar -o "$dest.part" "$url"
  mv -f "$dest.part" "$dest"
  printf '%s' "$stamp" > "$marker"
}

mkdir -p "$BASE" "$SDK" "$AVD_HOME" "$CACHE" "$APP_DIR"

if [ "${1:-}" = "--force-runtime" ]; then FORCE_RUNTIME=1; fi

step "1/4 Downloading Android runtime (via GitHub, not Google)"
fetch_asset hellgram-macos-platform-tools.tar.gz "$CACHE/platform-tools.tar.gz"
fetch_asset hellgram-macos-emulator.tar.gz "$CACHE/emulator.tar.gz"
fetch_asset hellgram-macos-system-image.tar.gz "$CACHE/system-image.tar.gz"

step "2/4 Installing SDK into $SDK"
tar -xzf "$CACHE/platform-tools.tar.gz" -C "$SDK"
tar -xzf "$CACHE/emulator.tar.gz" -C "$SDK"
tar -xzf "$CACHE/system-image.tar.gz" -C "$SDK"
[ -x "$SDK/emulator/emulator" ] && [ -x "$SDK/platform-tools/adb" ] || die "runtime extraction failed."

step "3/4 Creating the Hellgram Android device (AVD, no Java needed)"
cat > "$AVD_HOME/hellgram.ini" <<EOF
avd.ini.encoding=UTF-8
path=$AVD_HOME/hellgram.avd
path.rel=avd/hellgram.avd
target=android-34
EOF
mkdir -p "$AVD_HOME/hellgram.avd"
cat > "$AVD_HOME/hellgram.avd/config.ini" <<EOF
AvdId=hellgram
PlayStore.enabled=false
abi.type=arm64-v8a
avd.ini.displayname=Hellgram
avd.ini.encoding=UTF-8
disk.dataPartition.size=6442450944
fastboot.forceChosenSnapshotBoot=no
fastboot.forceColdBoot=no
fastboot.forceFastBoot=yes
hw.accelerometer=yes
hw.arc=false
hw.audioInput=yes
hw.battery=yes
hw.camera.back=emulated
hw.camera.front=emulated
hw.cpu.arch=arm64
hw.cpu.ncore=2
hw.dPad=no
hw.device.manufacturer=Google
hw.device.name=pixel_7
hw.gps=yes
hw.gpu.enabled=yes
hw.gpu.mode=host
hw.initialOrientation=Portrait
hw.keyboard=yes
hw.lcd.density=420
hw.lcd.height=2400
hw.lcd.width=1080
hw.mainKeys=no
hw.ramSize=2048
hw.sdCard=yes
hw.sensors.orientation=yes
hw.sensors.proximity=yes
hw.trackBall=no
image.sysdir.1=system-images/android-34/google_apis/arm64-v8a/
sdcard.size=512M
tag.display=Google APIs
tag.id=google_apis
target=android-34
EOF

step "4/4 Building Hellgram.app"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/launcher.sh" ]; then
  cp "$SCRIPT_DIR/launcher.sh" "$CACHE/launcher.sh"
  cp "$SCRIPT_DIR/stop.sh" "$CACHE/stop.sh"
  cp "$SCRIPT_DIR/logo.png" "$CACHE/logo.png" 2>/dev/null || \
    curl -fsSL -o "$CACHE/logo.png" "https://raw.githubusercontent.com/$REPO/main/assets/logo.png"
else
  curl -fsSL -o "$CACHE/launcher.sh" "$RAW/launcher.sh"
  curl -fsSL -o "$CACHE/stop.sh" "$RAW/stop.sh"
  curl -fsSL -o "$CACHE/logo.png" "https://raw.githubusercontent.com/$REPO/main/assets/logo.png"
fi

APP="$APP_DIR/Hellgram.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$CACHE/launcher.sh" "$APP/Contents/MacOS/Hellgram"
chmod +x "$APP/Contents/MacOS/Hellgram"
cat > "$APP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>Hellgram</string>
  <key>CFBundleDisplayName</key><string>Hellgram</string>
  <key>CFBundleIdentifier</key><string>ua.entaytion.hellgram.mac</string>
  <key>CFBundleExecutable</key><string>Hellgram</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>LSMinimumSystemVersion</key><string>12.0</string>
  <key>LSApplicationCategoryType</key><string>public.app-category.social-networking</string>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
EOF

# App icon from repo logo
ICONSET="$CACHE/AppIcon.iconset"
rm -rf "$ICONSET"; mkdir -p "$ICONSET"
for s in 16 32 64 128 256 512 1024; do
  sips -z "$s" "$s" "$CACHE/logo.png" --out "$ICONSET/m_${s}.png" >/dev/null
done
cp "$ICONSET/m_16.png"   "$ICONSET/icon_16x16.png"
cp "$ICONSET/m_32.png"   "$ICONSET/icon_16x16@2x.png"
cp "$ICONSET/m_32.png"   "$ICONSET/icon_32x32.png"
cp "$ICONSET/m_64.png"   "$ICONSET/icon_32x32@2x.png"
cp "$ICONSET/m_128.png"  "$ICONSET/icon_128x128.png"
cp "$ICONSET/m_256.png"  "$ICONSET/icon_128x128@2x.png"
cp "$ICONSET/m_256.png"  "$ICONSET/icon_256x256.png"
cp "$ICONSET/m_512.png"  "$ICONSET/icon_256x256@2x.png"
cp "$ICONSET/m_512.png"  "$ICONSET/icon_512x512.png"
cp "$ICONSET/m_1024.png" "$ICONSET/icon_512x512@2x.png"
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"

STOP="$APP_DIR/Hellgram Stop.app"
rm -rf "$STOP"
mkdir -p "$STOP/Contents/MacOS" "$STOP/Contents/Resources"
cp "$CACHE/stop.sh" "$STOP/Contents/MacOS/HellgramStop"
chmod +x "$STOP/Contents/MacOS/HellgramStop"
cat > "$STOP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>Hellgram Stop</string>
  <key>CFBundleDisplayName</key><string>Hellgram Stop</string>
  <key>CFBundleIdentifier</key><string>ua.entaytion.hellgram.mac.stop</string>
  <key>CFBundleExecutable</key><string>HellgramStop</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>LSMinimumSystemVersion</key><string>12.0</string>
  <key>LSUIElement</key><true/>
  <key>NSHighResolutionCapable</key><true/>
</dict>
</plist>
EOF
cp "$APP/Contents/Resources/AppIcon.icns" "$STOP/Contents/Resources/AppIcon.icns"

/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$APP" 2>/dev/null || true

cat <<EOF

$(printf '\033[1;32m✓ Done!\033[0m')  Hellgram.app installed to $APP_DIR

  ▶ Start:    open "$APP"   (Spotlight: 'Hellgram')
  ■ Stop:     open "$STOP"
  📄 Logs:    $BASE/launcher.log
  ✖ Uninstall: rm -rf "$BASE" "$APP" "$STOP"

  The Hellgram window is an on-device Android runtime running the exact same
  Hellgram APK — every Hellgram feature is unchanged.
  Run on Mac: the first boot takes ~2 minutes, after that it resumes in seconds.
  پنجره Hellgram همان APK اندروید را با همهٔ فیچرها اجرا می‌کند؛ اولین اجرا
  حدود ۲ دقیقه طول می‌کشد، بعد از آن سریع بالا می‌آید.
EOF
