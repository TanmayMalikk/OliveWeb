# Running Olive tests

## One-command test run

Open Android Studio's **Terminal** tab at the project root and run:

```powershell
.\test-all.bat
```

On Linux or macOS, run the equivalent Bash runner from the project root (the script also resolves the project correctly when invoked by path from another directory):

```bash
./test-all.sh
```

Both runners use the same test suites and stop on the first failure. The Bash runner requires Python 3 (standard library only) for Android test discovery and JUnit reporting, plus Node/npm, the Android SDK/JDK, and Chrome for Playwright. Set `JAVA_HOME` for Gradle when Android Studio's runtime is not in a standard location. Optionally set `OLIVE_FIREBASE_JAVA_HOME` to a Java 21 installation for Firebase emulators.

Both commands write the combined report to `test-reports/olive-test-summary.txt`, including when a test fails. Individual JUnit reports and the current/failed check are under `build/reports`.

This automatically runs:

1. Android/Kotlin JVM unit tests, debug and UI-test APK builds, lint, and architecture checks.
2. Web unit tests, a production build, Playwright user flows, responsive checks, and axe accessibility checks.
3. Cloud Functions unit tests and a production-dependency security audit.
4. Firestore and Firebase Storage rules tests in local emulators.

The script stops at the first failure and returns a nonzero exit code. A successful run ends with `All requested Olive tests passed.` It uses Android Studio's bundled Java runtime when that runtime is installed in the standard location.

## Include phone or emulator UI tests

Start and unlock a disposable Android emulator. Then run:

```powershell
.\test-all.bat -WithDeviceTests
```

```bash
./test-all.sh --with-device-tests
# Select a particular disposable emulator:
./test-all.sh --with-device-tests --device-serial emulator-5554
```

Device tests are optional in both runners. They reject physical phones, uninstall the Olive app/test packages on the selected emulator, and configure/clear test PIN `246810`. Use a disposable emulator: its Olive data is erased. The Bash runner finds `adb` through `ANDROID_SDK_ROOT`, `ANDROID_HOME`, `local.properties`, common SDK locations, or `PATH`.

The disposable emulator must be unlocked. The runners set PIN `246810` if it has no lock and remove that test PIN on exit. If it uses another PIN, the credential-dependent methods are skipped. Device tests run one method per instrumentation process to prevent Android 17 activity state from leaking between unrelated workflows. Assertion failures stop immediately; only a confirmed Android framework process crash is retried once.

To verify the Unix runner itself without building the app or contacting a device/backend:

```bash
python3 scripts/tests/test_unix_runner.py -v
```

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

```bash
./test-all.sh --skip-firebase-rules
```

The Bash runner also accepts `-WithDeviceTests`, `-SkipFirebaseRules`, and `-DeviceSerial SERIAL` for compatibility with the Windows command examples. Run `./test-all.sh --help` for usage.

## Android Studio buttons

To run one test, open its Kotlin test file and click the green triangle beside the class or test method. To run all JVM tests, open the **Gradle** panel and run `app > Tasks > verification > testDebugUnitTest`.

The device tests are separated by feature under `app/src/androidTest/java/com/olive/app`: onboarding, cover/account, privacy/security, settings, and standalone cover behavior. `OliveUiTestBase.kt` contains only their shared setup and common UI actions.

For repeatable automatic checks, add `test-all.bat` as an Android Studio external tool or Run Configuration. Set the program to the project's `test-all.bat` file and the working directory to the project root. Running that configuration performs the same complete check every time.
