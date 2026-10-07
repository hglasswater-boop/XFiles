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

The Compose test rule uses `ui-test-junit4` and the debug-only `ui-test-manifest` activity.
The debug app and instrumentation APK must resolve `androidx.concurrent:concurrent-futures`
to the same 1.2.0 version required by AndroidX Test 1.7.0, because Android Gradle Plugin
constrains shared test dependencies to the app's resolved versions. Keep this alignment
in the debug configuration alongside the test activity dependency.

The existing mobile/TV unit tests, release lint and Android emulator instrumentation tests
remain required. Physical-device validation must also check local and SMB playback,
pause/seek controls, fine-preview open/close, cutouts/navigation modes, and PiP transitions.
Geometry tests do not establish actual device playback behavior.
