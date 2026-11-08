# Mobile Cast playback state contract

## Scope

The mobile Cast controller presents the current folder's video playlist, but the Cast
receiver is given only **one media item at a time**. Jumping to another video replaces
that receiver item. The local ExoPlayer retains the folder playlist for local playback.

This document covers the remote player's item identity after its single item reaches
the end, and the handoff from local to remote playback. Image Cast is documented
separately in [IMAGE_CAST.md](IMAGE_CAST.md).

## Cross-folder selection (Issue #273)

- While a Cast receiver is connected and remote playback is active, navigating back to the
  browser and opening a video from another folder must **keep the same Cast player,
  receiver connection, HTTP relay and foreground-service ownership**. Changing the
  folder is a playlist update and an explicit remote media handoff, never a reason
  to create a new CastPlayer or fall back to phone playback.
- Replace the controller's folder playlist and the inactive local player's playlist
  before sending the newly selected video to the receiver. The receiver continues
  to receive one media item at a time. Previous/Next and auto-advance now follow
  the newly selected folder's order, not the prior folder's order.
- Register the new folder's media IDs, relay sources and converter originals on the
  existing objects **before** the remote load. Keep sources already used by an
  in-flight request valid until the relay itself is closed.
- Once Cast is explicitly disconnected, a newly opened video plays locally as usual.
  A video change within the same folder retains the existing selection behavior.
  Viewers must attach/detach listeners for the correct playlist and must not let a
  previous viewer's dispose event dismiss the new viewer's ownership.
- This is a process-scoped session handoff, not a persistent queue across app restarts.
  Local/SMB-to-Cast continuity must be verified on a real Cast receiver; an emulator
  cannot establish cross-device behavior.

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
- Auto-advance runs **only** when the active remote player reports
  `Player.STATE_ENDED` or the Cast receiver explicitly reports
  `MediaStatus.PLAYER_STATE_IDLE` with `IDLE_REASON_FINISHED`, with no pending
  handoff or player error. Generic `STATE_IDLE` is not sufficient: canceled,
  interrupted and failed loads must not auto-advance. Its source video
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
  the same presented video identity. This contract does not change local playback
  or automatically replay a video when it ends.

## Relay reliability and diagnostics

- A Cast receiver loads XFiles media through the handset's HTTP relay, which
  requires an IPv4 address reachable on the same LAN as the receiver. The current
  selection first checks Android's active-network IPv4 address, then network
  interfaces. Active-network selection can be wrong with VPN/mobile routing;
  this issue was reported on a LAN **without VPN**, so that separate case has
  not been proven or fixed by this change.
- The relay serves independently requested HTTP byte ranges from SMB using
  positional reads. In particular, a metadata/probe range must not call
  `seek()` on the same shared Rust handle and cancel an ongoing stream. HTTP
  streaming replacements may cancel an obsolete stream only for the same media
  item; unrelated ranges and items remain independent.
- A failed HTTP read must be diagnosable in logcat without printing sensitive
  file paths, relay tokens or credentials. A premature end of the HTTP response
  is **not** a successful Cast playback.
- Automatic Next/Previous depends on the **remote** player reporting a natural
  `STATE_ENDED` or an explicitly finished Cast idle reason, not elapsed wall
  clock time, a paused state, a disconnected receiver or generic `STATE_IDLE`.
  Remote player errors and missing completion events must be distinguished
  when investigating failures; a device observation is necessary to prove the fix.

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
10. Cast IDLE+FINISHED advances; IDLE+CANCELED, IDLE+INTERRUPTED, and IDLE+ERROR do not.
11. Independent SMB HTTP range reads do not seek/cancel a shared backend handle and preserve the requested byte offset.
12. Reusing a remote session with a different folder updates its playlist without rebuilding the player, remaps index/identity, and supports explicit handoff to the new folder.
13. Old remote HTTP source tokens remain valid while the relay adds the new folder's sources.

Physical-device validation (not covered by JVM tests): cast a non-first video,
allow it to finish with Off selected and check that the controller, browser
highlight and notification still refer to it with playback stopped. Change mode
to Next and Previous and check sequential automatic playback in each direction;
verify both folder boundaries, pause, manual stop/disconnect and manual next/previous.
With the controller dismissed, verify auto-advance still runs and updating the mode
after reopening controls takes effect on the next video end.
