---
title: Mini Audiobookshelf Android - Scaffold
type: feat
date: 2026-09-01
artifact_contract: ce-unified-plan/v1
artifact_readiness: implementation-ready
product_contract_source: ce-plan-bootstrap
execution: code
---

# Mini Audiobookshelf Android - Scaffold

## Goal Capsule

- **Objective:** Create a greenfield Kotlin/Compose Android repo that compiles, launches an empty Material 3 window, and runs a JVM unit test in a `:core` module that cannot import Android. Later phases add sign-in, browse, playback, and downloads against this layout.
- **Authority hierarchy:** Requirements (R-IDs) win on product behavior. Key Technical Decisions (KTD-IDs) win on implementation mechanism within their cited requirements. Implementation Units override neither. Later phase plans add R-IDs; they do not reopen KTD1 through KTD6 unless a stop condition fires.
- **Execution profile:** Greenfield. U1 is the Gradle tree. U2 is the empty window and composition root. U3 is the `:core` test harness. Stop before any Audiobookshelf HTTP call.
- **Stop conditions:** Stop and ask if AGP or the Compose BOM on the machine cannot target SDK 36; if `:core` cannot be a pure JVM module under the current Gradle Android plugin; or if a later phase seems to need Hilt, which this plan forbids.
- **Tail ownership:** The implementer owns build, test, and commit. No Play Store, F-Droid, or signed-release work.

This is the Android sibling of Mini Audiobookshelf, the GTK4 client. Product behavior that both share is catalogued in `features.md`. This phase does not implement those features. It locks the stack so the rest of the plans have a place to land.

## Product Contract

### Summary

A GPL-3.0-or-later Android app with application id `io.github.miniaudiobookshelf.android`, minSdk 26, targetSdk 36. Two Gradle modules: `:core` is Kotlin JVM with JUnit; `:app` is Compose and will later own Media3, WorkManager, and the Keystore. A clean checkout builds a debug APK and runs `:core:test`.

### Problem Frame

The GTK client already proved the Audiobookshelf contract: JWT auth from 2.26.0, public session streams, session-only progress writes, whole-book time. Copying that into Android as one blob is how you get an untestable Activity with OkHttp calls in composables.

The split that worked on desktop is a GTK-free core library plus a player plus views. The Android split is the same idea with different names. `:core` has to be a JVM module, not `android { }`, or every parser test will drag in Robolectric and the "we will test policy without a device" claim dies on day one.

### Requirements

- R1. The project builds a debug APK from a clean checkout with the Gradle wrapper, without Android Studio.
- R2. Application id is `io.github.miniaudiobookshelf.android`. Namespace for app code is `io.github.miniaudiobookshelf.android`. Namespace for core is `io.github.miniaudiobookshelf.core`.
- R3. minSdk is 26. targetSdk and compileSdk are 36.
- R4. `:core` is a Kotlin JVM library. It has no `android` plugin, no Android SDK types, and no Compose. JUnit 5 tests run with `./gradlew :core:test`.
- R5. `:app` depends on `:core`. The Application class is the composition root. Views do not construct core services through a global `object`.
- R6. The app launches one empty Material 3 screen. System light/dark is followed. No connect UI yet.
- R7. License is GPL-3.0-or-later. `COPYING` is the GNU GPL v3 text. README states the license, the 2.26.0 server floor, and how to build.
- R8. A GitHub Actions workflow on push builds the debug APK and runs `:core:test`.
- R9. Later phases must be able to add OkHttp, kotlinx.serialization, Media3, DataStore, EncryptedSharedPreferences, Coil, and WorkManager without renaming modules or the application id.

### Acceptance Examples

- AE1. Clean build
  - **Covers:** R1, R3
  - **Given:** a machine with JDK 17+ and the Android SDK.
  - **When:** `./gradlew :app:assembleDebug :core:test`.
  - **Then:** a debug APK is produced and the core test suite reports at least one passing test.

- AE2. Core stays JVM
  - **Covers:** R4
  - **Given:** a new type is added under `:core`.
  - **When:** that type is compiled.
  - **Then:** importing `android.*` or `androidx.*` fails the `:core` compilation. App-only types live in `:app`.

- AE3. Single process at launch
  - **Covers:** R5, R6
  - **Given:** the debug APK is installed.
  - **When:** the launcher icon is tapped.
  - **Then:** one Activity shows an empty Scaffold with the app name in the top bar, and `adb shell ps` does not show a second app process invented by WorkManager or a remote MediaSession.

