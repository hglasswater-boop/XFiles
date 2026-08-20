# F-Droid distribution plan

The goal is to make this fork available through the main F-Droid repository without breaking update compatibility for existing personal builds.

## Current status

This fork already has several pieces that are useful for F-Droid submission:

- Public GPL-3.0-only source code.
- No advertising, analytics, accounts or telemetry.
- Clearly tagged GitHub releases built from the public source tree.
- Fastlane store metadata in English and Japanese.
- Google Play dependency metadata is excluded from APK/AAB outputs to improve build transparency.
- Android dependencies are resolved through the Gradle build rather than committed as app-local binary SDKs.

## Main remaining blocker: fork identity

The normal build currently uses the upstream application ID:

```text
app.local1st.files
```

F-Droid requires forks to use a fresh Android application ID and to be visibly distinguishable from the original app, including the app name/icon and corresponding translated strings.

Changing the normal build's application ID would prevent it from updating already-installed personal builds, so the preferred approach is to add a dedicated F-Droid build variant/flavor with its own application ID and branding while keeping the existing personal/release build identity unchanged.

## Proposed F-Droid variant

The F-Droid variant should:

1. Use a fork-specific application ID.
2. Use a distinguishable app name and icon, for example **XFiles SMB**.
3. Keep all source and dependencies FLOSS-compatible.
4. Build without proprietary analytics, advertising or Play Services dependencies.
5. Keep the same core feature set unless F-Droid scanners require a dependency-specific adjustment.
6. Produce a deterministic, documented Gradle build command suitable for `fdroiddata` metadata.

## Submission work

Before submitting to `fdroiddata`:

- Add and test the dedicated F-Droid variant.
- Run F-Droid scanner/build checks against that variant.
- Audit all Maven dependencies and bundled resources for licenses and scanner findings.
- Add the final F-Droid application metadata and build recipe in `fdroiddata`.
- Ensure automatic version detection follows the tagged `vX.Y.Z-smb` releases.
- Consider enabling a reproducible-build verification path once the F-Droid variant builds cleanly.

GitHub release APKs remain the direct-download distribution for the personal build. F-Droid should build its package from the tagged public source rather than treating the GitHub APK as the package to publish.
