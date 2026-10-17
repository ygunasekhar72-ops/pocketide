# 0001 — Terminal stack (Gate A)

Status: accepted (baseline) · Date: 2026-10-17

## Context

The originally proposed dependency for the integrated terminal was
**libtermux-android** (`com.github.libtermux:libtermux-android:1.0.0`).

## Feasibility-gate evidence

1. Repository `github.com/libtermux/libtermux-android` is Apache-2.0 but tiny
   (~16 stars, ~55 commits, 2 forks) and its README states verbatim that it is
   "currently in an experimental stage… not usable in production".
2. The README-documented coordinate `com.github.libtermux:libtermux-android:1.0.0`
   does **not resolve** — `https://jitpack.io/com/github/libtermux/libtermux-android/1.0.0/`
   returns HTTP 404; the JitPack index lists only ad-hoc commit-hash builds,
   no releases/tags. Documentation also references a second, inconsistent
   group (`com.aeoncorex:libtermux-core`).
3. Mechanically the library downloads a bootstrap (~30 MB) at runtime; it does
   not package a Linux environment into the APK as our goals require.

Verdict: **rejected as a committed dependency.**

## Decision

Ship a layered terminal stack in which every layer is independently verified:

1. **PTY**: original ~small JNI C wrapper using `posix_openpt`/`forkpty`
   (Apache-2.0, our own code). Treat as unproven until M1 acceptance tests pass.
2. **Shell**: Android's built-in `/system/bin/sh` (mksh) + built-in `toybox`
   coreutils. Zero downloads, zero licensing burden, offline from first launch.
3. **Terminal emulator view**: vendored `emulatorview` from
   `jackpal/Android-Terminal-Emulator` (Apache-2.0, ~3.2k stars; development
   ended 2017 — acceptable because we vendor and pin the source).
4. **Optional development runtimes** (Python/Node/glibc-style toolchains):
   first-run, explicit-consent download of the official **Alpine aarch64
   rootfs (~10 MB)** run under **proot** in app-private storage. Nothing GPL
   is redistributed inside the APK; the download is fetched from official
   mirrors at runtime.
5. **Git UI** uses **JGit** (pure Java, EPL) and is independent of any CLI.

## Consequences

- Baseline terminal is guaranteed to work offline on any Android ≥ 8 device.
- `mksh`/`toybox` limitations (no python/node/pre-built toolchain, no
  `apt`) are documented as known limitations, mitigated by the optional M7
  runtime layer.
- GPL obligations are avoided for the distributed APK; GPL software may still
  run (downloaded at runtime) without GPL redistribution.