### Scope Boundaries

**In scope**

Gradle wrapper, version catalog, `:core` and `:app`, empty Compose window, composition root, one JUnit test, GPL files, README, `.gitignore`, debug CI.

**Deferred to later phase plans**

Sign-in, TLS, browsing, playback, MediaSession, downloads, icons beyond a stock launcher placeholder, release signing, Play Store, F-Droid, Android Auto.

**Outside this product's identity**

A general music player. Server administration. Supporting Audiobookshelf older than 2.26.0. Flutter. Hilt.

### Threat Model

This phase stores nothing. The useful claim is negative: do not invent a process split or a logging helper that later phases will have to claw secrets out of.

**Defended against, starting now**

- A second process writing the same refresh token. WorkManager and MediaSession stay in the default process. KTD4.
- Secrets in logcat from a homemade HTTP logger. No HTTP client in this phase. When phase 002 adds one, redaction is required there.

**Not defended against, by design**

Any app on the device with accessibility or root. Same-user process inspection, as on the desktop keyring.

## Planning Contract

### Key Technical Decisions

- KTD1. **Kotlin, Jetpack Compose, Material 3, Gradle version catalog.** Governs R1, R9. Native Android, matching the native GTK client. Not Flutter. Versions live in `gradle/libs.versions.toml` so phase 004 can add Media3 without hunting hardcoded strings.

- KTD2. **Two modules, `:core` JVM and `:app` Android, constructor injection, no Hilt.** Governs R4, R5. The GTK plan's KTD7 was a GTK-free static library for the same reason: policy tests without a GUI toolkit. Hilt would pull the Android Gradle plugin into test wiring and make a JVM module awkward. `MiniAbsApplication` constructs real collaborators. Tests construct fakes.

- KTD3. **JDK 17, Kotlin 2.x, Compose BOM current with AGP 8.9+.** Governs R1, R3. minSdk 26 is Android 8. That is old enough for `mediaPlayback` foreground services and EncryptedSharedPreferences, and it drops the long tail of WebView-era devices. target 36 is what a 2026 Play-bound app would need even though Play is deferred.

- KTD4. **One process.** Governs R5 and later credential/download work. Do not set `android:process` on WorkManager, a download service, or MediaSessionService. Two processes rotating one refresh token is the failure KTD18 in the GTK plan existed to prevent.

- KTD5. **GPL-3.0-or-later, same as the GTK client.** Governs R7. Copy `COPYING` from the desktop repo rather than rewriting it.

- KTD6. **Application id `io.github.miniaudiobookshelf.android`.** Governs R2. Sibling of `io.github.miniaudiobookshelf.MiniAudiobookshelf`. Changing it later invalidates backups, Keystore aliases, and any future MediaSession package checks. Finalize before a public release; do not bikeshed during U1.

### High-Level Technical Design

```text
abs-mini-android/
├── COPYING
├── README.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/libs.versions.toml
├── gradle/wrapper/
├── .gitignore
├── .github/workflows/ci.yml
├── core/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/io/github/miniaudiobookshelf/core/
│       │   └── Placeholder.kt
│       └── test/kotlin/io/github/miniaudiobookshelf/core/
│           └── PlaceholderTest.kt
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/io/github/miniaudiobookshelf/android/
        │   ├── MiniAbsApplication.kt
        │   ├── MainActivity.kt
        │   └── ui/AppRoot.kt
        └── res/values/strings.xml
```

`Placeholder.kt` is a one-liner so the test module is not empty. Phase 002 replaces it with real types. Do not invent a fake domain layer to look busy.

`MiniAbsApplication` is empty of services in this phase except a comment marking it as the composition root. Phase 002 puts the settings store, API client, and credential manager there.

### Assumptions

- JDK 17 or 21 is on PATH. The wrapper uses 17 bytecode.
- The Android SDK with platform 36 is installed. CI uses `android-actions/setup-android` or the equivalent.
- The desktop client's `COPYING` file is readable at `/home/usr1/Projects/mini-audiobookshelf/COPYING` while writing the first commit. If it is not, fetch GPL-3.0 from gnu.org rather than paraphrasing.

## Implementation Units

### Unit Index

