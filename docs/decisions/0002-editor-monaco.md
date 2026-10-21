# 0002 — Editor stack (Gate B)

Status: accepted (plan, PoC gated) · Date: 2026-10-17

## Context

The editing surface must be VS Code-grade on a phone, work fully offline, and
avoid shipping an Android software keyboard integration bug surface.

## Decision

- **Monaco editor** (the VS Code editor), supplied as a **pinned release
  bundled into the repository** under `app/src/main/assets/` for
  reproducible offline operation. The exact version + downloaded artifact
  checksum will be recorded here and in THIRD_PARTY_NOTICES.md at M2.
- Assets are served to a `WebView` through **`WebViewAssetLoader`**
  (`appassets.androidplatform.net`), avoiding `file://` restrictions and
  enabling Monaco web workers.

## M2 pin record (supersedes the estimation note below)

- **Pinned version: monaco-editor 0.57.0** (MIT, Microsoft Corporation).
- Official npm tarball:
  `https://registry.npmjs.org/monaco-editor/-/monaco-editor-0.57.0.tgz`
  sha256 `3ea1712fbacd3290cf4751007e3a5b57cc279767607812d4c3e57925fb0b05c2`,
  verified byte-for-byte against the registry-published dist.integrity
  sha512 before extraction (2026-10-21).
- Bundled subset: `package/min/vs` verbatim (editor core, all Monarch
  language grammars, css/html/json/typescript language services, all
  worker bundles, loader, nls) + `package/LICENSE`, under
  `app/src/main/assets/monaco/`; provenance metadata with per-file hashes
  lives at `app/src/main/assets/monaco/monaco-pinned.json` and is
  enforced by a JVM provenance test.
- Measured bundle size on disk: **25 MB** (real-world 0.57.0 release
  layout; supersedes the 3–8 MB estimate below — accepted for
  reproducibility and offline operation).
- Because Monaco's Android WebView keyboard behavior is a known risk
  (github.com/Microsoft/monaco-editor/issues/4946 — keyboard does not open
  on tap in some WebView versions), M2 implements and tests a dedicated
  **IME bridge**: a hidden input surface relays composition and key events
  into the editor, plus a symbol toolbar for `{}()[]<>;=Tab/Esc/arrow keys`.

## Acceptance criteria (M2, verified on device)

1. Loads and renders offline in airplane mode.
2. Soft keyboard opens on editor tap; text insertion works.
3. Cursor movement (arrows on code toolbar), selection, indentation work.
4. Symbol shortcuts insert at the caret.
5. Save persists via the Kotlin bridge; reopen shows persisted content.
6. JS↔Kotlin bridge demonstrably round-trips content.

## Consequences

- No language servers in v1; Monaco's built-in Monarch highlighting covers
  all popular languages, with TS/JS/HTML/CSS/JSON having native IntelliSense.
- Monaco is ~3–8 MB of committed assets — accepted for reproducibility.
