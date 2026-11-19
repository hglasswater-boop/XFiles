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
- For videos with a **LARGE (80 × 56 dp)** or **EXTRA_LARGE (104 × 72 dp)** thumbnail, show only a **white `Info` glyph** in the thumbnail's **top-left**, with the visible glyph's top-left about **2 dp** inside the thumbnail. Do **not** draw the previous translucent black circular background or a background clip. Keep a transparent **40 × 40 dp** hit target anchored directly to the thumbnail's top-left for accessibility. Its action opens the existing `DialogRequest.Details(entry)` dialog with size, media metadata, and any embedded comment. This must not start playback or open the storyboard.
- The bottom-right remains reserved for duration and the center for the play glyph. The overlay must not change list geometry.
- SMALL (36 × 36 dp) and MEDIUM (56 × 42 dp) lack space for a separate reliable touch target; they retain details via the existing long-press menu. Non-video files and the lightweight startup row do not show this shortcut.
- Both local files and SMB entries use the same callback. Respect read-only/snapshot state and any pending destination-selection workflow, and provide an accessible content description for the action.
- The test contract covers the video/size/snapshot visibility policy and verifies via UI interaction that tapping the info overlay invokes details without invoking the thumbnail storyboard or row open action.

## Compact tree indentation (Issue #292)

- The tree's visible hierarchy guide reserves **8 dp per visible level** rather than 12 dp. The maximum visible indent remains governed by the existing browser setting (0–4 levels). At four levels the guide occupies 32 dp, down from 48 dp.
- Both the lightweight startup rows and normal rows use the **same** indent calculation so thumbnail and filename positions do not jump as the list finishes loading. Deep hierarchy still compresses to the most recent visible levels, without changing the breadcrumb's full path.
- The expand chevron keeps its former **12 dp canvas width** plus its existing leading 1 physical pixel; its leaf-placeholder width is identical. Reducing the guide indentation must not shrink the arrow glyph or alter row tap, long-press, selection, or expanded/collapsed behavior.
- Guide elbows, vertical continuation lines, and branch ends remain inside the reduced slots. Thumbnail, selection-control, and row-height dimensions do not change.
- Regression checks cover 0, 1, 2 and 4 visible indent levels and the fixed chevron/placeholder width. Visual verification on an actual device remains a separate completion gate.

## Thumbnail-to-tree gap (Issue #297)

- A video or image **thumbnail leaf row** has no expand chevron; the empty space between the last tree guide and the thumbnail is **4 dp**, rather than reserving the container's full 12 dp chevron width plus its existing lead pixel.
- Keep the **8 dp per visible tree level** guide geometry unchanged, and leave the container chevron at **12 dp**. Folder rows and non-thumbnail file rows keep their existing leading slot; this change targets wasted space in thumbnail rows only.
- The regular row and lightweight startup row must apply the same thumbnail-leaf gap, so swapping between row modes cannot shift the thumbnail horizontally. Existing thumbnail sizes, filenames, details icon, playback, selection, and row hit targets are unchanged.
- Regression coverage asserts the 4 dp thumbnail-leaf gap remains narrower than the existing 12 dp chevron slot; on-device visual inspection verifies the reduced spacing and retained tree connectors.
