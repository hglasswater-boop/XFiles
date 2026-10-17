# Android gallery visibility after file operations

Issue: [#223](https://github.com/hglasswater-boop/XFiles/issues/223)

## Background

XFiles streams NAS and archive files into local files with `LocalFileSystem.openOut()`. A successful write to the filesystem does **not** by itself guarantee that Android's MediaStore has indexed the file, so Samsung Gallery and other MediaStore-based apps may not see newly copied PNG images.

## Contract

- **After** a copy completes successfully (including closing the destination stream), request `MediaScannerConnection.scanFile()` for eligible local media files. This covers SMB → local, local → local, and archive → local file copies, including recursive folder copies, overwrites, conflict renames, and copy-based moves.
- When an operation successfully renames a local file or directory without copying bytes, request indexing of affected eligible destination files too. A source-file scan can be requested for moved files to allow stale MediaStore entries to be reconciled.
- Only request scans for recognizable image, video, and audio filenames on public/shared external storage volumes. Keep MIME/extension classification consistent with `FileTypes`.
- Never request scans for files that are absent, incomplete, hidden, under a `.nomedia` ancestor, inside Android/data or Android/obb, or outside known shared storage roots. Do not index archive-only or SMB destinations.
- No scanning on failure or cancellation. Notification is best effort; inability to notify MediaStore must **not** turn a fully successful transfer into a failed copy or trigger source deletion/transfer retry.
- On supported Android versions (minSdk 26), scan calls are asynchronous: copy completion requests indexing; it does **not** promise that Gallery refreshes synchronously or overrides Samsung Gallery's own filters. Gallery may exclude particular folders/content by its own policy.
- Preserve original file bytes, original destination choice, and normal conflict/cancellation behavior. No new broad media permissions or full-storage scan.

## Implementation boundary

Use a small `LocalMediaIndexer` whose eligibility rules can be unit-tested without Android. Its caller only informs it of **completed** local files. Application wiring supplies known public volume roots and a callback invoking `MediaScannerConnection.scanFile`. The operation engine stays independent of Android Context and owns the exact completion boundary.

## Regression tests

1. Eligible completed PNG/JPEG/video/audio in shared storage request one scan; non-media files, missing or partial targets do not.
2. Unknown or private paths, Android/data and Android/obb, hidden files/directories, and `.nomedia` ancestors are excluded.
3. Real filesystem operations notify only **after** the destination stream closes; interrupted/failed writes never notify.
4. SMB → local and recursive copy paths share the same completion behavior.
5. Test on a Samsung Android device: copy a PNG from SMB into `Pictures/`, wait briefly, and verify it appears in Samsung Gallery; check overwrite, folder copy, `.nomedia`, and SD-card destinations.

## Scope note

This issue addresses XFiles copy/move media publication. Direct writes by other entry points (e.g. separate ZIP extraction, rename commands, in-editor save) may need their own indexing hooks where they bypass the shared copy path; do not claim coverage without tests.
