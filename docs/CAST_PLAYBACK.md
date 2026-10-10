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
  naturally finishes**. Playback reports stopped/ended rather than automatically
  selecting the first or next folder item. Previous/next commands still work if there
  are adjacent videos in the folder.
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

Physical-device validation (not covered by JVM tests): cast a non-first video,
allow it to finish, check that the controller, browser highlight and notification
still refer to that video and that playback is no longer active. Repeat for
manual stop/disconnect and previous/next across videos.