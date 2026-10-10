# Mobile Cast playback state contract

## Scope

The mobile Cast controller presents the current folder's video playlist, but the Cast
receiver is given only **one media item at a time**. Jumping to another video replaces
that receiver item. The local ExoPlayer retains the folder playlist for local playback.

This document covers the remote player's item identity after its single item reaches
the end, and the handoff from local to remote playback. Image Cast is documented
separately in [IMAGE_CAST.md](IMAGE_CAST.md).

## Selected-video identity

- While Cast is active, the controller's selected video comes from the pending explicit
  handoff target (if any), otherwise the currently reported receiver media ID.
- Once the receiver confirms a media ID, remember it as the **last confirmed remote
  video**. A transient or terminal `null` media ID from the still-connected receiver
  must not reset the controller's selection to the first video in the folder.
- The selection remains on that last confirmed video when the **single receiver item
  naturally finishes** and auto-advance is Off. Playback reports stopped/ended rather
  than selecting the first or next folder item. Previous/next commands remain available.
- The Cast controller offers three mutually exclusive auto-advance modes with an icon
  and visible label for each: **Off** (stop on this video), **Next** (forward in folder
  order), and **Previous** (backward in folder order). Off is the initial mode. This
  mode is owned by the process-scoped Cast session manager, so it survives dismissing
  and reopening the controller during the same app process, but is not persisted across
  app restarts. Changing the mode does not immediately start another video.
- Auto-advance runs **only** when the active remote receiver reports
  `Player.STATE_ENDED`, with no pending handoff or player error. Its source video
  is the last receiver-confirmed media ID, not the local playlist's index or Cast's
  sometimes-empty receiver queue. The manager replaces the receiver's one media item
  with the immediately adjacent folder video via the existing explicit remote load.
  At either folder boundary the current video remains selected and playback ends;
  there is **no wraparound**.
- Process a natural completion for a video **once** even if Cast sends duplicate
  terminal events from multiple listeners. After that video is deliberately replayed
  and reaches a fresh ready state, a subsequent natural ending may advance again.
  Explicit seek, pause, manual stop, disconnect and playback error never advance.
- An explicit jump supersedes the previous selection immediately, but stale callbacks
  from the previous receiver item cannot acknowledge it. The new selection is confirmed
  only when the receiver reports its target media ID.
- Disconnection returns the controller to the local player's state; it must not reuse a
  stale remote selection. Playback failure cancels a pending handoff and invalidates
  the last remote selection so a failed load is not presented as successful.
- Notification title, browser Cast highlighting, and playback controls should all use
  the same presented video identity. This contract does not change local/TV playback
  or automatically replay a video when it ends.

## Regression tests

`CastHandoffTrackerTest` should cover:
1. An acknowledged video B retains B after the remote player reports `null` at end.
2. A completed jump A → C retains C at end and does not revert to A.
3. The pending jump stays authoritative over stale A/`null` reports.
4. Disconnection and failed playback do not retain the old remote identity.
5. The non-remote path never substitutes the remembered remote media ID.
6. Off mode leaves the item selected; Next and Previous choose the correct adjacent item.
7. First/last boundaries never wrap; unknown videos never select an arbitrary item.
8. Repeated END events for the same video do not skip multiple videos, and a replayed
   video may advance again after it has entered a fresh ready state.
9. Pending handoff, errors, disconnection and non-ended playback cannot advance.

Physical-device validation (not covered by JVM tests): cast a non-first video,
allow it to finish with Off selected and check that the controller, browser
highlight and notification still refer to it with playback stopped. Change mode
to Next and Previous and check sequential automatic playback in each direction;
verify both folder boundaries, pause, manual stop/disconnect and manual next/previous.
With the controller dismissed, verify auto-advance still runs and updating the mode
after reopening controls takes effect on the next video end.
