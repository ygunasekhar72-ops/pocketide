# Third-Party Notices

Verified third-party components used by PocketIDE. Every entry records the
coordinate/version, license, source repository and verification evidence before
the dependency is added to the build. Entries are appended per milestone.

Verification method: HTTP status check against the artifact/POM URL on the
official registry (Google Maven / Maven Central / GitHub raw) performed during
the milestone in which the dependency is introduced.

## Build toolchain (M0)

| Component | Version | License | Source | Verified |
|---|---|---|---|---|
| Gradle | 8.11.1 | Apache-2.0 | https://services.gradle.org/distributions/ | distribution fetched via gradle-wrapper.properties |
| Gradle wrapper scripts | v8.11.1 tag | Apache-2.0 | https://github.com/gradle/gradle/tree/v8.11.1 | raw files fetched HTTP 200 (gradlew, gradlew.bat, gradle-wrapper.jar) |
| Android Gradle Plugin | 8.7.3 | Apache-2.0 | https://dl.google.com/android/maven2 | 200 on `com.android.application` 8.7.3 POM |
| Kotlin (kotlin-android + compose plugins) | 2.0.21 | Apache-2.0 | https://repo1.maven.org/maven2/org/jetbrains/kotlin/ | 200 on `kotlin-gradle-plugin` 2.0.21 POM |

## Runtime libraries (M0)

| Component | Version | License | Source | Verified |
|---|---|---|---|---|
| androidx.core:core-ktx | 1.15.0 | Apache-2.0 | Google Maven | 200 on POM |
| androidx.lifecycle:lifecycle-runtime-ktx | 2.8.7 | Apache-2.0 | Google Maven | 200 on POM |
| androidx.activity:activity-compose | 1.9.3 | Apache-2.0 | Google Maven | 200 on POM |
| androidx.compose:compose-bom | 2024.12.01 | Apache-2.0 | Google Maven | 200 on POM |
| androidx.compose.ui:ui | via BOM | Apache-2.0 | Google Maven | transitive via BOM |
| androidx.compose.material3:material3 | via BOM | Apache-2.0 | Google Maven | transitive via BOM |
| junit | 4.13.2 | EPL-1.0 | Maven Central | standard test scope |

## Runtime libraries (M0.5)

| Component | Version | License | Source | Verified |
|---|---|---|---|---|
| androidx.lifecycle:lifecycle-viewmodel-compose | 2.8.7 (matches lifecycle family) | Apache-2.0 | Google Maven | 200 on POM |

## Test-only libraries (M0.5)

| Component | Version | License | Source | Verified |
|---|---|---|---|---|
| androidx.test.ext:junit | 1.2.1 | Apache-2.0 | Google Maven | 200 on POM |
| androidx.compose.ui:ui-test-junit4 | BOM 2024.12.01 (1.7.6) | Apache-2.0 | Google Maven | 200 on POM (ui-test-junit4-android) |
| androidx.compose.ui:ui-test-manifest | BOM 2024.12.01 (1.7.6) | Apache-2.0 | Google Maven | 200 on POM |
| org.robolectric:robolectric | 4.14.1 | MIT | Maven Central | 200 on POM |

Robolectric downloads android-all jars at test runtime (~100 MB, Gradle-cached).


## Runtime libraries (M1-P2)

| Component | Version | License | Source | Verified |
|---|---|---|---|---|
| org.jetbrains.kotlinx:kotlinx-coroutines-core | 1.10.1 | Apache-2.0 | Maven Central | 200 on `kotlinx-coroutines-core-1.10.1.pom` and `kotlinx-coroutines-core-jvm-1.10.1.pom` (2026-10-18) |

## Test-only libraries (M1-P2)

| Component | Version | License | Source | Verified |
|---|---|---|---|---|
| org.jetbrains.kotlinx:kotlinx-coroutines-test | 1.10.1 | Apache-2.0 | Maven Central | 200 on `kotlinx-coroutines-test-1.10.1.pom` (2026-10-18) |


## Pending gates (to be verified before introduction)

- **M1 terminal**: vendored `emulatorview` (jackpal/Android-Terminal-Emulator,
  Apache-2.0, verified 2026: repo active-archived, license checked). Custom JNI
  PTY code is original Apache-2.0. No GPL/code-server/Termux-runtime-packaging.
- **M2 editor**: Monaco editor, pinned release downloaded from npm dist,
  checksum recorded here; MIT license.
- **M7 optional runtime**: Alpine aarch64 rootfs (official mirror) + proot,
  downloaded on-device at first run (not redistributed inside the APK).
