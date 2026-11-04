"""Exercise Unix runner orchestration with fake tools, never a real device/backend."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


REPOSITORY = Path(__file__).resolve().parents[2]
BASH = shutil.which("bash") or r"C:\Program Files\Git\bin\bash.exe"


class UnixRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="olive runner ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for directory in ("scripts", "web", "functions", "firebase", "bin", "app/src/androidTest/java"):
            (self.root / directory).mkdir(parents=True)
        shutil.copy(REPOSITORY / "test-all.sh", self.root)
        shutil.copy(REPOSITORY / "scripts/test_report.py", self.root / "scripts")
        self.write("app/src/androidTest/java/ExampleTest.kt", """package example
class ExampleTest {
    @Test fun basicFlow() {}
}
""")
        self.write("app/src/androidTest/java/SafeEntryUiTest.kt", """package example
class SafeEntryUiTest {
    @Test fun deviceLockEnrollmentAndEveryReentryRequireAndroidAuthentication() {}
}
""")
        self.write("gradlew", """#!/usr/bin/env bash
echo "gradle $*" >> "$MOCK_LOG"
[[ "$MOCK_MODE" != gradle-fail ]] || exit 17
mkdir -p app/build/outputs/apk/{debug,androidTest/debug} app/build/reports app/build/test-results/testDebugUnitTest
touch app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
[[ "$MOCK_MODE" == missing-artifact ]] || touch app/build/reports/lint-results-debug.html
printf '<testsuite><testcase name="jvm" classname="unit"/></testsuite>' > app/build/test-results/testDebugUnitTest/TEST-unit.xml
""")
        self.write("bin/npm", """#!/usr/bin/env bash
area="${PWD##*/}"
echo "$area npm $*" >> "$MOCK_LOG"
if [[ "$MOCK_MODE" == web-fail && "$area $*" == 'web test' ]]; then echo 'mock web failure'; exit 23; fi
if [[ "$1" == ci ]]; then mkdir -p node_modules; fi
""")
        self.write("bin/adb", """#!/usr/bin/env bash
echo "adb $*" >> "$MOCK_LOG"
if [[ "$1" == devices ]]; then printf 'List of devices attached\nemulator-5554\tdevice\nphone\tdevice\n'; exit; fi
shift 2
case "$*" in
    'shell getprop ro.kernel.qemu') [[ "$MOCK_MODE" == physical ]] && echo 0 || echo 1 ;;
    'shell getprop sys.boot_completed') echo 1 ;;
    'shell pm path android') echo package:/system/framework/framework-res.apk ;;
    install*) echo Success ;;
    'shell dumpsys user') echo 'State: RUNNING_UNLOCKED' ;;
    'shell dumpsys power') echo 'mWakefulness=Awake' ;;
    'shell dumpsys window policy') echo 'showing=false' ;;
    'shell locksettings verify --old 246810')
        if [[ "$MOCK_MODE" == wrong-pin ]]; then echo 'credential mismatch'
        elif [[ -f "$MOCK_STATE" ]]; then echo 'Lock credential verified successfully'
        else echo 'user has no password'; fi ;;
    'shell locksettings set-pin 246810') touch "$MOCK_STATE"; echo "Pin set to '246810'" ;;
    'shell locksettings clear --old 246810') rm -f "$MOCK_STATE" ;;
    'shell am instrument'*)
        if [[ "$MOCK_MODE" == assertion-fail ]]; then echo 'FAILURES!!!'; exit 1; fi
        if [[ "$MOCK_MODE" == crash && ! -f "$MOCK_STATE.crashed" ]]; then
            touch "$MOCK_STATE.crashed"; echo 'shortMsg=Process crashed'; exit 1
        fi
        echo 'OK (1 test)' ;;
