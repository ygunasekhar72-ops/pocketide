# PocketIDE

A standalone Android coding IDE (VS Code-inspired) — no Termux, no desktop
machine, no remote server required. Kotlin + Jetpack Compose + bundled Monaco
editor + integrated terminal + Git + an AI agent loop with approval boundaries.

- Name: **PocketIDE**
- Application ID: `com.pocketide.mobile`
- License: **Apache-2.0** (see `LICENSE`, `NOTICE`, `THIRD_PARTY_NOTICES.md`)

## Delivered capability (by design)

| Capability | Approach |
|---|---|
| Editor | Monaco (pinned release) in WebView via `WebViewAssetLoader`; custom IME bridge for mobile keyboards |
| Terminal | Own JNI PTY + Android `mksh` (`/system/bin/sh`) + built-in `toybox` — works offline immediately; Python/Node via optional first-run runtime install (Alpine rootfs + proot, explicit user consent) |
| Git | JGit (pure Java) for locally stage/commit/diff/log; push/pull via CLI |
| AI | OpenAI-compatible provider (BYO key, Keystore-encrypted), tool-executing agent loop with diff/terminal approval gates |
| Delivery | GitHub Actions builds + uploads debug APK artifact on every push; signed release workflow at M8 |

## Current status

- [x] M0 — repository + CI skeleton (this commit)
- [x] M0.5 — Compose UI foundation: design system, shell navigation, home (real workspace projects), honest editor/terminal/AI/settings surfaces
- [ ] M1 — terminal proof of concept (Gate A)
- [ ] M2 — Monaco proof of concept (Gate B)
- [ ] M3 — workspace core (file ops, tabs, autosave)
- [ ] M4 — command palette + project search/replace
- [ ] M5 — Git UI (JGit)
- [ ] M6 — AI agent loop (real tools, real approval)
- [ ] M7 — optional Linux runtime
- [ ] M8 — polish + signed releases

### M0.5 UI foundation (what works vs. what is deliberately not there yet)

Works for real:
- Dark-first design system (charcoal surfaces, purple primary, mint secondary; WCAG-verified token pairs)
- Shell navigation: 5 bottom tabs, detail push/pop, system-back handling, top-bar project context
- Home: create/delete/filter **real local projects** (directories under the app workspace), empty/loading/error states
- Settings: identity, workspace path (copyable), repository link

Deliberate placeholders (unavailable engines are labeled, never simulated):
- Editor: tabs/breadcrumb/accessory-bar layout only — Monaco lands in M2, file access in M3
- Terminal: session header/output/input layout only — real PTY engine lands in M1
- AI: provider status + approval-rules summary only — provider/keys/tool loop lands in M6

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

CI builds an APK artifact on every push to `main` (see `.github/workflows/ci.yml`).

## Decision records

See `docs/decisions/` — architecture choices with verifiable evidence,
in particular why the originally proposed terminal dependency
(libtermux-android) failed its feasibility gate and what ships instead.
