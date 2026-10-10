# Image casting (Mobile)

## Contract (Issue #208)

The image viewer exposes the existing Cast route picker in its top overlay. Selecting a
Cast device sends the **settled** image to the receiver. Swiping to another image while
connected replaces the displayed image. Pinch/zoom remains local: the receiver receives
the original image bytes and does not mirror local transforms.

- The Mobile edition uses the existing Cast SDK session and `CastMediaRelay`; the TV
  edition does not expose a sender button.
- Send a **single photo** to the receiver using `MediaInfo` with
  `STREAM_TYPE_NONE`, `image/*` content type and `MEDIA_TYPE_PHOTO` metadata.
  Do not send a video or a Cast receiver queue, or try to play photos through ExoPlayer.
- Support JPEG, PNG, GIF, WebP, BMP and APNG for photo entries. Resolve the concrete MIME
  by filename extension when available; fall back to a supported explicit image MIME
  if the filename does not identify the format. Never send `image/*` or
  `application/octet-stream` to the receiver as a photo.
- Reuse the local LAN HTTP relay with its per-file unguessable URL, range requests and
  `Content-Type`. Local files, content URIs, root and SMB entries can be relayed when
  a nonnegative file size is known. Archive-embedded images are not yet relayable:
  report that limitation explicitly instead of claiming a successful Cast operation.
- Preserve an image sender controller across hiding/showing image viewer chrome. The
  controller listens for Cast session start/resume and updates the currently selected
  image on a new session. A failed load is surfaced to the user.
- Image casting must supersede the prior video Cast state (including its notification
  and keep-alive) only when loading a supported image into a connected receiver.
- The relay belongs to the image viewer; on closing the viewer, unregister listeners
  and close the HTTP server/SMB handles. Do not stop the Cast device's Cast session
  merely because the viewer closes. Closing the sender may prevent a future receiver
  re-fetch; re-open the viewer to cast another image.
- Do not change any video Cast queue, seek, resume, storyboard or PiP behavior.

## Regression and acceptance coverage

Unit tests must cover MIME selection (all supported formats, generic/wrong metadata,
unsupported formats), relay transport eligibility and settled-page selection
deduplication/reconnection. Existing Cast range tests must continue to pass.

Physical-device validation on a Mobile sender and a real Chromecast / Google Cast
receiver is still required for:
1. Select a device from the image viewer; initial local JPEG/PNG appears on the TV.
2. Swipe images (including GIF/WebP) and verify each settled page replaces the photo;
   opening the image viewer with a Cast device already connected behaves identically.
3. Open an SMB folder and cast images; ensure HTTP access succeeds over the LAN.
4. Hide/show viewer chrome, then switch images; casting remains active.
5. Handle disconnected receivers, inaccessible files and unsupported photos without
   crashes; verify a previous video Cast notification is not left active.
6. Return to video playback and verify normal local/remote controls still work.

No device-level assertion is claimed from unit tests or builds alone.
