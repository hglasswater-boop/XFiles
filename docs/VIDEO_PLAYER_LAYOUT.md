# Local video player layout

## Layout contract

The mobile local player uses the available Compose container dimensions, rather than
the device orientation flag, to arrange the video and its persistent storyboard.

- **Portrait or square container:** keep the horizontal storyboard between the video
  and playback controls. Reserve the storyboard height, the playback-control clearance,
  and a 6 dp gap below the video surface. The storyboard is 126 dp high, or 252 dp when
  its fine preview is open.
- **Landscape container (width greater than height):** place the storyboard in a
  single-column vertical sidebar at the end of the container. The sidebar is fixed at
  128 dp wide so the coarse storyboard keeps approximately the same 120 dp frame width
  used by the horizontal strip after its 4 dp horizontal content padding. The video and
  its controls occupy all remaining width and the full container height. Reserve no
  bottom space for the storyboard or playback controls on the video surface. Opening a
  fine preview changes only the sidebar's contents, without reducing the video area.
- **Picture-in-Picture:** show no storyboard and reserve no storyboard space on either
  axis, regardless of the PiP window's aspect ratio.

The local-player landscape sidebar always requests one storyboard column. Other callers
of `CastStoryboardStrip` choose their own vertical column count; in particular, the
portrait Chromecast controller keeps its existing two-column timeline.

The landscape sidebar has one internal edge (next to the video) and three device-facing
edges. Safe-drawing insets are therefore applied only to the top, bottom and end edges.
The start inset must not be applied inside the sidebar, because it belongs to the opposite
device edge and would shift the single storyboard column away from the sidebar center.
After the safe end inset, the storyboard keeps its existing 4 dp horizontal content
padding, so the thumbnail column remains centered in the usable sidebar width.

The sidebar is directly adjacent to the video with no layout gap. Unlike the floating
portrait storyboard, the landscape sidebar is rectangular and flush with the container
edges. It must not use rounded clipping, because clipped corners expose the parent window
background as light wedges at the storyboard edge.

The video player remains at one Compose call site when the container changes shape, so
layout changes do not replace the playback instance. Touch gestures and playback controls
continue to belong to the video area. Tapping outside an open fine preview dismisses it.
Storyboard loading, seeking, frame highlighting and fine-preview playback following reuse
the existing `CastStoryboardStrip` implementation.

## Vertical thumbnail selection and dismissal (Issue #235)

- In the **landscape local player's persistent one-column sidebar**, tapping a loaded
  thumbnail seeks to its timestamp **without hiding the sidebar**. The user must explicitly
  tap the storyboard visibility toggle to hide it; selecting a frame must not trigger that
  toggle; coarse thumbnail selection may still close an open fine preview.
- In an **expanded temporary vertical storyboard dialog**, tapping a thumbnail seeks and
  closes only that dialog. Long-pressing for a fine preview, scrolling, or jumping to the
  current item must not close the storyboard.
- In the landscape local player, the player top bar always exposes a labeled storyboard
  toggle: **Hide storyboard** when the sidebar is visible and **Show storyboard** when hidden.
  Its accent state reflects whether the sidebar is shown. Tapping it hides or restores the
  one-column sidebar without seeking or restarting playback. Hiding immediately returns its
  entire 128 dp width to the video; reopening uses the current video position. The toggle
  remains inside the video area (not the removable sidebar), so it can always reopen it.
  The toggle is absent from the portrait controls and from Picture-in-Picture.
- In the portrait player's expanded two-column storyboard dialog, a selected thumbnail
  closes only the dialog; the horizontal storyboard stays available.
- Tapping a thumbnail in the regular horizontal storyboard keeps that strip visible.
  Switching video entries or device orientation restores the usual initial landscape
  sidebar visibility. PiP continues hiding all storyboards.


## Fine storyboard manual browsing and playback following (Issue #275)

- Long-pressing any coarse thumbnail opens a fine-grained strip centered on **that
  thumbnail's timestamp**, even when playback is already ahead of it. Previously
  displayed past sections remain accessible by another long press; opening an older
  section must not immediately jump to the current playback position.
