# Self-update distribution

Status: product/implementation contract for XFiles in-app updates (#221)

## Scope

The Android phone app **checks both the latest published stable GitHub release** (`/releases/latest`) **and the rolling, signed `debug-latest` release**. No Normal/Nightly selector is restored. The **highest installable Android `versionCode` wins**; when stable and debug have the same build code and are both eligible, prefer the **stable APK**. A stable APK is **never** installed over the same or a newer build. This prevents versionCode downgrades when the rolling debug build is already newer than the stable release.

The app's **automatic startup check** (daily, when enabled) and manual Settings update button use the same resolver. An update proposal is displayed to the user; installing still requires the existing Android package installation confirmation. This is not unattended silent installation.

The independent Mobile diagnostic package uses `diagnostic-latest` only from its explicit diagnostic action, never from the normal check.

## Sources and selection

### Stable GitHub Releases

- API: `https://api.github.com/repos/hglasswater-boop/XFiles/releases/latest`.
- Accept only a **non-draft, non-prerelease** release with a tag `v<version>` where `<version>` matches `N.N.N-smb`.
- The release workflow uploads `XFiles-<version>.apk` as a **non-debuggable**, signed phone APK.
- The release workflow records the **actual APK versionCode** in the body as `XFiles <version> · build <build> (<40-hex commit>)`. This is the stable-release *metadata contract* for already-published `v1.4.3-smb` and subsequent releases. Resolve only when tag, body version and the exact asset name agree and build is positive.
- Check and validate the downloaded APK manifest's edition package and `versionCode` against release metadata; reject the wrong package or a wrong build. Reject a downloaded stable APK that is marked debuggable.
- Stable is eligible only for **strictly newer** versionCodes. Equal versionCode does not swap Debug for Stable, because a same-build reinstall isn't a reliable upgrade.

### Rolling debug fallback / newer build

- API: `https://api.github.com/repos/hglasswater-boop/XFiles/releases/tags/debug-latest`.
- Phone asset: `XFiles-<version>-b<build>-debug.apk`.
- These builds use the **same stable signing identity** but are debuggable. The asset's `<build>` is the APK `versionCode`.
- Debug is eligible **only if its build code is strictly newer** than installed. The prior equal-build non-debuggable-to-debuggable migration is retired for this stable-aware updater: an already-installed stable APK must not prompt a same-build switch to Debug.
- If both sources have eligible assets, choose the higher build code, preferring stable on a tie. A missing/malformed stable asset does **not** block a valid debug update, and vice versa.
- Continue to publish `debug-latest` on each `main` push: **existing app versions that only know the debug feed cannot discover the stable feed until their updater is upgraded through this bridge**. The new stable-aware logic reaches installed users through the next `debug-latest` package. Old apps configured for retired Nightly may still require one manual update.

### Rolling release publication and recovery (Issue #265)

- The `debug-latest` GitHub Release is an **in-place rolling release**, not a disposable publication. Existing app installations query its tag directly. The CI **must never delete the release/tag before an upload**: GitHub Releases returning HTTP 500 mid-publication must not break previously working updates. `diagnostic-latest` follows the same publication contract.
- After the signed APK is built, the publisher checks whether the rolling release exists, creates it only when absent, then **uploads the new APK first** using idempotent overwrite. New releases are initially created with no assets so failed or partial creates can be retried safely. Existing assets and release metadata remain available if creation/upload fails.
- After successfully verifying the newly uploaded asset, stale older assets can be removed individually (never the active APK or the whole release). This keeps the update resolver unambiguous and avoids accumulating old APKs.
- Set the release title, body, prerelease flag, target commit, and move the lightweight rolling Git tag to the current build commit. Verify the published APK and Git tag SHA after publishing. Stable version tags such as `v1.4.10-smb` are **never** changed by this process.
- The publisher retries transient GitHub API failures with bounded backoff; an exhausted retry must fail the CI job visibly, not claim that users have received an update. It must leave any previously published APK available. Re-running the same `main` commit is idempotent.
- Test contract: fake `gh` executable covers an existing release, absent release, transient upload failure, partial creation success despite API failure, and persistent API failure without deleting a working release. Run tests in CI, then verify the actual `debug-latest` API and its asset after a successful `main` workflow. Users must not be told that a new build is available before this check.

### Diagnostic package (unchanged)

- API: `diagnostic-latest`
- Mobile asset: `XFiles-Diagnostic-<version>-b<build>-debug.apk`
- Package ID: `app.local1st.files.diagnostic`
- Strictly newer than the installed diagnostic build; no stable diagnostic channel.

## GitHub Actions artifact retention

- GitHub Releases are the install/update distribution channel. **Never delete stable phone APKs or the `debug-latest`/`diagnostic-latest` release assets when clearing Actions artifacts.**
- CI-generated artifacts are temporary convenience downloads, not a permanent archive: branch-only signed debug APKs, on-demand debug builds, Rust SMB diagnostic previews and failed emulator reports expire after **1 day**.
- On `main`, the signed debug APK is already published in the rolling GitHub Release, so do not upload an extra Actions APK artifact.
- Successful emulator tests keep their results in run logs instead of uploading duplicate report artifacts. On failure, retain the diagnostic report for 1 day.
- For a **new stable version only**, retain a signed Release AAB artifact for **7 days** for manual Play submission. Rebuilds of an already-published stable version do not upload duplicate AABs. Old Actions artifacts may be cleared in bulk, including expired build outputs and superseded AABs; this does not touch release assets or GitHub Actions run logs/caches.
## Security and compatibility

- Only use the exact platform-specific release asset names and reject missing or invalid release metadata.
- Download to the existing update cache, verify readable Android package identity and manifest `versionCode`, reject downgrades and reject incorrect Debug/Release APK type.
- Verify the APK's signing identity matches the currently installed package before launching Android's installer; the Android `PackageInstaller` remains the final signing/compatibility authority and requires user consent. A new installation with no previously installed package cannot compare against a prior signing identity.
- Keep Android phone package identity `app.local1st.files` and side-by-side diagnostic `app.local1st.files.diagnostic`. No package-ID changes or silent installation.
- Preserve `auto_check_enabled`, `last_auto_check`, last-check messaging and once-daily cadence. UI remains one automatic-update toggle and one check button, no channel selector.
- Stable release CI continues publishing a stable tag **once per version** plus the phone APK and AAB. Later commits to `main` with the same version update rolling debug, but do not mutate that stable tag.
- Verify that `debug-latest` and stable assets are signed with a mutually compatible key on physical devices. CI and manifest checks do not prove real-device seamless installation.

## Regression coverage

Tests must cover: stable metadata/tag/body/asset agreement; missing/malformed phone assets; draft/prerelease rejection; strict build-number upgrades for both sources (including equal-build rejection); no downgrade; newest candidate selection and stable tie preference; diagnostic independence; existing phone app update UI flows; package-signature preflight and manifest validation where Android tests can exercise them.

## Historical note

Prior to #221, normal update checks used **only** `debug-latest`, with an intentional one-time equal-build migration from non-debuggable stable/Nightly packages. That exception is removed: any subsequent transition to a different build type must have a strictly newer build number. Older installed builds with the retired `selected_track=nightly` preference use the normal feed in newer app versions; builds that still fetch the deleted `nightly` tag need a one-time manually installed update.
