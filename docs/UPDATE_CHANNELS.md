# Self-update distribution

Status: product/implementation contract for XFiles in-app updates

## Scope

XFiles has **one** in-app normal-package update source for both Mobile and TV: the rolling, signed debug release tagged `debug-latest`. The former Normal/Nightly selector, Nightly release resolver and Nightly publication are retired.

The Mobile diagnostic package remains a separate side-by-side package published as `diagnostic-latest`. Diagnostic builds are installed only by the explicit diagnostic action, never by the normal automatic update check.

## Migration from Nightly

Older Mobile and TV builds stored `selected_track=nightly` in `mobile_self_update` / `tv_self_update` preferences. New builds **ignore** this legacy key and always request `debug-latest`. The automatic-update enabled state and last-check timestamp are preserved.

Previously installed non-debuggable Nightly or stable APKs still use the normal package ID and signing identity. When checking the normal release, a newer `versionCode` is installable; an **equal-build** debug APK is installable over a non-debuggable normal APK as a one-time migration. Equal-build replacement over an already-debuggable install and any downgrade are forbidden. Package ID, debug-build type and release-matching `versionCode` are validated before installation.

The legacy `nightly` GitHub release and tag will be removed by the main-branch release workflow. An **older app that still points to Nightly cannot automatically discover `debug-latest`**: it must be upgraded once by manually installing the current signed Normal APK from GitHub Releases (without uninstalling, to retain app data). After that, its update checks use Normal automatically. This limitation must be communicated when retiring Nightly.

## Release contracts

### Normal Mobile and TV

- GitHub release tag: `debug-latest`
- Mobile asset: `XFiles-<version>-b<build>-debug.apk`
- TV asset: `XFiles-TV-<version>-b<build>-debug.apk`
- `<build>` is the APK `versionCode`, parsed from the asset name.
- The latest qualifying asset of the expected edition is selected.

### Diagnostic Mobile (independent)

- GitHub release tag: `diagnostic-latest`
- Mobile asset: `XFiles-Diagnostic-<version>-b<build>-debug.apk`
- package ID: `app.local1st.files.diagnostic`
- Must be strictly newer than an installed diagnostic package.

### Stable release workflow

`.github/workflows/release.yml` continues building the signed Mobile + TV release APKs and Mobile AAB on main. The versioned `v<version>` stable release is published **only if that tag/release does not already exist**. Later pushes at the same version do not publish any rolling prerelease. The job removes the retired Nightly release and tag idempotently. The normal in-app update mechanism is provided independently by Debug CI's `debug-latest` release.

## Validation

The normal updater accepts a candidate only when:
1. The GitHub release has an asset matching the target edition's exact naming pattern, yielding a valid build number.
2. The candidate is newer than the installed normal package, or it is the same build as a **non-debuggable** installed normal APK (migration only).
3. The downloaded APK has the expected Mobile or TV package ID and a matching `versionCode`.
4. The downloaded normal APK is **debuggable**, matching the `debug-latest` contract. A non-debuggable APK is rejected even if the name and build number match.

An older APK, an equal-build already-debuggable APK, a mismatched package or build, or a non-debuggable "Normal" APK must be rejected.

The diagnostic package stays strictly newer than installed diagnostic builds, and its validation cannot be bypassed by normal-build migration.

## UI and update triggers

- The Mobile and TV settings screens show version, automatic-update switch, last check, manual update check and install dialog. **No channel selector** is shown.
- Manual and startup checks both use `debug-latest` unconditionally, regardless of legacy saved preference values.
- Mobile keeps the separate diagnostic install action.
- Standard install permission flow, background download and error reporting are retained.

## Regression coverage

Tests must cover: normal Mobile/TV asset selection, diagnostic independence, newer/equal non-debuggable/older build eligibility, missing package and metadata handling, strict APK type validation where testable, and the absence of Nightly references from update source code and release publication.
