# Olive Branch quality contract

This document defines the boundaries that keep the Android and web applications reviewable. A feature is complete only when its implementation, accessibility, localization, and tests satisfy these rules.

## Architecture

- A screen renders state and sends user actions. It does not construct repositories or import Firebase directly.
- View models and session controllers coordinate screen state. Repositories own storage, network, database, camera, OCR, and location I/O.
- Feature-specific UI stays with its feature. Shared UI contains only components that are reused with the same meaning and behavior.
- Small private composables may remain beside their screen. A standalone file needs a clear owner or a reusable public purpose.
- Handwritten source files stay below 500 lines. Splits follow responsibility rather than arbitrary line ranges.
- Production routes must be reachable. Test fixtures and test-only screens belong in test source sets.

The web safe area follows this split:

```text
src/users/shared/safe_app/SafeApp.tsx  signed-in app navigation
src/users/shared/safe_app/             Home, Profile, Settings, Activity, Messages, Safety Plan
src/users/user/safe_app/               Notes and Contacts
src/session.tsx             account workflows and shared session state
src/useSessionSecurity.ts   exit, visibility, history and idle-lock behavior
src/covers/                 one controller and focused renderer/form components per cover
src/styles/                 styles grouped by page or cover
```

Android currently keeps shared Compose primitives in `com.olive.app.ui`, cover UI under `ui.covers`, repositories under `data`, OCR under `ocr`, and location integration under `location`. New work must preserve those dependency directions while feature packages are migrated incrementally.

## Accessibility

- Use Material controls and their built-in semantics before creating a custom control.
- Every meaningful action has a concise localized name, role, state, and at least a 48 dp target.
- Decorative icons and images use a null content description.
- Status and validation changes use Compose live regions. They must not force accessibility focus away from the user's current position.
- Dialogs and expanded menus hide underlying actions and restore a sensible interaction point when closed.
- Do not rely on color, position, animation, dragging, or a canvas alone to communicate essential information.
- Camera, OCR, maps, charts, media, and games require labelled non-visual controls and spoken status.
- PIN semantics expose only the number of entered digits, never the digits themselves.
- Verify critical flows with TalkBack, keyboard or Switch Access, 200% text, and an RTL locale.

Follow the Android accessibility principles at <https://developer.android.com/guide/topics/ui/accessibility/principles>.

## Localization

- User-facing copy, descriptions, states, errors, and action labels come from resources.
- Format a complete sentence in one resource; do not concatenate translated fragments.
- Use plurals and locale-aware date, number, percentage, distance, and currency formatting.
- Resource placeholders must match across English, French, Spanish, Arabic, and Simplified Chinese.
- Technical notation and brand names may remain identical. Ordinary prose requires a reviewed translation.
- Test `en-XA` for expansion and `ar-XB` or Arabic for right-to-left layout before release.

## Verification

Run the complete local suite from the repository root:

```powershell
.\test-all.bat
```

Run instrumented Android tests when an unlocked device or emulator is available:

```powershell
.\test-all.bat -WithDeviceTests
```

The complete check includes Android unit tests, APK compilation, lint, source boundaries, architecture boundaries, web unit tests, the web production build, browser accessibility tests, Cloud Functions tests and dependency audit, and Firebase rules tests.

Before merging, the suite must pass twice after the final source change. A flaky test is a defect and cannot be hidden with retries.
