<div align="center">

<img src="docs/assets/logo.png" width="104" alt="XFiles logo">

# XFiles

**An Android file manager built for local storage, NAS media, power users, and the living room.**

> **Dual-pane on mobile. Remote-first on Google TV. SMB2/3 streaming, visual video timelines, Root/Shizuku, no ads, no telemetry.**

[![Release](https://img.shields.io/github/v/release/hglasswater-boop/XFiles?include_prereleases&sort=semver&label=release)](https://github.com/hglasswater-boop/XFiles/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#build)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)](#technology)
[![Network](https://img.shields.io/badge/network-SMB2%2F3-informational)](#privacy)

**English** · [日本語](README.ja.md) · [简体中文](README.zh-CN.md)

<img src="docs/assets/demo.gif" width="300" alt="XFiles demo">

</div>

---

## Why this fork stands out

XFiles keeps the X-plore-style tree workflow from the upstream project, then pushes it much further for NAS and video-heavy use. A saved SMB share appears beside local storage, remote videos can be inspected visually before opening them, playback can stream directly from the NAS, and the mobile edition can hand that same media to Chromecast without first downloading the whole file.

The fork is also split into purpose-built **mobile** and **Google TV** editions rather than forcing touch and remote-control UX into one compromise. Root/Shizuku access, archive browsing, package management, encrypted settings backup, and powerful file operations remain part of the same app.

## Highlights

### NAS-first SMB2/3

- Saved SMB servers live directly in the normal file tree.
- The bundled **Rust SMB engine is preferred by default**, with SMBJ retained as a compatibility fallback.
- Browse, copy, move, rename, thumbnail, storyboard and stream remote files without staging them locally first.
- Same-share moves use server-side rename where possible.
- Media random access, adaptive prefetch, rolling cache behavior and playback priority are tuned for large NAS videos.
- SMB credentials are protected with Android Keystore-backed encryption.

### See inside a video before opening it

Tap a video thumbnail to open a **storyboard timeline**. XFiles progressively extracts and caches frames across the runtime, prioritizing what is visible first.

- Configure **6 to 120 frames** in 2-frame steps.
- Configure **1 to 10 seconds** minimum spacing.
- Tap a frame to start or seek playback at that exact part of the video.
- Long-press for a finer timeline preview.
- The same timeline is available in the local player and Chromecast controller.
- Long videos are sampled across their full duration instead of concentrating previews near the start.

### A video player designed for browsing, not just playback

- Persistent storyboard between the video surface and playback controls.
- Resume position restored before player preparation.
- Double-tap left/right for **-10 / +10 seconds**.
- Vertical swipe on the right side for media volume.
- Frame counter and frame-accurate stepping inherited from XFiles.
- Picture-in-Picture with **-5 / +5 second** actions.
- Storyboard state and player controls stay out of each other's way across fullscreen and PiP transitions.

### Chromecast on mobile

The mobile edition can cast local, SMB and provider-backed media through a temporary HTTP Range relay.

- Play/pause, seek, previous/next and playlist controls.
- Coalesced rapid seeks and optimistic feedback for a snappier controller.
- SMB handle reuse and prewarming to reduce latency after seeks and item changes.
- Storyboard navigation on the Cast screen.
- Active Cast item highlighting in the browser.
- Persistent mini-player when you return to the file list.
- Notification controls for previous/next, ±10 seconds and play/pause.
- Robust handoff state so stale media metadata does not flash when changing videos.

Chromecast is intentionally **mobile-only**. The TV edition is the receiver-side browsing/playback experience and does not include the Cast stack.

### Google TV edition

XFiles TV is a separate package built specifically for a remote control:

- Leanback launcher/banner support.
- A true **single-pane** browser to reduce work and remove D-pad focus ambiguity.
- Focus-revealed left/right action rails.
- Deterministic Up/Down row navigation and focus restoration.
- Remote-first player controls: center play/pause, left/right ±10 seconds, up show controls, down hide controls, Back hides controls before exiting.
- TV-specific self-update flow.

### File operations that fit real workflows

- Dual-pane tree browsing on mobile, including archives as folders.
- Copy, move, delete, rename, new folder, ZIP creation and extraction.
- Explicit destination confirmation before copy/move.
- **Flatten one level**: move the contents of immediate child folders up and remove only folders left empty. Supported for local storage and SMB roots.
- Conflict handling with Skip, Overwrite and Keep both.
- Foreground-service execution for long operations.
- Per-folder sort overrides, dense browser display options and configurable context-menu order.

### Works with other apps

- Registered Android `ACTION_SEND` / `ACTION_SEND_MULTIPLE` share target.
- Preserves useful aggregate MIME types such as `video/*` for multi-video shares.
- External `PICK_FILES` mode can return local or SMB selections as temporary read-granted URIs without exposing SMB credentials.
- A transactional seekable SMB output bridge supports media tools that need random-access output, truncation and durable commit semantics.

### Root, Shizuku and Android package tools

- `su` access on rooted devices.
- Shizuku transport for shell-level access without root.
- Read-only safety mode for privileged paths.
- Access to paths such as `Android/data` and `Android/obb` where the active transport permits it.
- App manager with APK/split inspection and component information.
- Installs `.apk`, `.apks`, `.apkm`, `.xapk` and raw `.aab` packages, including XAPK OBB placement.

### Portable settings and built-in updates

- Password-encrypted settings export/import using **AES-256-GCM** and PBKDF2-HMAC-SHA256.
- Backups include browser settings, favorites, per-folder sort rules, file associations and saved SMB connections, including protected credentials.
- Both editions can check GitHub Releases for a newer edition-matching signed APK.
- Settings exposes automatic update checks, **Check now**, current version/build, last successful check and status.

## Mobile vs TV

| | Mobile | Google TV |
|---|---|---|
| Package | `app.local1st.files` | `app.local1st.files.tv` |
| Browser | Dual-pane tree | Single-pane remote-first tree |
| Primary input | Touch / gesture | D-pad / remote |
| Chromecast controller | Yes | No |
| Storyboard | Browser, player, Cast | Player/browser features as supported by TV UI |
| Self-update | Yes | Yes |

## Download

Grab signed builds from [**GitHub Releases**](https://github.com/hglasswater-boop/XFiles/releases).

For stable releases such as `v1.4.0-smb`:

- `XFiles-1.4.0-smb.apk` is the normal mobile edition.
- `XFiles-TV-1.4.0-smb.apk` is the Google TV edition.
- The release workflow also builds a mobile AAB as a CI artifact.

A rolling `nightly` prerelease is refreshed by pushes to `main` after the current stable tag exists.

Requires **Android 8.0 / API 26 or newer**.

## Core features

Beyond the fork-specific highlights above, XFiles includes:

- X-plore-style expandable tree navigation.
- Image viewer with pinch zoom.
- Text viewer/editor and paged hex viewer.
- Audio and Media3 video players.
- Recursive wildcard search with archive traversal.
- ZIP/JAR/APK, 7z, TAR variants and RAR browsing.
- High-performance parallel ZIP creation/extraction.
- App manager and Android package installer.
- Material 3 Expressive UI, dynamic color and edge-to-edge layout.
- Multi-language UI.

## Privacy

XFiles has **no accounts, ads or telemetry**. Network access is used for features you explicitly invoke, primarily SMB/NAS access, Chromecast on the mobile edition, and update checks.

Saved SMB passwords are encrypted through Android Keystore. Exported settings backups are encrypted before being written. You can inspect the exact declared permissions in [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml) and the edition-specific manifests.

## Technology

| Layer | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose, Material 3 Expressive |
| Android | minSdk 26, compile/target SDK 37 |
| Architecture | MVVM + StateFlow, manual DI composition root |
| SMB | Native Rust SMB2/3 engine preferred, SMBJ compatibility fallback |
| Media | Media3 ExoPlayer, Coil 3, mobile Media3 Cast integration |
| Persistence | DataStore Preferences, Android Keystore-backed SMB secrets |
| Privileged access | Shizuku + `su` |
| Archives | java.util.zip, commons-compress, xz, junrar |
| Package install | PackageInstaller, vendored bundletool, ARSCLib |
| Settings backup | AES-256-GCM, PBKDF2-HMAC-SHA256 |

The Rust Android JNI build is validated for arm64-v8a, armeabi-v7a and x86_64 before normal APK assembly.

## Build

JDK 17+ and Android SDK platform 37 are required.

```bash
./gradlew :app:assembleMobileDebug :app:assembleTvDebug
```

Outputs:

```text
app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
app/build/outputs/apk/tv/debug/app-tv-debug.apk
```

Release CI builds signed mobile/TV APKs plus a mobile AAB. Debug CI runs both unit-test variants, builds both signed debug editions, verifies the Rust JNI layer, and runs an API 35 Android Emulator launch/lifecycle smoke test.

## Project lineage

This repository is a personal fork based on [Local1stDotApp/XFiles](https://github.com/Local1stDotApp/XFiles). The upstream project and contributors created the foundation this fork builds on. The NAS/media, Chromecast, Google TV, storyboard, update and integration work described above intentionally diverges from upstream behavior.

## License

[GPL-3.0-only](LICENSE). If you distribute a modified XFiles, distribute its corresponding source under the license terms as well.
