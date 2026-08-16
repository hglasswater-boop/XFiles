# Privacy Policy — XFiles personal fork

**Last updated: 2026-08-16**

This XFiles fork (`app.local1st.files`) is an open-source Android file manager based on
XFiles and distributed under [GPL-3.0-only](LICENSE). This fork adds SMB2/SMB3 access to
user-configured network shares.

## The short version

**This fork does not include analytics, advertising, telemetry, cloud sync, or remote
configuration.** It does request Android's `INTERNET` permission because SMB requires a
network connection.

Network access is used for SMB2/SMB3 connections that you explicitly configure in the
app. File names, directory listings, file contents, and SMB credentials are sent only to
the SMB server you configured as required to perform those file operations.

## Data we collect

None.

There is no analytics SDK, no crash reporter, no advertising SDK, no telemetry, no
account system, no cloud service operated by this app, and no remote configuration.

## Data the app accesses

XFiles is a file manager, so it reads and writes files selected through its browser.

| What it accesses | Why | Leaves the device? |
|---|---|---|
| Files and folders in shared storage | To list, open, copy, move, rename, delete, compress and extract them | No, unless you explicitly copy them to an SMB share |
| SMB shares you configure | To browse and perform file operations on your NAS or network share | Yes — only to the SMB server you configured |
| The list of installed apps | To show the App manager, with icons, versions and components | No |
| Files anywhere on the filesystem, as superuser | Only if you explicitly enable **Root access** in Settings, and only on a rooted device | No, unless you explicitly copy them to an SMB share |

Thumbnails generated for local images and videos are cached in the app's private storage.
Clearing the app's storage removes them.

App settings and per-folder sort preferences are stored locally. SMB connection metadata
such as host, share name and username is stored in app-private preferences. SMB passwords
are encrypted with an AES-GCM key held by Android Keystore before being stored in
app-private preferences.

## Permissions

The fork declares the same file-management permissions as upstream XFiles plus
`android.permission.INTERNET`, which is required for SMB2/SMB3 network connections.

## Children

XFiles is a general-purpose utility and is not directed at children. It does not collect
analytics or advertising data from anyone.

## Third parties

The app bundles open-source libraries used to implement its features. SMB access is
implemented with SMBJ. No third-party analytics or advertising SDK is included.

## Changes to this policy

Changes to this policy appear in this file's git history.

## Source

This personal fork is maintained at:
<https://github.com/hglasswater-boop/XFiles>
