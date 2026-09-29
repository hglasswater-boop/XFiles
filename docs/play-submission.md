# Google Play submission — current technical notes

> This file describes the repository's current Android behavior. Play Console state and Play policy can change independently, so re-check the Console and current policy before each submission.

## Current package and artifacts

The normal mobile package is:

```text
app.local1st.files
```

The release workflow builds:

```text
:app:assembleMobileRelease
:app:assembleTvRelease
:app:bundleMobileRelease
```

The mobile AAB is produced at:

```text
app/build/outputs/bundle/mobileRelease/app-mobile-release.aab
```

The GitHub release workflow also publishes signed mobile and TV APKs. The Android `versionCode` is derived from the repository build number and `versionName` comes from `version.properties`.

## Current feature / permission shape

PR #182 removed the App Manager and Root / Shizuku / `su` privileged-access stack. Do not describe or declare those removed features in store copy or review videos.

The current common manifest includes these notable permissions:

| Permission | Current purpose |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` | General-purpose file management across shared storage |
| `READ_EXTERNAL_STORAGE` (through API 32) | Legacy Android read access |
| `WRITE_EXTERNAL_STORAGE` (through API 29) | Legacy Android write access |
| `REQUEST_INSTALL_PACKAGES` | User-initiated APK/APKS/APKM/XAPK/AAB installation and self-update |
| `INTERNET` | SMB/NAS, mobile Chromecast relay traffic and update checks/downloads |
| `ACCESS_LOCAL_NETWORK` | Local-network SMB / Cast access where required by Android |
| `POST_NOTIFICATIONS` | Long-operation and media notifications |
| `RUN_USER_INITIATED_JOBS` | User-initiated SMB transfer jobs on supported Android versions |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Long-running file operations |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Cast relay playback keep-alive |
| `WAKE_LOCK` | Keep active operations from being interrupted by sleep |

The source of truth is [`app/src/main/AndroidManifest.xml`](../app/src/main/AndroidManifest.xml) plus the edition-specific manifests.

## Data safety / privacy positioning

XFiles has no developer-operated account system, advertising, analytics or telemetry. Do **not** claim that the app has no `INTERNET` permission: the current fork intentionally uses the network for features the user invokes.

User-directed network paths include:

- SMB traffic to a server configured by the user.
- Mobile Chromecast relay traffic to a receiver selected by the user.
- GitHub Release API / asset traffic for update checks and downloads.

Keep Play Console answers aligned with [`PRIVACY.md`](../PRIVACY.md). Whether a particular user-directed transfer counts as a Play Data safety category is a policy question, so verify the current Play definitions rather than copying historical answers from this repository.

## Privacy policy URL

Use the privacy policy for this fork, not the upstream repository:

```text
https://github.com/hglasswater-boop/XFiles/blob/main/PRIVACY.md
```

## All files access

XFiles remains a general-purpose file manager. `MANAGE_EXTERNAL_STORAGE` is used to present and operate on shared-storage files across locations without requiring the user to select each individual directory first.

When preparing the Play permission declaration, demonstrate normal file-management behavior such as browsing shared storage, copying/moving files, renaming, deleting, archive operations and destination selection. Do not include the removed Root or App Manager flows.

## Package installation

`REQUEST_INSTALL_PACKAGES` remains intentional. XFiles installs packages only when the user initiates an install/update flow. Supported package forms include APK, APKS, APKM, XAPK and AAB; AAB conversion is performed on-device before using Android's package installer flow.

If Play requests a declaration or demonstration, show the user selecting a package and the Android install confirmation flow.

## Long-running work

File operations can continue after the activity is backgrounded. Current code uses foreground-service support and, for supported SMB transfer paths on newer Android versions, user-initiated data-transfer jobs. Cast relay playback has its own media-playback foreground service.

Any Play foreground-service declarations and videos should match the service types actually present in the current manifest and implementation.

## Store listing source

Version-controlled listing text lives under:

```text
fastlane/metadata/android/
```

Current locales include English, Japanese and Simplified Chinese. The listing should emphasize the current fork features:

- dual-pane mobile tree browser
- SMB2/SMB3 / NAS access
- video thumbnails and storyboards
- Media3 playback and mobile Chromecast
- separate Google TV edition
- package installation
- encrypted settings backup
- no ads / analytics / telemetry

Do not advertise App Manager, Root, Shizuku, or "no network permission".

## Screenshots

The current en-US phone screenshot directory intentionally excludes the removed App Manager / Root screenshots. Before a Play submission, review every image in:

```text
fastlane/metadata/android/en-US/images/phoneScreenshots/
```

and make sure it still represents the current UI.

## Release-channel note

The repository has multiple distribution/update concepts:

- stable GitHub tags such as `v1.4.1-smb`
- rolling `nightly` releases after a stable tag exists
- signed `debug-latest` builds from `main` used by the normal in-app update channel
- side-by-side `diagnostic-latest` packages from `diagnose/*` branches

The diagnostic package uses a different application ID and must not be presented as the normal Play package.

## Pre-submission checklist

- Build and test the current mobile release AAB/APK.
- Re-check current Play policy and Console declarations.
- Confirm store text matches the current README and Fastlane metadata.
- Confirm privacy policy matches the current network and permission behavior.
- Confirm screenshots contain no removed App Manager / Root UI.
- Exercise local files, SMB, archives, video playback and package install paths relevant to the release.
- Review the final AAB permissions before upload.
