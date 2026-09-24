# Validation — 18 September 2026

## Version 0.2 — local account and safe-area flows

- Updated debug APK assembled successfully with the same Android Studio project and Olive branding.
- Eight JVM tests passed (four catalogue/money tests and four PIN, cooldown, trigger and input-validation tests).
- Six Compose UI tests passed on the Pixel 9a Android 17 emulator: existing cover flows plus new/returning account setup, contacts added/edited and persisted, PIN history and five-attempt cooldown across recreation, retained cart, background locking and quick exit during an in-flight unlock.
- Sensitive-window protection was asserted during a safe session. The test vault was checked for absent plaintext profile/credential strings; key management/encryption still requires specialist review before real-world use.
- Android lint: zero errors, five dependency-version suggestions. No lint checks were disabled.
- Installed and launched the updated APK on the user's running Pixel 10 emulator for a startup smoke check. No physical-device verification has occurred.
- Firebase setup and rule-emulator testing were deferred at the user's request. All functional account tests used installation-local encrypted demo accounts, not a live backend. Cross-device account recovery remains pending backend integration.
- Validation outputs were redirected to `work/olive-build` to avoid Windows file locks from Android Studio indexing. The standard project output directory remains unchanged.

## Previous version 0.1 checks

Local environment: Android Studio bundled JDK 25, Gradle 9.5.0, AGP 9.3.3, compile/target SDK 37.

- Debug APK assembled successfully.
- Four JVM unit tests passed: search normalization, combined category/search, integer-cent totals, negative quantity handling.
- Three Compose UI tests passed on each of Pixel 9a and Pixel 10 emulators running Android 17 (six executions, zero failures). Flows cover search/clear, add-to-cart/unavailable checkout/removal, and save/unsave.
- Home screen inspected from emulator screenshots; selected UI controls use the Olive palette.
- Android lint completed with zero errors. Version-update suggestions are retained because the build uses a tested, pinned toolchain.
- Explicit backup/transfer exclusion rules were added after lint identified the Android 12+ configuration requirement.

The first lint run stalled and was restarted. The first device-test run exposed an Espresso compatibility issue on Android 17; explicitly using Espresso 3.7.0 resolved it. No app assertions were weakened.

These are emulator checks, not physical-device testing. GitHub Actions is configured but has not run on a remote repository. Firebase, release signing, hosted downloads, and safety functionality are not part of this validation.

Reproduce unit/lint/build checks with `./gradlew assembleDebug testDebugUnitTest lintDebug`. With a running emulator, use `./gradlew connectedDebugAndroidTest` for the UI flows.

## Firebase connection — 2026-09-18
- Project: olive-21239; default Firestore Standard database: us-west1, free tier.
- Android Firebase-configured assembleDebug and unit tests passed.
- Two Firestore emulator rule suites passed; owner-only rules deployed successfully.
- Live check passed: temporary account registration, profile write, anonymous access denial, separate-client login and profile restore. Temporary account/profile removed.
- Physical-device cross-device verification remains outstanding.


## Username and password policy — 2026-09-18
- Signup remains username/password only; usernames normalize to lowercase using Locale.ROOT.
- Minimum password length is 8 characters in Android and live Firebase policy; password case is preserved.
- Build and unit tests passed. Live tests verified 8-character signup, duplicate normalized username rejection, wrong-case password rejection, and profile restoration. Temporary test data removed.


## Profile, settings, delivery and checkout — 2026-09-19

- Firebase-connected debug build and 9 JVM tests passed; lint: 0 errors, 5 warnings, 1 hint.
- Eight Compose UI flows passed across the cover run and targeted session runs on Pixel 9a / Android 17: cover search/cart/favourites, onboarding and returning login, contact edits, five-attempt auto-exit and persisted history, background/in-flight exit locking, checkout decline and disposal of payment fields, French settings, four-digit PIN, trigger change and address persistence.
- An initial onboarding timeout exposed form buttons overlapped by Quick exit. The floating control now has reserved bottom space; targeted reruns passed. An earlier run was also interrupted by emulator startup/connection loss.
- Four Firestore emulator rule groups passed, including required fields, address/payment-field whitelisting, owner-only access and legacy username compatibility. Rules deployed to olive-21239.
- Live Firebase checks passed: 8–24 password policy, registration with an eight-character password, duplicate username rejection, password case sensitivity, anonymous profile denial and separate-client profile/address restoration. Temporary accounts and profiles removed.
- Android Studio Gradle sync completed successfully.
- Restaurant provider proxy and matching-photo contract are prepared, not connected to a live provider. Only English and French are supported. Physical-device testing remains outstanding.

## No PIN cooldown — 2026-09-19

- Removed timed PIN lockouts from unlock and trigger-change verification. The fifth failure returns to the cover and resets the counter; retry is immediately available.
- Pixel 9a targeted Compose test passed: fifth-failure exit, immediate retry with a fresh counter, private history containing all six failures, recreation, and successful unlock despite a future cooldown saved by an older version.
- Firebase-connected debug build, JVM tests and lint completed successfully.
- Earlier cooldown validation above describes superseded behavior.

## Firebase Storage account profiles — 2026-09-19

- Android cloud profile persistence now uses `accounts/{uid}/profile.json` in Firebase Storage. Firebase Authentication remains responsible for username aliases and password credentials; device PINs and failed-attempt history remain encrypted locally.
- Storage rules compiled and were deployed to `olive-21239`. Rules allow only the authenticated owner, require the exact profile path and JSON content type, cap objects at 64 KiB, and deny all other paths.
- Six emulator rule groups passed: four existing Firestore regression groups and two Storage groups covering owner access, cross-account/anonymous denial, path restriction, content type and size.
- The Firebase-connected debug build, JVM tests and lint passed after replacing the Android Firestore profile adapter with Firebase Storage.
- The old Firestore `users` collection was recursively deleted. One existing Authentication account was identified; final deletion awaits Firebase Console sign-in and action-time confirmation.

## Cover profile and language settings — 2026-09-19

- The cover Account page now displays only full name, username, delivery address and app language. DOB, affected names, situation, safe contacts, trigger details, PIN data and attempt history are not rendered there.
- Full name, delivery address and English/French language are editable from the cover. Language and name changes update the same encrypted local profile and account-scoped cloud profile used by safe settings.
- A targeted Pixel 10 Compose test passed, covering visible delivery-profile fields, absence of DOB and trigger text, cover-side name/language editing, French cover navigation and persistence into the unlocked safe profile.
- The same flow verifies that a successful delivery-address save automatically closes the address dialog.

## Recents locking and onboarding detail — 2026-09-20

- `onUserLeaveHint` now locks the safe interface and switches state to the cover as Android begins leaving for Home or the recent-apps overview. `onPause` remains as the fallback, and secure-window protection remains enabled during the transition.
- Onboarding now asks for the optional name of the person causing harm. The value is stored in the private profile, shown only inside the safe profile, and remains editable in safe settings.