esac
""")
        # Git Bash's python3 may be a Windows Store alias. Use the interpreter
        # running these tests; Unix CI can use its native python3 directly.
        interpreter = Path(sys.executable).as_posix()
        self.write("bin/python3", f'#!/usr/bin/env bash\nexec "{interpreter}" "$@"\n')
        self.environment = os.environ.copy()
        self.environment.update({
            "PATH": str(self.root / "bin") + os.pathsep + os.environ["PATH"],
            "MOCK_LOG": (self.root / "calls.txt").as_posix(),
            "MOCK_STATE": (self.root / "pin").as_posix(),
            "MOCK_MODE": "success",
            "ANDROID_HOME": "",
            "ANDROID_SDK_ROOT": "",
            "OLIVE_FIREBASE_JAVA_HOME": "",
        })

    def write(self, name, content):
        path = self.root / name
        path.write_text(content, encoding="utf-8", newline="\n")
        path.chmod(0o755)

    def run_suite(self, *arguments, mode="success"):
        self.environment["MOCK_MODE"] = mode
        result = subprocess.run([BASH, (self.root / "test-all.sh").as_posix(), *arguments],
                                cwd=self.root.parent, env=self.environment, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
        self.output = result.stdout
        return result

    def log(self):
        return (self.root / "calls.txt").read_text()

    def report(self):
        return (self.root / "test-reports/olive-test-summary.txt").read_text()

    def test_default_suites_and_reports(self):
        result = self.run_suite()
        self.assertEqual(result.returncode, 0, self.output)
        calls = self.log().splitlines()
        self.assertEqual(calls, [
            "gradle testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug checkArchitecture",
            "web npm ci", "web npm test", "web npm run build", "web npm run test:e2e",
            "functions npm ci", "functions npm test", "functions npm audit --omit=dev",
            "firebase npm ci", "firebase npm test",
        ])
        self.assertIn("Result: PASSED", self.report())
        self.assertIn("Passed tests: 1", self.report())

    def test_skip_rules_and_existing_dependencies(self):
        for area in ("web", "functions", "firebase"):
            (self.root / area / "node_modules").mkdir()
        result = self.run_suite("-SkipFirebaseRules")
        self.assertEqual(result.returncode, 0, self.output)
        self.assertNotIn("firebase", self.log())
        self.assertNotIn("npm ci", self.log())

    def test_failure_preserves_exit_code_and_stops_later_suites(self):
        result = self.run_suite(mode="web-fail")
        self.assertEqual(result.returncode, 23, self.output)
        self.assertNotIn("functions npm", self.log())
        self.assertNotIn("web npm run build", self.log())
        self.assertIn("Web unit tests|23", self.report())
        self.assertIn("mock web failure", self.report())

    def test_gradle_failure_cannot_reuse_old_results(self):
        stale = self.root / "app/build/test-results/testDebugUnitTest"
        stale.mkdir(parents=True)
        self.write("app/build/test-results/testDebugUnitTest/TEST-old.xml",
                   '<testsuite><testcase name="stale"/></testsuite>')
        result = self.run_suite(mode="gradle-fail")
        self.assertEqual(result.returncode, 17, self.output)
        self.assertIn("Passed tests: 0", self.report())
        self.assertNotIn("web npm", self.log())

    def test_missing_artifact_fails(self):
        result = self.run_suite(mode="missing-artifact")
        self.assertNotEqual(result.returncode, 0, self.output)
        self.assertNotIn("web npm", self.log())

    def test_device_methods_are_isolated_and_pin_is_cleaned(self):
        result = self.run_suite("-WithDeviceTests", "-DeviceSerial", "emulator-5554")
        self.assertEqual(result.returncode, 0, self.output)
        self.assertEqual(self.log().count("shell am instrument"), 2)
        self.assertIn("shell locksettings clear --old 246810", self.log())
        self.assertFalse((self.root / "pin").exists())
        self.assertIn("Android device UI: 2 passed", self.report())

    def test_framework_crash_is_retried_once(self):
        result = self.run_suite("--with-device-tests", mode="crash")
        self.assertEqual(result.returncode, 0, self.output)
        self.assertEqual(self.log().count("shell am instrument"), 3)

    def test_assertion_is_not_retried_and_cleanup_still_runs(self):
        result = self.run_suite("--with-device-tests", mode="assertion-fail")
        self.assertNotEqual(result.returncode, 0, self.output)
        self.assertEqual(self.log().count("shell am instrument"), 1)
        self.assertIn("shell locksettings clear --old 246810", self.log())
        self.assertIn("Android device UI: 0 passed, 1 failed", self.report())
        self.assertIn("FAILURES!!!", self.report())

    def test_wrong_pin_skips_only_credential_tests(self):
        result = self.run_suite("--with-device-tests", mode="wrong-pin")
        self.assertEqual(result.returncode, 0, self.output)
        self.assertEqual(self.log().count("shell am instrument"), 1)
        self.assertIn("Android device UI: 1 passed, 0 failed, 1 skipped", self.report())

    def test_physical_phone_is_rejected_before_installation(self):
        result = self.run_suite("--with-device-tests", "--device-serial", "phone", mode="physical")
        self.assertNotEqual(result.returncode, 0, self.output)
        self.assertNotIn("uninstall", self.log())
        self.assertNotIn("locksettings", self.log())

    def test_bad_options_do_not_start_tests(self):
        for arguments in (("--unknown",), ("--device-serial",), ("--device-serial", "--with-device-tests")):
            result = self.run_suite(*arguments)
            self.assertEqual(result.returncode, 2, self.output)
        self.assertFalse((self.root / "calls.txt").exists())


if __name__ == "__main__":
    unittest.main()
