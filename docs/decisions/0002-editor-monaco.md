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
