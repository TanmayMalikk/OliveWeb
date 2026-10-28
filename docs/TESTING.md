# Running Olive tests

## One-command test run

Open Android Studio's **Terminal** tab at the project root and run:

```powershell
.\test-all.bat
```

This automatically runs:

1. Android/Kotlin JVM unit tests, debug and UI-test APK builds, lint, and architecture checks.
2. Web unit tests, a production build, Playwright user flows, responsive checks, and axe accessibility checks.
3. Cloud Functions unit tests and a production-dependency security audit.
4. Firestore and Firebase Storage rules tests in local emulators.

The script stops at the first failure and returns a nonzero exit code. A successful run ends with `All requested Olive tests passed.` It uses Android Studio's bundled Java runtime when that runtime is installed in the standard location.

## Include phone or emulator UI tests

Start a Pixel emulator or connect an Android device with USB debugging enabled. Then run:

```powershell
.\test-all.bat -WithDeviceTests
```

The disposable emulator must be unlocked and use PIN `246810` so the device-credential tests can exercise the real Android prompt. Device tests run one method per instrumentation process to prevent Android 17 activity state from leaking between unrelated workflows. Assertion failures stop immediately; only a confirmed Android framework process crash is retried once.

## Faster runs while editing

Run only Kotlin/JVM unit tests:

```powershell
.\gradlew.bat testDebugUnitTest
```

Run only Cloud Functions tests:

```powershell
cd functions
npm test
```

Run everything except Firebase rule-emulator tests:

```powershell
.\test-all.bat -SkipFirebaseRules
```

## Android Studio buttons

To run one test, open its Kotlin test file and click the green triangle beside the class or test method. To run all JVM tests, open the **Gradle** panel and run `app > Tasks > verification > testDebugUnitTest`.

The device tests are separated by feature under `app/src/androidTest/java/com/olive/app`: onboarding, cover/account, privacy/security, settings, checkout, and standalone cover behavior. `OliveUiTestBase.kt` contains only their shared setup and common UI actions.

For repeatable automatic checks, add `test-all.bat` as an Android Studio external tool or Run Configuration. Set the program to the project's `test-all.bat` file and the working directory to the project root. Running that configuration performs the same complete check every time.
