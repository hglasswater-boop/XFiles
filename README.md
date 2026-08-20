<div align="center">

<img src="docs/assets/logo.png" width="104" alt="XFiles logo">

# XFiles

**An open-source Android file manager with X-plore's workflow** — dual-pane tree
browsing, archive-as-folder, app manager, APK/AAB/XAPK install, root & Shizuku access,
and SMB2/3 network shares — on the latest Android stack with a Material 3 Expressive UI.

> **Dual-pane file manager with SMB/NAS, Root & Shizuku. No ads or telemetry.**

[![Release](https://img.shields.io/github/v/release/hglasswater-boop/XFiles?include_prereleases&sort=semver&label=release)](https://github.com/hglasswater-boop/XFiles/releases)
[![License](https://img.shields.io/badge/license-GPL--3.0--only-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84?logo=android&logoColor=white)](#build--run)
[![Kotlin](https://img.shields.io/badge/Kotlin-Compose-7F52FF?logo=kotlin&logoColor=white)](#tech-stack)
[![Network](https://img.shields.io/badge/network-SMB2%2F3%20only-informational)](#permissions--privacy)

English · [日本語](README.ja.md) · [简体中文](README.zh-CN.md)

<img src="docs/assets/demo.gif" width="300" alt="One tour on a OnePlus 7 Pro: copy files, inspect an installed app's components and APK splits, then browse the root-only directories under /data">

<sub>Real capture on a OnePlus 7 Pro (Android 16), sped up. One run: <b>file copy</b> → <b>App manager</b> (an app's components &amp; APK splits) → <b>Root</b> (the real filesystem, <code>/data</code> and all).</sub>

</div>

---

## This fork

This repository is a personal fork of
[Local1stDotApp/XFiles](https://github.com/Local1stDotApp/XFiles). The upstream project
is kept as the base, while this fork adds the following customizations for day-to-day
use:

### Acknowledgements

This fork would not exist without the original
[Local1stDotApp/XFiles](https://github.com/Local1stDotApp/XFiles). Many thanks to the
original author and contributors for creating XFiles and making it available as open
source. Their work provided the foundation that made these personal customizations
possible.

### SMB2/3 network shares

- Added a native **SMB2/SMB3 filesystem** using SMBJ and exposed saved servers directly
  in the normal XFiles tree.
- SMB connections support a display name, host, share, optional start path, username,
  password, domain and custom port.
- Saved SMB passwords are protected with the **Android Keystore** instead of being kept
  as plain text.
- Servers can be **added, tested, edited, duplicated and deleted** from the browser UI.
  The configured server name is also used in breadcrumbs.
- SMB folders participate in normal XFiles operations and can be used as copy/move
  destinations.
- Remote images and videos get thumbnails. Video thumbnail extraction uses seekable SMB
  access, avoids black first frames where possible, and prefers embedded cover art when
  available.
- Media can be played **directly from SMB** through Media3 without first downloading the
  entire file. Random-access reads, readahead and seek handling were tuned for remote
  playback.

### Per-folder sorting

- Sort settings can be saved **per folder** instead of relying only on one global sort
  order.
- A long-press on the breadcrumb opens the folder sort dialog.
- Each override stores the sort key, direction and folders-first behavior, and falls
  back to the global setting when no override exists.

### Browser display customization

- Added browser display settings for **thumbnail size**, filename wrapping and maximum
  visible tree depth.
- Filename display supports compact multi-line modes, including **three-line wrapping**.
- Tree guides were adjusted for variable-height rows and deep hierarchies.
- Folder rows show their direct child counts using separate **folder and file icons with
  counts**, rather than a combined text label.
- Browser rows were made denser so more files fit on screen while keeping thumbnails
  usable.

### Copy / move destination confirmation

- Copy and move from a selection no longer start immediately when the toolbar action is tapped.
- The destination picker can jump to either the **source pane's current folder** or the
  **other pane's current folder**, then browse deeper before committing the operation.
- The transfer starts only after tapping **Copy here** / **Move here**, which prevents accidental
  transfers to the wrong pane or folder.

### Video player improvements

- Double-tap the left/right half of the video to seek **-10 / +10 seconds**.
- Vertical swipe on the right half adjusts media volume without opening the Android
  system volume panel.
- SMB playback uses faster seek/read behavior to make large remote videos more usable.

### Portable settings backup

- Added **Settings export / import** for moving this fork's configuration between
  devices.
- Backups include normal app settings, favorites, browser display options, per-folder
  sort overrides, file associations and **saved SMB connections including passwords**.
- The backup is password-protected using **AES-256-GCM** with a key derived using
  **PBKDF2-HMAC-SHA256 (200,000 iterations)**.
- On import, SMB passwords are written back through the destination device's Android
  Keystore protection.

### Build and CI changes

- Added a reusable **Debug CI** workflow that runs on pushes to any branch and can also
  be started manually.
- Debug APKs use the configured stable signing key, so test builds can update an
  installed copy instead of behaving like unrelated debug apps.
- Release builds run on GitHub-hosted runners and stale builds are cancelled when a
  newer push supersedes them.

These changes intentionally diverge from upstream behavior. When comparing bugs or
features with the original project, check whether the behavior is listed above first.

## Why

- **X-plore broke on [Waydroid](https://waydro.id)** over the past half-year; I needed a replacement.
- **It's the LLM era** — if a tool doesn't fit, build your own.
- **Software with dangerous root powers should be open-source and collect nothing.**
  This fork has no analytics, accounts or ads. Network access is used for SMB shares
  explicitly configured by the user.

## Download

Grab an APK from [**Releases**](https://github.com/hglasswater-boop/XFiles/releases):

- **`vX.Y`** — stable, cut whenever `versionName` is bumped.
- **`nightly`** — a single rolling prerelease, refreshed on every push to `main`.

Requires **Android 8.0 (API 26)** or newer. On first launch, grant *All files access*
(the app deep-links to the system page). Or [build it yourself](#build--run).

## Features

### Dual-pane tree browser

X-plore's signature: two independent panes — side-by-side on wide screens, a swipeable
pager on phones. Folders expand **in place** as a tree with indent guide lines, and each
pane carries its own floating breadcrumb pill. On phones, a target chip at the top keeps
the hidden pane's folder visible and switches to that pane when tapped.

Archives sit in the tree like any other folder — the breadcrumb descends straight into
`project.zip`.

### SMB2/3 network shares

Saved SMB servers appear alongside local storage in the tree. Browse shares, use a
configured subdirectory as the starting point, copy files to/from remote folders, show
remote media thumbnails, and stream video directly through the built-in Media3 player.
Connection passwords are stored with Android Keystore protection.

### Thumbnails in the tree

Images and video poster frames render inline for local files and SMB shares. Video
frames are extracted at thumbnail size and cached, with a play badge and an icon
fallback while loading. When media contains embedded cover art, the fork prefers it as
the thumbnail.

### File operations

Multi-select via right-edge checkmarks. **The other pane is the destination** for
copy, move, zip and extract: set its folder, return to the source pane, then run the
operation directly. `Copy to…`/`Move to…` in the long-press menu remain available when
you want a one-off explicit destination. Plus delete, rename, new folder. On Android
8–10, writes to SD cards and other secondary volumes work through a one-time SAF grant.

A background engine drives it all with progress (the wavy Expressive indicator),
cancellation, and Skip / Overwrite / Keep-both conflict resolution.

### High-performance zip

Creation deflates every entry across all CPU cores (commons-compress
`ParallelScatterZipCreator`, STORE for already-compressed media). Extraction runs one
`ZipFile` handle per worker off a shared queue. Zip-Slip guarded; falls back to
single-threaded streaming when temp space is tight.

### Foreground service

Long copy/move/zip/extract operations keep running when the app is backgrounded, shown
in an ongoing notification with a Cancel action and a wake lock. The service self-stops
when idle.

### Archives as folders

Browse zip/jar/apk, 7z, tar(.gz/.bz2/.xz) and rar read-only; extract by copying out.
Anything installable installs from right there — see below.

### App manager

Installed and system apps in two groups, with real icons, version/package badges and
rich details. Install, launch, uninstall, or copy an APK out to share an app as a file.

Expand an app and you get everything that belongs to it in one place: a **Components**
node broken down into activities / providers / receivers / services, plus `base.apk` and
every `split_config.*` APK — each one expandable, because an APK is just a zip.

Drill into a category and each component shows its class name and its real manifest
state — `exported` / `not exported`, `enabled` / `disabled`. Launch activities, create
shortcuts, and enable/disable components where the system permits.

### Package installer

APKs install with a tap — and so does everything APK-shaped: split bundles
(`.apks` / `.apkm` / `.xapk`, with XAPK **OBB** expansion files placed where the game
expects them) and even raw **`.aab`** files. A vendored
[bundletool](https://github.com/google/bundletool) converts the bundle on the phone
into split APKs matched to the device and signs them with a built-in certificate — no
PC, no Play Store. Installs run in the foreground service, so backgrounding the app
mid-install doesn't kill the session.

### Root & Shizuku

On by default. A **Root** entry (`/`) sits with the storage roots — turn **Root access**
off in Settings to hide it. A separate **Read-only** switch (also on by default) blocks
anything needing privilege to write, so you can go look without being able to break your
system.

Two interchangeable transports power it, and Settings lets you pick (or leave it on
auto):

- **`su`** — full superuser on rooted devices. `/data` opens up to `adb`, `anr`, `app`,
  `app-private`, `dalvik-cache` — directories a normal app can't even list — with
  list/read/write/mkdir/rename/delete under `/data`, `/system`, …
- **[Shizuku](https://shizuku.rikka.app/)** — no root required: XFiles binds a Shizuku
  user service running at shell (ADB) privilege that hands over real file descriptors.
  Settings walks you through setup and permission.

Whichever transport is live also kicks in transparently where plain file access is
denied — most notably **`Android/data`** and **`Android/obb`**, which open like any
other folder. Thumbnails, viewers and even video playback work on privileged paths.
Opening **Root** uses `su` when it is available. Without it, Shizuku can still
list `/` and browse what the adb shell can see (`/system`, `/proc`, `/storage`,
`Android/data`). `/data` and `/data/data` stay closed until superuser is granted.

The settings screen carries the rest of the preferences too — theme, dynamic color,
hidden files, folders-first, sort key and direction, browser display configuration and
portable settings backup/restore.

### Viewers

An image viewer (pager + pinch zoom), a text viewer with edit/save, a hex viewer with
on-demand paging, an audio player, and a custom video player (Media3/ExoPlayer) with
**frame-accurate stepping**.

Tap the time readout to swap it for a frame counter — current frame, total, and the real
frame rate — then step ±1 frame, swipe on the picture to scrub by time or frames with
live preview, drag the compact control card out of the way, or go fullscreen immersive.
This fork also adds left/right **double-tap ±10-second seeking** and a right-side
vertical swipe for media volume.

### Search

Live streaming recursive search with `*`/`?` wildcards. Descends into archives, and
reveals results in the tree on tap.

### Open from other apps

Off by default so XFiles never hijacks anything. Three opt-in toggles in Settings
register XFiles with the system resolver for **archives**, **images** and **videos** —
after that, "open with XFiles" from any app lands in the archive tree or the right
viewer.

### Material 3 Expressive

`MaterialExpressiveTheme` + expressive motion, dynamic color (Android 12+),
light/dark/system, floating toolbar, `LoadingIndicator` / `LinearWavyProgressIndicator`.
True edge-to-edge: no top app bar — content scrolls under the status bar behind a
gradient scrim, with floating breadcrumb and settings buttons.

### 18 languages

The UI follows the system language: English plus Arabic, Chinese (Simplified and
Traditional), Dutch, French, German, Hindi, Indonesian, Italian, Japanese, Korean,
Polish, Portuguese (Brazil), Russian, Spanish, Turkish and Vietnamese.

## Permissions & privacy

No telemetry, no accounts and no ads. This fork requests network access only because it
supports SMB2/SMB3 shares configured by the user. Every permission the app declares,
and why:

| Permission | Why |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | Browse and modify all of shared storage — the whole point of an X-plore-style manager |
| `READ_EXTERNAL_STORAGE` *(≤ API 32)* | Legacy read path on older Android |
| `WRITE_EXTERNAL_STORAGE` *(≤ API 29)* | Legacy write path on older Android |
| `QUERY_ALL_PACKAGES` | The App manager lists what is installed |
| `REQUEST_DELETE_PACKAGES` | Uninstall from the App manager |
| `REQUEST_INSTALL_PACKAGES` | The package installer: APKs, split bundles (`.apks`/`.apkm`/`.xapk`), AABs |
| `POST_NOTIFICATIONS` | Show the progress notification for long operations |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep a copy/move or install running when backgrounded |
| `WAKE_LOCK` | Don't sleep mid-operation |
| **`INTERNET`** | Connect to SMB2/SMB3 servers explicitly configured by the user |

SMB credentials stay on the device and saved passwords are protected using Android
Keystore-backed encryption. Settings backup files can include SMB credentials, but the
entire export is password-encrypted before it is written.

Verify the declared permissions yourself in
[`AndroidManifest.xml`](app/src/main/AndroidManifest.xml) or with
`aapt dump permissions` on the APK.

## Tech stack

| Layer | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose (BOM 2026.06.01), material3 **1.5.0-alpha23** (Expressive APIs) |
| Build | AGP 9.2.1 (built-in Kotlin, no KGP), Gradle 9.4.1, compileSdk 37 / target 37 / min 26 |
| Architecture | MVVM + StateFlow, manual DI composition root (`di/Graph`); app module + a shaded bundletool vendor module |
| Persistence | DataStore Preferences; Android Keystore-backed SMB secrets |
| Network shares | SMBJ, SMB2/SMB3, seekable random access for remote media |
| Media/Images | Coil 3 (GIF, local/SMB image fetchers, disk-cached video thumbnails), Media3 ExoPlayer |
| Archives | java.util.zip, commons-compress (+xz), junrar |
| Privileged access | Shizuku 13.1.5 (user service, real fds) · `su` shell |
| Package install | PackageInstaller sessions · vendored bundletool 1.18.3 · ARSCLib (in-process aapt2) · minimal self-signed signer |
| Settings backup | AES-256-GCM · PBKDF2-HMAC-SHA256 (200,000 iterations) |

Note: material3 is pinned to `1.5.0-alpha23` because the Expressive APIs are
`internal` in the 1.4.0 stable release.

## Project layout

```
app/src/main/java/app/local1st/files/
├── core/
│   ├── fs/        XEntry model, XId id scheme, XFileSystem + FsRegistry,
│   │   │          Local/Archive/Apps/Root/SMB filesystems, storage roots,
│   │   │          SMB random access, legacy SAF writes
│   │   └── priv/  privileged transports — su shell & Shizuku user service (real fds)
│   ├── ops/       OperationEngine (copy/move/delete/compress + conflicts), OpsService
│   ├── search/    recursive SearchEngine
│   ├── prefs/     DataStore settings, per-folder sorting, SMB connections,
│   │              encrypted settings backup
│   ├── thumb/     Coil fetchers: app icons, local/SMB images and video thumbnails
│   └── util/      formatters, mime/category mapping, intents; package install —
│                  PackageInstaller sessions, AAB→APKs (bundletool), XAPK/OBB,
│                  in-process aapt2 (ARSCLib), self-signed signing
├── di/            Graph (composition root) + GraphInit wiring
└── ui/
    ├── browser/   PaneController (tree state machine), PaneView, EntryRow,
    │              breadcrumb folder sorting and child-count badges
    ├── components/ shared Compose bits (tooltips, predictive back)
    ├── main/      MainViewModel, MainScreen (dual pane + floating toolbar), PermissionGate
    ├── dialogs/   rename/new-folder/delete/zip/details/SMB, ops progress + conflicts
    ├── viewer/    image / text / hex viewers, audio player, frame-accurate video player,
    │              SMB Media3 data source
    ├── search/    search overlay
    ├── settings/  settings screen + browser display / backup controls
    ├── appinfo/   app details overlay
    └── theme/     MaterialExpressiveTheme setup

vendor/bundletool-shaded/   Gradle module shading bundletool 1.18.3 + its pinned deps
```

Entry ids are URI-like strings for local files, archives, apps, root access and SMB
connections.

## Build & run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and an Android SDK with platform 37. On first launch grant
"All files access" (the app deep-links to the system page).

## Releases

A **GitHub-hosted** GitHub Actions workflow
([`.github/workflows/release.yml`](.github/workflows/release.yml)) builds a signed APK
on every push to `main`:

- The build number (`versionCode`) increments each run (`github.run_number`).
- `versionName` lives in `version.properties`. While it's unchanged, each push just
  refreshes a single rolling **`nightly`** prerelease with the latest build. Bump
  `versionName` to cut a new stable `vX.Y` release.
- Signing keys/passwords come from repo secrets: `KEYSTORE_BASE64`,
  `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
- [`.github/workflows/personal-fork-ci.yml`](.github/workflows/personal-fork-ci.yml)
  provides a signed Debug CI build for feature branches and manual test builds.

## License

[GPL-3.0-only](LICENSE). A file manager that can be handed root deserves a licence that
keeps every future copy open — if you ship a modified XFiles, ship its source too.

---

*This is a study/clone project inspired by X-plore File Manager; it shares no
code or assets with the original.*