| U-ID | Title | Key files | Depends on |
|---|---|---|---|
| U1 | Gradle wrapper, modules, version catalog | `settings.gradle.kts`, `gradle/libs.versions.toml`, `core/`, `app/` | none |
| U2 | Empty Compose window and composition root | `MiniAbsApplication.kt`, `MainActivity.kt`, `AppRoot.kt`, manifest | U1 |
| U3 | Core test, license, README, CI | `PlaceholderTest.kt`, `COPYING`, `README.md`, `.github/workflows/ci.yml` | U1 |

### U1. Gradle wrapper, modules, version catalog

- **Goal:** `./gradlew tasks` works. `:core` is `kotlin("jvm")`. `:app` is `com.android.application` plus Compose.
- **Requirements:** R1, R2, R3, R4, R9
- **Files:** wrapper properties, root and module Gradle files, `gradle.properties` (`android.useAndroidX=true`, non-transitive R), `.gitignore` for `.gradle/`, `build/`, `local.properties`, `.idea/`, `*.apk`, `*.keystore`.
- **Approach:**
  1. `settings.gradle.kts` includes `:app` and `:core`. Repositories: Google and Maven Central only.
  2. Catalog entries now: AGP, Kotlin, Compose BOM, Compose compiler, JUnit 5, activity-compose, core-ktx. Leave commented stubs out. Add libraries when the phase that needs them lands.
  3. `:core` `build.gradle.kts` uses `java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }` and JUnit 5.
  4. `:app` sets `applicationId`, `minSdk 26`, `compileSdk 36`, `targetSdk 36`, `versionCode 1`, `versionName "0.1.0"`. `buildFeatures { compose = true }`.
- **Verification:** `./gradlew :core:compileKotlin :app:compileDebugKotlin` succeeds.

### U2. Empty Compose window and composition root

- **Goal:** Tapping the icon shows a Scaffold.
- **Requirements:** R5, R6
- **Files:** `AndroidManifest.xml` with `android:name=".MiniAbsApplication"`, single `MainActivity` exported for the launcher, `exported="true"` only on that Activity. No extra permissions yet. INTERNET arrives in phase 002.
- **Approach:**
  1. `MainActivity` is `ComponentActivity`. `setContent { AppRoot() }`.
  2. `AppRoot` uses `MaterialTheme` with dynamic color on API 31+ and a static fallback below that. TopBar title from `R.string.app_name` = "Mini Audiobookshelf".
  3. Do not add Navigation Compose yet. Phase 002 introduces a single back stack when Connect exists.
- **Verification:** Install the debug APK. The window appears. Back finishes the Activity.

### U3. Core test, license, README, CI

- **Goal:** The test command and CI are real, not a README promise.
- **Requirements:** R4, R7, R8
- **Approach:**
  1. `PlaceholderTest` asserts `1 + 1 == 2` so the harness is proven. Delete it when the first real core test lands in phase 002.
  2. Copy GPL v3 into `COPYING`. README: what the app will be, server 2.26.0+, `./gradlew :app:assembleDebug :core:test`, license line, pointer at `docs/plans/`.
  3. Workflow: JDK 17, Android SDK, `./gradlew :core:test :app:assembleDebug --stacktrace`. Cache Gradle. Do not upload the APK as a public artifact until there is something worth sideloading.
- **Verification:** `./gradlew :core:test` is green locally. Pushing the branch runs the workflow.

## Risks and Dependencies

| Risk | Impact | Mitigation |
|---|---|---|
| Application id changes after Keystore aliases exist | Users must sign in again; downloads in app storage become orphaned | KTD6. Do not rename after phase 002. |
| `:core` quietly becomes an Android library | Policy tests need a device or Robolectric | R4. Refuse `com.android.library` on `:core`. |
| WorkManager default config later uses a remote process | Dual writers of the refresh token | KTD4. Default process only. |

**Dependencies:** JDK 17+, Android SDK 36, Gradle wrapper 8.13+ or whatever AGP 8.9 requires.

## Open Questions

- OQ1. Final application id before a public listing. Default is `io.github.miniaudiobookshelf.android`. Same situation as desktop OQ4.
- OQ2. Whether CI should run on macOS as well as Linux. Default: Ubuntu only.

## Sources

- Desktop scaffold and composition-root rule: `mini-audiobookshelf` docs/plans/2026-08-26-001 KTD7, KTD16, U1.
- Product catalog: `features.md` in this repo.
- License: GNU GPL v3, same file as the GTK client.
