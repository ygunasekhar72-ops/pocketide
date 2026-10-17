# 0003 — UI foundation (M0.5)

Status: accepted · Date: 2026-10-17

## Scope

M0.5 delivers the Compose UI foundation only: design system, shell
navigation, and placeholder surfaces for the editor/terminal/AI that are
**honest about their missing engines**. No PTY, no Monaco, no Git, no AI.

## Decisions

1. **Navigation: library-free `NavigationState`** (StateFlow stack) instead of
   androidx.navigation-compose. Justification: five bottom-tab siblings plus
   one detail push (open project → Editor); adding a nav XML/graph library
   for that complexity buys nothing. Revisit only if M3+ introduces
   backstacked domain screens.
2. **Dark-only theme** in M0.5: PocketTheme always applies the dark charcoal
   scheme; light theme is deferred (decision pending) to avoid shipping a
   half-tested second palette.
3. **ViewModel wiring via `lifecycle-viewmodel-compose`** (same 2.8.7 version
   already present transitively through our pinned lifecycle family — not a
   new library family, coordinate verified).
4. **Monospace glyph icons** (`MonoIcon`) instead of `material-icons-extended`
   (multi-MB artifact not justified for five tab symbols).
5. **Honest placeholders**: every control that depends on an engine not yet
   integrated is either disabled with a milestone badge (M1/M2/M3/M6/M7) or
   removes a real capability. No fabricated output anywhere.
6. **Real local persistence for Home**: projects are actual directories under
   `filesDir/workspace` via `ProjectRepository` (create/delete/list, RGC-free
   pure JVM code, validated names, traversal-safe). This is the same path the
   M1 terminal must mount as its CWD — recorded as the shared-workspace
   contract.

## Testing

- JVM: NavigationState, ProjectRepository, WCAG contrast of core token pairs.
- Robolectric + Compose (`@Config(sdk=[35])`): shell tab switching, screen
  placeholder rendering, home empty/content states.

## Limitations recorded

- Robolectric requires `isIncludeAndroidResources = true` and adds
  first-run CI download of android-all jars (~100 MB, cached by Gradle).
- Light theme, external-storage projects, and a SAF picker are post-M3.
