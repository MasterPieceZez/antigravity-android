# Antigravity for Android

[![Release](https://img.shields.io/github/v/release/MasterPieceZez/antigravity-android?color=3B82F6&label=release&v=1.0.0)](https://github.com/MasterPieceZez/antigravity-android/releases) [![Build](https://github.com/MasterPieceZez/antigravity-android/actions/workflows/build-release.yml/badge.svg?branch=main&v=1.0.0)](https://github.com/MasterPieceZez/antigravity-android/actions/workflows/build-release.yml) ![ABI](https://img.shields.io/badge/ABI-universal%20%7C%20arm64--v8a%20%7C%20armeabi--v7a%20%7C%20x86__64%20%7C%20x86-14171D) ![License](https://img.shields.io/badge/license-GPL--3.0-22C55E)

An unofficial, standalone Android client for Google Antigravity (`agy`). It bundles the Termux Linux bootstrap directly inside the APK, runs `agy --hub` locally on-device with zero PRoot/root overhead, and launches straight into a hardware-accelerated fullscreen Antigravity Web GUI.

> **Disclaimer:** Independent open-source project not affiliated with, endorsed by, or sponsored by Google LLC or the Termux project.

## Features

- **100% Self-Contained Runtime:** Embeds the Termux Linux bootstrap, PTY, and foreground `TermuxService` inside one APK, with no external Termux app required.
- **Hardware-Accelerated Fullscreen Web Hub:** GPU-composited WebView with `RENDERER_PRIORITY_IMPORTANT`, `offscreenPreRaster`, instant background Hub pre-warming, OAuth browser handoff, native file/image uploads, and double-back-to-exit protection.
- **Native Mic Support for Antigravity STT:** Origin-checked `WebChromeClient.onPermissionRequest` (`localhost` / `127.0.0.1` audio-only) so Antigravity's built-in speech-to-text works seamlessly.
- **Agent Job-Done Notifications:** High-priority Android alerts when a background agent task finishes.
- **Hidden Built-in Terminal:** Accessible anytime from the Control Center with a 1-row extra-keys bar (`ESC`, `TAB`, sticky `CTRL`/`ALT`, arrows, `/`, `-`, `|`, `~`) and `A-`/`A+` font zoom.
- **1-Tap Offline `.tar.zst` Backup & Termux Porting:** Bundled native multithreaded `zstd` 1.5.7 across all ABIs for offline backup, `zstd -t` verification, and 1-command migration from stock Termux.
- **Daily-Driver QoL:** Persistent snap-to-edge floating pill, Share-to-Antigravity (`ACTION_SEND`) inbox, Keep Screen Awake & CPU Wake Lock toggles, live Hub log viewer, and automatic MIUI background recovery.

## Installation

### Option A: Fresh Install
1. Download `AntigravityStandalone-v1.0.0-arm64-v8a.apk` (or `universal`) from [Releases](https://github.com/MasterPieceZez/antigravity-android/releases).
2. Open **Antigravity**, grant **All Files Access** & **Disable Battery Optimization**, and tap **Install Antigravity Automatically**. (Uses isolated package ID `com.agy.os`, so it runs side-by-side with stock Termux and you don't need to uninstall Termux.)

### Option B: Port Your Existing Termux Environment
In your existing stock Termux, run:
```bash
pkg install -y zstd tar && tar -cf - -C /data/data/com.termux/files --exclude='./usr/var/cache/*' --exclude='./usr/tmp/*' ./usr ./home | zstd -6 -T2 -o /sdcard/Download/termux-port.tar.zst && zstd -t /sdcard/Download/termux-port.tar.zst
```
Then open **Antigravity** and tap **Import Backup / Port Termux (.tar.zst)** to migrate your packages and home files offline (paths are automatically remapped to `com.agy.os`).

## Security
- Local bridge (`127.0.0.1:4401`) authenticated by a per-install `0600` random secret (`~/.agy_bridge/bridge.token`) + strict `Origin` checks.
- Cleartext HTTP scoped exclusively to `localhost` and `127.0.0.1` via `network_security_config.xml`; `allowFileAccess` disabled.

## License & Transparency
- Licensed under **GNU General Public License v3.0 (GPL-3.0)** (forked from [termux/termux-app](https://github.com/termux/termux-app)).
- Developed with AI assistance and tested on real hardware (Xiaomi POCO X3 NFC, Android 12 / MIUI 14).
