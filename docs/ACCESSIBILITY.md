# Accessibility verification

Olive uses Material controls and Compose semantics for TalkBack. Custom PIN controls,
onboarding progress, graph navigation, statistical plots, status messages and icon
labels provide additional spoken information. Decorative artwork stays silent.

## Privacy and inactivity

- Hidden cover content is removed from accessibility traversal. An inactive news
  WebView also hides its native accessibility descendants.
- Capture blackouts hide the entire Compose host. Background privacy covers hide
  the activity content until the locked screen has redrawn.
- Quick Exit stays available inside the modal chat introduction, which isolates
  background controls. Leaving the safe area removes that dialog immediately.
- Accessibility focus, clicks, long clicks, text editing and reading by movement
  granularity count as interaction. Background content updates and automatic
  scrolling do not keep a private session unlocked.
- The default idle policy remains a warning after one minute and locking fifteen
  seconds later. Android 10+ accessibility timeout recommendations can extend the
  warning response time. Quick Exit and locking on background remain immediate.

## Automated checks

Run JVM tests and build local test APKs:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest -PoliveLocalTest=true
```

Run the instrumented suite on an emulator with fictional accounts. It clears local
test stores; do not run it on a device containing real accounts. Accessibility tests
cover PIN announcements and actions, localized labels, blackout isolation, modal
Quick Exit and graph pan/zoom. Existing tests cover onboarding, account access,
checkout, settings, persistence and the article reader.

For automated UI runs, disable the emulator's window, transition and animator
duration scales, following [Android's Espresso setup guidance](https://developer.android.com/training/testing/espresso/setup).
Restore normal animation settings for the TalkBack walkthrough below.

## TalkBack release checks

Use Android Accessibility Suite on a test phone or emulator. Check swipe navigation,
explore by touch and double-tap activation, rather than only tapping screen positions.

1. Complete both new-account and returning-account setup. Confirm that headings,
   requirements, progress, fields, visibility controls and errors are understandable.
2. Enter, delete and submit a PIN. The indicator must announce only the number of
   digits entered, never the complete PIN. Confirm immediate retry and Quick Exit.
3. Navigate notes, contacts, settings, messages and account pages. Check reading
   order, announced status changes, dropdowns, dialogs, back navigation and scrolling.
4. Open a chat introduction. Background controls must not be reachable until the
   tip closes, and Quick Exit inside the tip must work.
5. Read private content, then Quick Exit, background the app and trigger a capture
   blackout. Ensure hidden private content cannot be reached or newly announced.
6. Check idle locking while navigating with TalkBack. In Android's accessibility
   settings, increase "Time to take action" and verify the warning respects it.
7. Customize food ingredients, toggle billing address reuse and complete the
   checkout test flow. Each checkbox must announce its label and checked state once.
8. Calculate normally and with scientific operators. Use the graph's accessibility
   actions to zoom and pan; compare its table and analysis with the visual graph.
9. Open news articles, bookmark, follow related links and return. Publisher HTML
   has its own accessibility quality; check representative publishers separately.
10. Repeat key flows in English, French, Spanish, Arabic and Simplified Chinese,
    with large text, landscape orientation and a hardware keyboard. This branch
    preserves main's language manager; accessibility labels use its Android resources.

Automated semantics checks do not verify speech pronunciation, reading comfort or
every publisher's web content. Keep a real TalkBack walkthrough in release testing.

## Verification on October 13, 2026

- All 75 JVM tests passed.
- All 33 instrumented tests passed on Pixel 9a / API 37, split into an 8-test
  accessibility/news run and a 25-test regression run.
- Local-test APKs and the normal debug APK built successfully. Lint completed with
  zero errors; existing project warnings remain.
- With TalkBack 17 enabled, the emulator showed accessibility focus on the cover
  and search field, and activation opened the keyboard. This was a limited smoke
  check with audio disabled, not a spoken walkthrough of every screen.
- The release checks above still need a real-device walkthrough, including speech,
  larger fonts, all supported languages and representative publisher pages.
