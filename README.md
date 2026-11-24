# Antigravity for Android (Unofficial Standalone Client)

[![Release](https://img.shields.io/github/v/release/m0m0d600c-design/antigravity-android?color=3B82F6&label=release)](https://github.com/m0m0d600c-design/antigravity-android/releases) [![Build](https://github.com/m0m0d600c-design/antigravity-android/actions/workflows/build-release.yml/badge.svg)](https://github.com/m0m0d600c-design/antigravity-android/actions/workflows/build-release.yml) ![ABI](https://img.shields.io/badge/ABI-arm64--v8a-14171D) ![License](https://img.shields.io/badge/license-GPL--3.0-22C55E)

A self-contained, ARM64-only Android wrapper around `termux-app` that embeds a native Linux runtime, runs Google Antigravity (`agy --hub`) silently in the background, and opens directly into the fullscreen Antigravity Web GUI—with zero external Termux app dependency, native microphone permissions for Antigravity's built-in speech-to-text, job-completion notifications, and 1-tap offline `.tar.zst` backup & migration.

> **Disclaimer:** This is an independent, unofficial open-source project and is not affiliated with, endorsed by, or sponsored by Google LLC or the Termux project.

---

## Key Features

- **100% Self-Contained ARM64 Runtime:** Embeds `bootstrap-aarch64.zip`, PTY, and foreground `TermuxService` inside a single ~36 MB APK—no external Termux app or `RUN_COMMAND` intents required.
- **Fullscreen Antigravity Web GUI:** Launches straight into `http://localhost:4400/#t=<token>` with dark `#0B0D10` zero-flash startup, OAuth external browser handoff (`accounts.google.com`), native file/image uploads, and double-back-to-exit protection.
- **Native Mic Support for Antigravity STT:** Implements strict origin-checked `WebChromeClient.onPermissionRequest` (`localhost` / `127.0.0.1` audio-only) so Antigravity's built-in speech-to-text works directly inside the WebView.
- **Agent Job-Done Notifications:** Sends high-priority Android notifications when a background agent task finishes (via `window.Notification` WebView bridge, debounced DOM state observer, and authenticated `POST 127.0.0.1:4401/notify` hook).
- **Hidden Built-in Developer Terminal:** Accessible anytime from the Control Center with a slim 1-row extra-keys bar (`ESC`, `TAB`, sticky `CTRL`/`ALT`, arrows, `/`, `-`, `|`, `~`) and `A-` / `A+` font zoom.
- **1-Tap Offline `.tar.zst` Backup & Termux Migration:** Bundles a native ARM64 multithreaded `zstd` 1.5.7 binary (`libzstd_cli.so`) to verify (`zstd -t`), back up (Full or Packages-Only), and restore environments 100% offline with live MB/percentage progress.
- **Daily-Driver QoL:** Draggable snap-to-edge floating pill with persistent position, Share-to-Antigravity (`ACTION_SEND`) inbox, Keep Screen Awake & CPU Wake Lock toggles, live Hub log viewer, and automatic background Hub recovery on resume.

---

## Important Note About `com.termux`

To run prebuilt Termux packages (`pkg`, `python3`, `nodejs`, `git`, `clang`, `rust`) and the native `agy` binary at 100% speed on Android 12+ without `proot` or root, this APK intentionally uses `applicationId = "com.termux"` and `targetSdkVersion = 28`.

Because Android does not allow two apps with the same package ID signed by different keys, **you must uninstall stock Termux before installing this APK**. If you already have libraries/packages in stock Termux, see the 1-command migration guide below so you don't lose anything.

---

## Installation

### Option A: Fresh Install (No Backup Needed)
1. Download the full `AntigravityStandalone-v1.3.0-arm64.apk` from **Releases** and install it on an `arm64-v8a` Android device.
2. Open **Antigravity** and grant **All Files Access** and **Disable Battery Optimization** on the setup card.
3. Tap **Install Antigravity Automatically** on the onboarding screen—it installs `curl`, `python`, and `agy` inside the embedded Linux environment and boots straight into the GUI.

### Option B: Port Your Existing Termux Libraries & `agy` Setup
If you already have stock Termux installed with your packages, Python/Node toolchains, or `agy`:

1. **Before uninstalling stock Termux**, paste and run this command inside Termux to pack your entire environment into a verified `.tar.zst` archive:
   ```bash
   pkg install -y zstd tar && tar -cf - -C /data/data/com.termux/files --exclude='./usr/var/cache/*' --exclude='./usr/tmp/*' ./usr ./home | zstd -6 -T2 -o /sdcard/Download/termux-port.tar.zst && zstd -t /sdcard/Download/termux-port.tar.zst
   ```
2. Uninstall stock Termux (and any Termux add-ons sharing `com.termux`).
3. Install `AntigravityStandalone-v1.3.0-arm64.apk`, open **Antigravity**, and grant **All Files Access**.
4. Tap **Import Offline Backup / Port Termux (.tar.zst)**—it automatically detects `/sdcard/Download/termux-port.tar.zst` (or `termux-agy-master.tar.zst`), verifies integrity, restores all your libraries and home files 100% offline, and boots straight into Antigravity.

---

## Security Architecture

- **Authenticated Local Bridge (`127.0.0.1:4401`):** Protected by a per-install 256-bit random token stored at `~/.agy_bridge/bridge.token` (`0600` permissions) sent via `X-Bridge-Token`, plus strict `Origin` header validation.
- **Scoped Cleartext & WebView Hardening:** Cleartext HTTP is restricted exclusively to `localhost` and `127.0.0.1` via `network_security_config.xml`; `allowFileAccess` is disabled; WebView `onPermissionRequest` verifies `localhost`/`127.0.0.1` origin and grants `RESOURCE_AUDIO_CAPTURE` only.
- **Concurrency-Safe Restore:** Backup and restore pipelines use an `AtomicBoolean` lock and `zstd -t` integrity verification before touching `/data/data/com.termux/files`.

---

## Tested Hardware

| Device | OS / Skin | ABI | Status |
| :--- | :--- | :--- | :--- |
| Xiaomi POCO X3 NFC | Android 12 / MIUI 14 | `arm64-v8a` | Verified (Web GUI, mic STT, notifications, offline `.tar.zst` restore, background wake lock) |

---

## Building from Source

Requires **OpenJDK 17**, **Android SDK (API 36)**, **Build-Tools 36.0.0**, and **Android NDK 29.0.14206865**.

```bash
# 1. Cross-compile arm64-v8a libzstd_cli.so (see .github/workflows/build-release.yml for exact flags)
# 2. Download bootstrap-aarch64.zip into app/src/main/cpp/ (automatic via Gradle downloadBootstraps task)

# Build Full Standalone APK (~36 MB):
./gradlew :app:assembleRelease

# Build Lite In-Place Update APK (~3 MB, for existing installs):
./gradlew :app:assembleRelease -PagyLiteUpdate=true
```

To sign release builds, export:
```bash
export AGY_KEYSTORE_PATH=/path/to/your-release.jks
export AGY_KEYSTORE_PASSWORD=your-store-password
export AGY_KEY_ALIAS=your-alias
export AGY_KEY_PASSWORD=your-key-password
```

---

## Transparency Note & License

- **Development Note:** This project was built with heavy AI assistance and tested on real hardware (POCO X3 NFC, Android 12 / MIUI 14). The entire source code and CI build pipeline are open—issues, security audits, and PRs are welcome.
- **License:** Forked from [termux/termux-app](https://github.com/termux/termux-app) and licensed under **GNU General Public License v3.0 (GPL-3.0)**.
