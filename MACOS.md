# Hellgram for macOS (Apple Silicon)

Run the **exact same Hellgram APK** — with every Hellgram feature unchanged — on
your Mac. Hellgram for Mac is not a rewrite: it boots a tiny on-device Android
runtime (the official Google emulator, no Google Play, no accounts) and installs
the Hellgram APK into it. Your chats, Hell Tunnel, Ghost Mode, PiP, proxies — all
identical, logins persist between launches.

## Requirements

- Apple Silicon Mac (M1/M2/M3/M4), macOS 12+
- ~8 GB free disk, ~2.5 GB RAM while running
- `curl` + (`node` or Python 3) — `xcode-select --install` covers everything

## Install (one time)

```sh
bash macos/install.command
```

or download and double-click:
<https://raw.githubusercontent.com/HELBOYCODER/Hellgram/main/macos/install.command>
(right-click → Open the first time, because it was downloaded)

The installer fetches the Android runtime from this repo's GitHub releases
(tag `macos-runtime`, built by the **macOS Runtime** workflow — works even where
`dl.google.com` is blocked), creates the private Hellgram device, and puts
**Hellgram.app** into `~/Applications`.

## Use

- **Start**: open `Hellgram.app` (Spotlight works). First boot ≈ 2 minutes; after
  that it resumes in seconds and auto-updates the APK whenever a new GitHub
  release with an APK asset is published.
- **Stop**: open `Hellgram Stop.app` (graceful shutdown, keeps snapshot + data).
- **Logs**: `~/Library/HellgramMac/launcher.log`
- **Update runtime**: `bash macos/install.command --force-runtime`
- **Uninstall**: `rm -rf ~/Library/HellgramMac ~/Applications/Hellgram.app ~/Applications/"Hellgram Stop.app"`

## How it works

```
GitHub Actions (macos-runtime.yml)          your Mac
  downloads from Google        ──►  Releases: macos-runtime assets
  re-hosts on GitHub                          │ install.command
                                              ▼
                          ~/Library/HellgramMac/sdk/{emulator,platform-tools,system-images}
                          Hellgram.app ──► emulator @hellgram -gpu host
                                       ──► adb install latest Hellgram APK (from Releases)
                                       ──► am start org.telegram.ui.LaunchActivity
```

The APK comes from the **latest GitHub Release** that has an `.apk` asset
(e.g. `v1.0.0` → `hellgram-test-28.apk`), so publishing a normal Hellgram
release is all it takes to update every Mac.

---

# Hellgram روی macOS (اپل سیلیکون)

این نسخه **دقیقاً همان APK اندروید Hellgram** را با **همهٔ فیچرها بدون هیچ تغییری**
روی مک اجرا می‌کند: یک اندرویدِ کوچکِ روی‌دستگاه (emulator رسمی گوگل، بدون Play
Store و بدون اکانت) بالا می‌آید و APK داخلش نصب می‌شود. چت‌ها، تونل Hellboy،
Ghost Mode، PiP و پروکسی‌ها عیناً کار می‌کنند و لاگین بین اجراها باقی می‌ماند.

**نیازها:** مک با تراشهٔ Apple Silicon، macOS 12 یا جدیدتر، حدود ۸ گیگ دیسک آزاد.

**نصب یک‌باره:** فایل `macos/install.command` را دانلود و (راست‌کلیک → Open) اجرا کنید.
اسکریپت، رانتایم اندروید را از ریلیز گیت‌هاب همین ریپو (تگ `macos-runtime`)
می‌گیرد — یعنی حتی اگر `dl.google.com` بسته باشد هم کار می‌کند — و اپ
**Hellgram** را در `~/Applications` می‌سازد.

**استفاده:** با `Hellgram.app` اجرا می‌شود (اولین اجرا ~۲ دقیقه، بعدی‌ها در چند
ثانیه). با `Hellgram Stop.app` با ذخیرهٔ اسنپ‌شات خاموش می‌شود. هر بار که ریلیز
جدیدی روی گیت‌هاب بگذارید، خودکار APK تازه نصب می‌شود.

**لگ‌ها:** `~/Library/HellgramMac/launcher.log` — **حذف:** پوشهٔ
`~/Library/HellgramMac` و دو اپ داخل `~/Applications`.
