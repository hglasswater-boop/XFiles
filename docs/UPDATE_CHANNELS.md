# Self-update channels

Status: product/implementation contract for XFiles in-app updates

## Scope

XFiles has an in-app updater for the normal Mobile and TV packages. The normal package can follow one of two update channels selected by the user:

- **Normal**: the rolling main-branch debug release published as `debug-latest`.
- **Nightly**: the rolling release-build prerelease published as the `nightly` tag.

The Mobile diagnostic package remains a separate side-by-side package published as `diagnostic-latest`. Diagnostic builds are installed only by the existing explicit diagnostic action and are not an automatic-update channel.

## Selected channel

The selected normal-package channel is persisted separately for Mobile and TV. The default is **Normal**, preserving the existing update behavior for installs that have not selected a channel.

Both manual update checks and the startup automatic check must use the same selected channel. Changing the channel changes only the source used by later checks; XFiles never attempts an Android `versionCode` downgrade to switch channels.

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

For either normal channel, an update is available only when the remote build number is strictly greater than the installed normal package `versionCode`. Equal and older builds are not offered.

Before invoking the Android package installer, the downloaded APK must still be validated:

1. The APK package id matches the current normal Mobile or TV package.
2. The APK `versionCode` equals the build number resolved from the GitHub release contract.
3. The APK `versionCode` is strictly greater than the installed normal package build.

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
- Equal/older remote builds are not considered updates; a newer build is.
- Channel persistence defaults to Normal and round-trips Normal/Nightly values.
- Mobile diagnostic release resolution remains independent from the selected normal-package channel.