- The fine strip contains up to five fine-grained timestamps before and five after
  the selected center, with timestamps clamped to the available video range. Users
  can scroll backward and forward through the generated fine frames and select
  any loaded timestamp to seek, including timestamps earlier than the playhead.
- Automatic playback following is **boundary crossing**, not merely "playhead is
  already beyond this fine window". A forward playback update (including a seek)
  can move the fine preview to a later coarse section only when the playhead was
  at or before the displayed fine window's end and then moves past it. This keeps
  normal playback following from Issue #188 without overriding manual inspection
  of older sections.
- Backward seeking does not automatically rewind the fine preview. Once playback
  returns to/before the displayed window end, a later forward crossing may resume
  auto-follow. Another long press explicitly selects a new window and resets the
  follow baseline.
- The same shared fine-preview behavior applies to local and Cast controllers and
  to vertical and horizontal coarse storyboard layouts.

Unit regression coverage must include: selecting an older coarse frame while
playback is already later, normal forward crossing, crossing several sections
in a large forward seek, backwards movement, and video start/end clamping.
Device verification remains necessary for actual touch/scroll behavior.


## Why landscape needs a sidebar

Issue #195 reports that rotating the player into landscape pushes the video upward and
makes it extremely small. Previously, the player subtracted a fixed 120 dp storyboard
(236 dp with fine preview) and the playback-control clearance from the video height in
landscape. The initial fallback clearance was 180 dp, plus a 6 dp gap. A 360 dp-tall
landscape container therefore initially left only 54 dp for the video, or no height
with the fine preview open. A sidebar removes this competition for vertical space.

Issue #204 further reduces horizontal competition. The first sidebar implementation used
two storyboard columns and consumed one third of the container width, capped at 240 dp.
A single 128 dp column preserves a useful thumbnail width while returning substantially
more horizontal area to the video.

Issue #206 tightens the single-column geometry on devices with landscape safe-drawing
insets. Applying the full device inset set inside the right-side sidebar can consume its
internal/start edge and make the thumbnails appear pushed toward the outer side. The same
issue also removes rounded clipping from the edge-attached landscape sidebar so a light
parent background never leaks through at the surface corners.

## Regression coverage

Compose instrumentation tests exercise the production layout with tagged video and
storyboard slots, using constrained container sizes without requiring media decoding:

- Landscape video retains the full container height, including with fine preview open.
- The storyboard is beside the video and its start bound exactly matches the video's end
  bound; there is no layout gap between them.
- Landscape requests exactly one vertical storyboard column.
- The landscape sidebar is 128 dp wide, leaving the remaining width to the video.
- Landscape safe-drawing does not add a start-side inset inside the right sidebar.
- The landscape sidebar is edge-attached without rounded clipping, so its corners cannot
  expose a light parent background.
- Portrait keeps the horizontal storyboard above playback controls and reserves its height.
- PiP hides the storyboard and gives the video the entire container in both aspect ratios.
- Switching the same composition from portrait to landscape and back retains the video
  slot's remembered state and recalculates the layout.
- Tapping the video area while the fine preview is open dismisses that preview.
- A vertical thumbnail selection seeks, dismisses the landscape sidebar or expanded portrait
  dialog as appropriate, and does not dismiss the ordinary horizontal strip. In landscape,
  the toolbar toggle hides and reopens the sidebar without replacing the video player; the
  video expands to the full container width when hidden and resumes its previous layout when
  visible. No manual hide control appears in portrait or PiP.

The Compose test rule uses `ui-test-junit4` and the debug-only `ui-test-manifest` activity.
The debug app and instrumentation APK must resolve `androidx.concurrent:concurrent-futures`
to the same 1.2.0 version required by AndroidX Test 1.7.0, because Android Gradle Plugin
constrains shared test dependencies to the app's resolved versions. Keep this alignment
in the debug configuration alongside the test activity dependency.

The existing Android unit tests, release lint and Android emulator instrumentation tests
remain required. Physical-device validation must also check local and SMB playback,
pause/seek controls, fine-preview open/close, cutouts/navigation modes, and PiP transitions.
Geometry tests do not establish actual device playback behavior.
