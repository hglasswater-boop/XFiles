# USB drive file-opening investigation (Issue #281)

## Report and scope

On 2026-11-14 a user reported that an attached USB drive can be browsed in XFiles,
but files selected from that drive do not open. The file type, failure UI, Android
exception, and whether other file managers can read the same files are **unknown**.
Do not claim a specific permission, filesystem, URI or codec is the cause until a
reproduction or device log establishes it.

## Current implementation (main at 970d9d4)

- `DefaultRootsRepository` discovers mounted volumes from
  `StorageManager.storageVolumes` and exposes a `file://` entry at
  `StorageVolume.directory` (API 30+) or the legacy resolved volume path.
- `LocalFileSystem.list` calls `File.listFiles()`. Child entries carry
  `file://` IDs and `localPath`. `LocalFileSystem.openIn` uses
  `FileInputStream`; successful directory enumeration does **not** verify that
  reading a child's bytes will succeed.
- `MainViewModel.openEntry` chooses the viewer by file category.
  Video/audio playback hands a local `file://` URI to Media3; images use Coil's
  `File` source; text and hex readers use local file/stream reads. Generic
  files may be shared via `FileProvider`, which already has a `root-path`.
- `PermissionGate` checks `Environment.isExternalStorageManager()` on API
  30+ before the browser appears. Android documents that all-files access
  includes USB OTG roots; it does not override all restricted application
  directories. Reference:
  https://developer.android.com/training/data-storage/manage-all-files
- The existing `LegacySafAccess` is a **write-only**, secondary-volume fallback
  for API 26-29. It is not a general USB read bridge and is not initialized
  on Android 11+.

## Failure reporting contract

- A Media3 decoder failure remains a codec-compatibility problem and offers
  the existing `Open with` action.
- A Media3 **file-source I/O failure** (not-found or permission denied) must
  not leave a blank video screen or be misreported as an unsupported codec.
  Show a readable failure and allow closing the viewer or trying `Open with`
  where XFiles can expose the entry.
- Do not hide other errors, silently copy full USB videos to cache, or
  change SMB / internal-storage source routing to work around an unverified
  USB-only problem.
- Do **not** close the issue until an actual USB drive can open at least one
  image, text and video file on the affected device, or narrower observed
  conditions justify an explicit revised acceptance test.

## Test-first work

1. Pure unit test: I/O permission / file-not-found errors classify as
   read failures; decoder failures classify as codec failures; unrelated
   playback failures stay generic.
2. Show a distinct readable UI for I/O playback errors; preserve existing
   decoder fallback and ordinary playback.
3. USB physical-device matrix: note Android version, drive label / filesystem
   where available, `MANAGE_EXTERNAL_STORAGE` grant state, original file
   extension, exact error, and whether the system Files app opens the same
   file. Repeat with .txt, .png and .mp4.
4. Check no regressions for internal files and SMB playback, plus external
   `FileProvider` handoff. Run unit tests, compile Android app and review
   relevant workflows.

This document records known code behavior and the regression contract; it
does not assert that the reporting change alone fixes USB file access.
