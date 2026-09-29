# Privacy Policy — XFiles personal fork

**Last updated: 2026-09-30**

This XFiles fork (`app.local1st.files`) is an open-source Android file manager based on XFiles and distributed under [GPL-3.0-only](LICENSE). It includes local file management, SMB2/SMB3 access, mobile Chromecast support, package installation and self-update checks.

## The short version

**XFiles does not include analytics, advertising, telemetry, a developer-operated account system, cloud sync, crash reporting that phones home, or remote configuration.**

The app does request Android network permissions. Network access is used when you explicitly use network-backed features, primarily:

- SMB2/SMB3 connections to servers you configure.
- Chromecast playback from the mobile edition to a Cast device you select.
- Update checks and APK downloads from this project's GitHub Releases.

XFiles does not operate a backend that receives your file contents, browse history, or usage analytics.

## Data we collect

None.

There is no analytics SDK, advertising SDK, telemetry service, developer account database, or XFiles cloud service collecting your activity.

## Data the app accesses

XFiles is a file manager, so it accesses files and metadata needed for the operations you request.

| What it accesses | Why | Leaves the device? |
|---|---|---|
| Files and folders in shared storage | Browse, open, copy, move, rename, delete, compress, extract and inspect files | Only when you explicitly copy/share/cast data to a destination you choose |
| SMB shares you configure | Browse and perform file operations on your NAS or network share | Yes, to the SMB server you configured |
| Media selected for Chromecast | Serve the selected media through the temporary Cast relay | Yes, to the Cast receiver you selected on the network |
| GitHub Release metadata and update APKs | Check for and install XFiles updates | Release metadata is requested from GitHub; an APK is downloaded only as part of the update flow |
| Settings, favorites and per-folder preferences | Preserve your app configuration | No, unless you explicitly export a settings backup |

Image/video thumbnails and storyboard frames may be cached in app-private storage for faster browsing. Clearing app storage removes those caches.

SMB connection metadata such as host, share name and username is stored in app-private preferences. Saved SMB passwords are encrypted with a key held by Android Keystore. Exported settings backups are password-encrypted before they are written.

## Permissions

The current Android manifest declares permissions required for file management, package installation, networking, notifications and long-running user-initiated work. The source of truth is [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml) plus edition-specific manifests.

Notable permissions include:

- `MANAGE_EXTERNAL_STORAGE` for general-purpose shared-storage file management.
- `REQUEST_INSTALL_PACKAGES` for user-initiated APK/APKS/APKM/XAPK/AAB installation and self-update.
- `INTERNET` and `ACCESS_LOCAL_NETWORK` for SMB, Chromecast relay traffic and update checks.
- foreground-service / user-initiated-job permissions and `WAKE_LOCK` for long-running file/media operations.

XFiles no longer includes the App Manager or Root/Shizuku privileged-access features, and does not declare their former package-query/delete or Shizuku provider permissions.

## Children

XFiles is a general-purpose utility and is not directed at children. It does not collect analytics or advertising data from anyone.

## Third parties

The app bundles libraries used to implement file, archive, media, SMB and Cast features. Network destinations are those required by features you invoke, such as your configured SMB server, your selected Cast receiver, or GitHub Releases for updates. XFiles does not embed third-party advertising or analytics SDKs.

## Changes to this policy

Changes to this policy appear in this file's git history.

## Source

This personal fork is maintained at:
<https://github.com/hglasswater-boop/XFiles>
