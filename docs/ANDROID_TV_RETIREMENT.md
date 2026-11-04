# Android TV edition retirement (Issue #260)

## Decision

XFiles is an Android **phone-only** application. Remove the `tv` product flavor and the `app.local1st.files.tv` package, TV-only source sets (manifest, D-pad / focus UI, theme, Leanback banner), TV-specific update handling, APK build, release assets, preview and CI jobs. Retain the normal package ID `app.local1st.files`, its signing keys, version policy, and the independent `.diagnostic` package variant controlled through the existing build property.

## User-visible compatibility

- The only normal app is the two-pane touch edition; **Chromecast sender / Cast media playback must remain available** (TV receiver app removal does not remove phone Cast support).
- Existing `app.local1st.files` installs must receive normal updates without uninstalling or losing data. Remove edition flavors without changing package ID.
- Existing `app.local1st.files.tv` installations are **different package IDs** and do not upgrade to the phone app. The former TV app stops receiving updates; historical GitHub release binaries are not retroactively deleted.
- Preserve current SMB/Rust/SMBJ backend selection, WireGuard, NAS playback, file operations and diagnostic updater behavior. Issue #239 remains open until its own hardware/NAS gates are passed.

## Single app build contract

- Debug: `./gradlew :app:assembleDebug :app:testDebugUnitTest`, APK `app/build/outputs/apk/debug/app-debug.apk`.
- Release: `./gradlew :app:assembleRelease :app:bundleRelease`, APK `app/build/outputs/apk/release/app-release.apk`, AAB `app/build/outputs/bundle/release/app-release.aab`.
- Connected emulator: `./gradlew :app:connectedDebugAndroidTest`.
- The three existing JNI ABIs and signing requirements are unchanged.
- Stable asset: `XFiles-<version>.apk`; rolling debug: `XFiles-<version>-b<build>-debug.apk`; diagnostic: `XFiles-Diagnostic-<version>-b<build>-debug.apk`. No new `XFiles-TV-*` assets.

## Existing locale fallback compatibility

The phone-only strings previously in `app/src/mobile/res/values/strings.xml` had English and Japanese translations only. After moving them to `main`, other enabled locales continue to fall back to English for those **same 27 strings**, rather than pretending translations exist. The resource-completeness test explicitly exempts only that known legacy set, requires full Japanese coverage, and still requires all pre-existing shared strings in every advertised locale. Further translations can remove entries from the exception set.

## TDD and acceptance

1. Update documentation before code changes.
2. Tests must assert a single application ID and the absence of TV flavors/TV release assets, verify only the phone signed update asset is accepted, and retain Cast controller tests.
3. Remove TV sources and flavor config, move the phone-only source set to `main`, merge its nonduplicated resource strings, and switch `mobileImplementation` Cast dependency to `implementation`.
4. Update release, debug, requested artifact and Rust preview workflows, plus any launcher/package identity tests, to single-app Gradle tasks and paths.
5. Run unit/lint/APK/emulator/CI. Check no active TV-only source, Gradle task, workflow output or updater branch remains. Keep historic release notes and previous CI history intact; those describe past releases, not current support.
6. Do not mark #260 completed until required CI and smoke checks are green. Do not close unrelated Rust migration issues.
