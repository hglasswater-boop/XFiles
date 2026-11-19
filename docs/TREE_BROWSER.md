# Tree browser behavior

This document defines the Android phone browser-tree state and refresh contract.

## Directory listings

- `PaneController` owns the authoritative child listing for directories that have been opened in the tree.
- Local expanded directories are refreshed from filesystem change notifications.
- Expanded SMB directories are refreshed by the browser's SMB polling policy.
- Operations handled by `OperationEngine` report affected container ids; direct mutation flows such as new-folder/new-text creation refresh their affected directory through `PaneController`.
- Refresh work is scoped to affected directories. A change in one directory must not force an application-wide or whole-tree rescan.

## Folder and file counts

Tree rows may show the number of **direct** child folders and files for an ordinary directory. These counts are intentionally non-recursive and are loaded lazily for rows whose child listing is not already available.

The count shown for a directory must describe the same filesystem contents as the latest successful browser refresh for that directory:

1. When `PaneController` successfully re-lists a directory, the resulting child list is also the authoritative source for that row's direct folder/file count. The count cache must be updated from that list and visible rows must observe the update.
2. When a refresh targets a directory with no cached child listing (for example, a collapsed visible folder), its cached direct count must be invalidated and a visible count row must lazily re-read that directory.
3. A failed directory listing must not replace a previously successful count with a fabricated zero value.
4. Updating one directory's count must not invalidate unrelated directory counts.
5. The same rules apply to local and SMB directories. SMB connection-root rows remain excluded from direct child counts because they are connection/routing nodes rather than ordinary directory listings.

This keeps tree counts fresh without adding recursive scans or broad refreshes to normal navigation.

## Video thumbnail information shortcut (Issue #290)

- The regular tree list keeps its existing behavior: tapping a video thumbnail opens its storyboard, tapping the rest of the row opens the file, and long-press opens the entry menu.
- For videos with a **LARGE (80 × 56 dp)** or **EXTRA_LARGE (104 × 72 dp)** thumbnail, show a small circular `Info` button in the thumbnail's **top-left**. Its action opens the existing `DialogRequest.Details(entry)` dialog with size, media metadata, and any embedded comment. This must not start playback or open the storyboard.
- The bottom-right remains reserved for duration and the center for the play glyph. The overlay must not change list geometry.
- SMALL (36 × 36 dp) and MEDIUM (56 × 42 dp) lack space for a separate reliable touch target; they retain details via the existing long-press menu. Non-video files and the lightweight startup row do not show this shortcut.
- Both local files and SMB entries use the same callback. Respect read-only/snapshot state and any pending destination-selection workflow, and provide an accessible content description for the action.
- The test contract covers the video/size/snapshot visibility policy and verifies via UI interaction that tapping the info overlay invokes details without invoking the thumbnail storyboard or row open action.
