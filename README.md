# Antigravity for Android

[![Release](https://img.shields.io/github/v/release/MasterPieceZez/antigravity-android?color=3B82F6&label=release&v=1.0.2)](https://github.com/MasterPieceZez/antigravity-android/releases) [![Build](https://img.shields.io/github/actions/workflows/build-release.yml/badge.svg?branch=main&v=1.0.2)](https://github.com/MasterPieceZez/antigravity-android/actions/workflows/build-release.yml) ![ABI](https://img.shields.io/badge/ABI-universal%20%7C%20arm64--v8a%20%7C%20armeabi--v7a%20%7C%20x86__64%20%7C%20x86-14171D) ![License](https://img.shields.io/badge/license-GPL--3.0-22C55E)

An unofficial, standalone Android client for Google Antigravity (`agy`). It bundles the Termux Linux bootstrap inside the APK so `agy` runs locally on-device without needing a separate Termux install or `proot`.

> This is an independent open-source project and is not affiliated with or endorsed by Google LLC or the Termux project.

## UI Modes

You can switch between two local interfaces from the Control Center:

- **Native ACP UI (Jetpack Compose):** Spawns `agy acp` directly inside the embedded Termux prefix over stdio JSON-RPC 2.0. Supports streaming Markdown with syntax-highlighted code blocks, inline unified diffs, collapsible reasoning and tool-call cards, interactive permission approvals, session switching (`session/list` / `session/resume`), mid-turn steering and cancellation, native Android speech-to-text, and background notifications when a turn finishes or needs approval.
- **Web Hub (`agy --hub`):** The existing fullscreen WebView connected to `http://localhost:4400/#t=<token>` with external browser OAuth handoff, `localhost`-scoped microphone access for Antigravity's built-in STT, file/image uploads, and job-completion alerts via a local token-authenticated bridge (`127.0.0.1:4401`).

Both modes share the same embedded Linux environment, built-in developer terminal, wake-lock toggles, and offline `.tar.zst` backup/restore tools.

## About the `com.termux` Package ID

To run prebuilt Termux packages (`pkg`, `python3`, `nodejs`, `git`, `clang`, `rust`) and the native `agy` binary directly on Android 12+ without `proot` or root, this app uses `applicationId = "com.termux"` and `targetSdkVersion = 28`.

Because Android won't install two apps with the same package ID signed by different keys, **uninstall stock Termux before installing this APK**. If you want to keep your existing Termux setup, use the `.tar.zst` migration step below first.

## Installation

### Fresh install
1. Download `AntigravityStandalone-v1.0.1-<arch>.apk` for your device (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) or `AntigravityStandalone-v1.0.1-universal.apk` from [Releases](https://github.com/MasterPieceZez/antigravity-android/releases).
2. Open **Antigravity** and grant **All Files Access** and **Disable Battery Optimization** when prompted.
3. Tap **Install Antigravity Automatically** on the onboarding screen to install `curl`, `python`, and `agy` into the embedded environment.

### Updating an existing install
- Download `AntigravityUpdate-v1.0.1-universal-lite.apk` from [Releases](https://github.com/MasterPieceZez/antigravity-android/releases) and install it over your existing Antigravity installation (preserves `/data/data/com.termux/files/usr` and `home`).

### Migrating from stock Termux
1. Before uninstalling stock Termux, back up your prefix and home directory to a verified `.tar.zst` archive:
   ```bash
   pkg install -y zstd tar && tar -cf - -C /data/data/com.termux/files --exclude='./usr/var/cache/*' --exclude='./usr/tmp/*' ./usr ./home | zstd -6 -T2 -o /sdcard/Download/termux-port.tar.zst && zstd -t /sdcard/Download/termux-port.tar.zst
   ```
2. Uninstall stock Termux (and any Termux add-ons sharing `com.termux`).
3. Install `AntigravityStandalone-v1.0.1-<arch>.apk` (or `universal`), open **Antigravity**, and grant **All Files Access**.
4. Tap **Import Offline Backup / Port Termux (.tar.zst)** to verify and restore `/sdcard/Download/termux-port.tar.zst` offline.

## Building from Source

Requires **OpenJDK 17**, **Android SDK (API 36)**, **Build-Tools 36.0.0**, and **Android NDK 29.0.14206865**.

```bash
# 1. Cross-compile libzstd_cli.so for target ABIs (see .github/workflows/build-release.yml for exact flags)
# 2. Bootstraps download automatically into app/src/main/cpp/ via the Gradle downloadBootstraps task

# Standalone multi-arch + universal APKs:
./gradlew :app:assembleRelease

# Lite in-place update APK (for existing installs with /data/data/com.termux/files/usr already populated):
./gradlew :app:assembleRelease -PagyLiteUpdate=true
```

Optional release signing environment variables:
```bash
export AGY_KEYSTORE_PATH=/path/to/release.jks
export AGY_KEYSTORE_PASSWORD=...
export AGY_KEY_ALIAS=...
export AGY_KEY_PASSWORD=...
```

## Tested Hardware

- **Xiaomi POCO X3 NFC** — Android 12 / MIUI 14 (`arm64-v8a`)

## License & notes

Forked from [termux/termux-app](https://github.com/termux/termux-app) under **GPL-3.0**. Most of the glue code and UI in this repo was put together with AI assistance and tested on real hardware; PRs and fixes are welcome.
