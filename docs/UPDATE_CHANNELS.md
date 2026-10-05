# Self-update channels

Status: product/implementation contract for XFiles in-app updates

## Scope

XFiles has an in-app updater for the normal Mobile and TV packages. The normal package can follow one of two update channels selected by the user:

- **Normal**: the rolling main-branch debug release published as `debug-latest`.
- **Nightly**: the rolling release-build prerelease published as the `nightly` tag.

The Mobile diagnostic package remains a separate side-by-side package published as `diagnostic-latest`. Diagnostic builds are installed only by the existing explicit diagnostic action and are not an automatic-update channel.

## Selected channel

The selected normal-package channel is persisted separately for Mobile and TV. The default is **Normal**, preserving the existing update behavior for installs that have not selected a channel.

Both manual update checks and the startup automatic check must use the same selected channel. Changing the channel changes only the source used by later checks. XFiles never attempts an Android `versionCode` downgrade to switch channels.

Normal and Nightly builds produced from the same commit intentionally share the same commit-derived `versionCode`. Therefore an explicit channel switch may offer an equal-build replacement when the installed package belongs to the other channel. This makes `Normal -> Nightly` and `Nightly -> Normal` selectable immediately for the same commit without inventing a higher version code. An equal build from the already-installed channel is not an update.

The installed channel is identified from the installed normal package: Normal is the debuggable main build and Nightly is the non-debuggable release build. Both use the same normal package id and signing identity.

## Release contracts

### Normal

Normal keeps the existing contract:

- GitHub release tag: `debug-latest`
- Mobile asset: `XFiles-<version>-b<build>-debug.apk`
- TV asset: `XFiles-TV-<version>-b<build>-debug.apk`
- `<build>` is the APK `versionCode` and is parsed from the asset name.

The Mobile diagnostic package keeps its independent contract:

- GitHub release tag: `diagnostic-latest`
- Mobile asset: `XFiles-Diagnostic-<version>-b<build>-debug.apk`
- package id: `app.local1st.files.diagnostic`

### Nightly

Nightly uses the rolling release produced by `.github/workflows/release.yml`:

- GitHub release tag: `nightly`
- Mobile asset: exactly `XFiles-nightly.apk`
- TV asset: exactly `XFiles-TV-nightly.apk`

Nightly asset names intentionally do not encode the version or build. The release workflow already emits the canonical metadata line in the release body:

```text
XFiles <version> · build <build> (<commit>).
```

The updater parses `<version>` and `<build>` from that line. A nightly response without valid canonical metadata or without the exact edition asset is invalid and must fail the update check rather than guess.

## Availability and validation

For the selected normal-package channel, a release is installable when either:

1. its build number is strictly greater than the installed normal package `versionCode`; or
2. its build number is equal and the installed normal package belongs to the other update channel.

An older build is never offered. An equal build from the already-installed channel is not offered.

Before invoking the Android package installer, the downloaded APK must still be validated:

1. The APK package id matches the current normal Mobile or TV package.
2. The APK `versionCode` equals the build number resolved from the GitHub release contract.
3. The APK `versionCode` is not lower than the installed normal package build.
4. An equal-build APK is accepted only when it replaces the other normal-package update channel.

Diagnostic package validation remains strict: its build must be newer than the installed diagnostic package because it has no channel-switch case.

This keeps channel selection separate from installation safety and prevents a stale or malformed GitHub release from being installed.

## UI behavior

The update settings card exposes the selected normal-package channel (`Normal` or `Nightly`) and allows switching it explicitly. The existing automatic-check toggle applies to that selected channel.

Manual **check/install latest** uses the selected normal-package channel. Mobile continues to expose the diagnostic-package action separately and labels it as a side-by-side diagnostic install.

## Tests required before implementation

Regression coverage must prove:

- Normal Mobile and TV retain the existing `debug-latest` endpoint and asset parsing.
- Nightly resolves the `nightly` endpoint and parses the canonical release metadata line.
- Nightly selects `XFiles-nightly.apk` for Mobile and `XFiles-TV-nightly.apk` for TV.
- Missing or malformed nightly metadata is rejected.
- A newer remote build is installable and an older build is not.
- An equal build is installable only when the selected channel differs from the installed channel.
- Channel persistence defaults to Normal and round-trips Normal/Nightly values.
- Mobile diagnostic release resolution remains independent from the selected normal-package channel.
