#!/usr/bin/env bash
# Unix counterpart of scripts/Test-All.ps1; keep suites and device policy aligned.
set -Eeuo pipefail

project_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
with_device_tests=false
skip_firebase_rules=false
device_serial=""
usage() {
    cat <<'HELP'
Usage: ./test-all.sh [--with-device-tests] [--skip-firebase-rules] [--device-serial SERIAL]
The Windows spellings -WithDeviceTests, -SkipFirebaseRules, and -DeviceSerial also work.
Device tests erase Olive data and configure/clear PIN 246810 on a disposable emulator.
Requires Bash, Python 3, Node/npm, an Android SDK/JDK, and Playwright's Chrome browser.
HELP
}
while (($#)); do
    case "$1" in
        --with-device-tests|-WithDeviceTests) with_device_tests=true ;;
        --skip-firebase-rules|-SkipFirebaseRules) skip_firebase_rules=true ;;
        --device-serial|-DeviceSerial)
            if (($# < 2)) || [[ -z "$2" || "$2" == -* ]]; then
                echo 'Device serial requires a value.' >&2
                exit 2
            fi
            device_serial="$2"
            shift
            ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done

cd -- "$project_root"
command -v python3 >/dev/null || { echo 'Python 3 is required for test reporting.' >&2; exit 1; }
report_dir="$project_root/build/reports"
report_helper="$project_root/scripts/test_report.py"
mkdir -p "$report_dir"
# Remove only this runner's old results so a failed run cannot report stale passes.
rm -rf -- "$project_root/app/build/test-results/testDebugUnitTest" "$report_dir/device-ui-tests"
rm -f -- "$report_dir/"{current-test-check,failed-test-check,failed-test-output,completed-test-checks}.txt \
    "$report_dir/"{web-unit-tests,web-browser-tests,functions-tests,firebase-rules-tests}.xml
completed_checks=()
selected_device=""
device_cleanup=false

finish() {
    local result=$?
    trap - EXIT
    set +e
    if $device_cleanup; then
        "$adb" -s "$selected_device" shell am force-stop com.olive.app >/dev/null 2>&1
        "$adb" -s "$selected_device" shell am force-stop com.olive.app.test >/dev/null 2>&1
        "$adb" -s "$selected_device" shell locksettings clear --old 246810 >/dev/null 2>&1
        local verification
        verification=$("$adb" -s "$selected_device" shell locksettings verify --old 246810 2>&1)
        if [[ "$verification" != *'user has no password'* ]]; then
            echo "Warning: could not remove the disposable emulator PIN from $selected_device." >&2
        fi
        "$adb" -s "$selected_device" shell svc power stayon false >/dev/null 2>&1
    fi
    python3 "$report_helper" summary "$project_root" "$result"
    local report_result=$?
    if ((report_result != 0)); then
        echo 'Test report generation failed.' >&2
        ((result != 0)) || result=$report_result
    fi
    exit "$result"
}
trap finish EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

fail() { echo "$*" >&2; exit 1; }
run_check() {
    local label="$1" result=0
    shift
    printf '\n== %s ==\n' "$label"
    printf '%s\n' "$label" > "$report_dir/current-test-check.txt"
    "$@" 2>&1 | tee "$report_dir/failed-test-output.txt" || result=$?
    if ((result != 0)); then
        printf '%s|%s\n' "$label" "$result" > "$report_dir/failed-test-check.txt"
        echo "$label failed with exit code $result." >&2
        exit "$result"
    fi
    completed_checks+=("$label")
    rm -f -- "$report_dir/failed-test-output.txt"
}

# Honor JAVA_HOME first, then common Android Studio installations on Linux/macOS.
if [[ -z "${JAVA_HOME:-}" ]]; then
    for studio_java in /opt/android-studio/jbr /usr/local/android-studio/jbr \
        '/Applications/Android Studio.app/Contents/jbr/Contents/Home'; do
        if [[ -x "$studio_java/bin/java" ]]; then export JAVA_HOME="$studio_java"; break; fi
    done
fi
if [[ -n "${JAVA_HOME:-}" ]]; then export PATH="$JAVA_HOME/bin:$PATH"; fi
firebase_java="${OLIVE_FIREBASE_JAVA_HOME:-}"
if [[ -n "$firebase_java" && ! -x "$firebase_java/bin/java" ]]; then
    echo 'Warning: OLIVE_FIREBASE_JAVA_HOME has no executable bin/java; using the current JDK.' >&2
    firebase_java=""
fi

run_check 'Android unit tests, debug APK, UI-test APK, and lint' \
    bash ./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug checkArchitecture
for artifact in app/build/outputs/apk/debug/app-debug.apk \
    app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk \
    app/build/reports/lint-results-debug.html; do
    [[ -f "$artifact" ]] || fail "Required test artifact was not created: $artifact"
done
completed_checks+=('Required Android artifacts created')

cd -- "$project_root/web"
[[ -d node_modules ]] || run_check 'Install web dependencies' npm ci
run_check 'Web unit tests' npm test
run_check 'Web production build' npm run build
run_check 'Web browser and accessibility tests' npm run test:e2e

cd -- "$project_root/functions"
[[ -d node_modules ]] || run_check 'Install Cloud Functions dependencies' npm ci
run_check 'Cloud Functions unit tests' npm test
run_check 'Cloud Functions dependency audit' npm audit --omit=dev

if ! $skip_firebase_rules; then
    cd -- "$project_root/firebase"
    [[ -d node_modules ]] || run_check 'Install Firebase test dependencies' npm ci
    if [[ -n "$firebase_java" ]]; then
        run_check 'Firestore and Storage rules tests' env JAVA_HOME="$firebase_java" \
            PATH="$firebase_java/bin:$PATH" npm test
    else
        run_check 'Firestore and Storage rules tests' npm test
    fi
fi
cd -- "$project_root"

wait_for_package_manager() {
    local deadline=$((SECONDS + 60)) booted package
    while ((SECONDS < deadline)); do
        booted=$("$adb" -s "$selected_device" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') || booted=""
        package=$("$adb" -s "$selected_device" shell pm path android 2>/dev/null | tr -d '\r') || package=""
        if [[ "$booted" == 1 && "$package" == package:* ]]; then return; fi
        sleep 2
    done
    fail "Android's package manager did not become ready on $selected_device within 60 seconds."
}

install_apk() {
    local apk="$1" attempt result output
    shift
    for attempt in 1 2 3; do
        wait_for_package_manager
        result=0
        output=$("$adb" -s "$selected_device" install --no-streaming -r "$@" "$apk" 2>&1) || result=$?
        printf '%s\n' "$output"
        if ((result == 0)) && grep -Eq '^Success[[:space:]]*$' <<< "$output"; then return; fi
        if ((attempt < 3)); then sleep "$((2 * attempt))"; fi
    done
    return 1
}

ensure_device_awake() {
    "$adb" -s "$selected_device" shell svc power stayon true >/dev/null
    "$adb" -s "$selected_device" shell input keyevent KEYCODE_WAKEUP >/dev/null
    "$adb" -s "$selected_device" shell wm dismiss-keyguard >/dev/null
    local policy power
    policy=$("$adb" -s "$selected_device" shell dumpsys window policy)
    if [[ "$policy" == *showing=true* ]]; then
        "$adb" -s "$selected_device" shell input swipe 540 1900 540 500 250 >/dev/null
        if $device_pin_ready; then
            "$adb" -s "$selected_device" shell input text 246810 >/dev/null
            "$adb" -s "$selected_device" shell input keyevent KEYCODE_ENTER >/dev/null
        fi
        "$adb" -s "$selected_device" shell wm dismiss-keyguard >/dev/null
    fi
    power=$("$adb" -s "$selected_device" shell dumpsys power)
    policy=$("$adb" -s "$selected_device" shell dumpsys window policy)
    [[ "$power" == *mWakefulness=Awake* && "$policy" != *showing=true* ]] || fail 'Could not wake and unlock the test emulator.'
}

if $with_device_tests; then
    adb="adb"
    sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
    if [[ -z "$sdk_root" ]]; then
        # Android Studio may configure only sdk.dir rather than an environment variable.
        sdk_root=$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null | tr -d '\r') || sdk_root=""
    fi
    if [[ -z "$sdk_root" ]]; then
        for sdk_candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
            if [[ -x "$sdk_candidate/platform-tools/adb" ]]; then sdk_root="$sdk_candidate"; break; fi
        done
    fi
    if [[ -x "$sdk_root/platform-tools/adb" ]]; then adb="$sdk_root/platform-tools/adb"; fi
    devices=$("$adb" devices | tr -d '\r')
    ready_devices=$(awk 'NR > 1 && $2 == "device" {print $1}' <<< "$devices")
    [[ -n "$ready_devices" ]] || fail 'No unlocked Android device or running emulator is available.'
    if [[ -n "$device_serial" ]]; then
        grep -Fxq -- "$device_serial" <<< "$ready_devices" || fail "Device '$device_serial' is not available. Run 'adb devices'."
        selected_device="$device_serial"
    else
        selected_device=$(awk '/^emulator-/ {print; exit}' <<< "$ready_devices")
        [[ -n "$selected_device" ]] || fail 'Android device tests require a disposable emulator.'
    fi
    is_emulator=$("$adb" -s "$selected_device" shell getprop ro.kernel.qemu | tr -d '\r')
    [[ "$is_emulator" == 1 ]] || fail 'Android device tests are blocked on physical phones. Select a disposable emulator.'
    echo "Using Android device: $selected_device"
    run_check 'Build Android UI tests' bash ./gradlew -PoliveLocalTest=true assembleDebug assembleDebugAndroidTest
    wait_for_package_manager
    "$adb" -s "$selected_device" uninstall com.olive.app.test >/dev/null 2>&1 || true
    "$adb" -s "$selected_device" uninstall com.olive.app >/dev/null 2>&1 || true
    run_check 'Install Olive test build' install_apk "$project_root/app/build/outputs/apk/debug/app-debug.apk"
    run_check 'Install Olive UI tests' install_apk "$project_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" -t
    "$adb" -s "$selected_device" shell input keyevent KEYCODE_WAKEUP >/dev/null
    "$adb" -s "$selected_device" shell wm dismiss-keyguard >/dev/null
    user_state=$("$adb" -s "$selected_device" shell dumpsys user)
    [[ "$user_state" == *'State: RUNNING_UNLOCKED'* ]] || fail 'Unlock the emulator once with PIN 246810, then rerun.'
    # Register cleanup before setting the PIN, including when setup fails partway through.
    device_cleanup=true
    device_pin_ready=false
    pin_verification=$("$adb" -s "$selected_device" shell locksettings verify --old 246810 2>&1) || true
    if [[ "$pin_verification" == *'user has no password'* ]]; then
        pin_result=$("$adb" -s "$selected_device" shell locksettings set-pin 246810 2>&1) || true
        [[ "$pin_result" == *"Pin set to '246810'"* ]] || fail "Could not configure test PIN: $pin_result"
        device_pin_ready=true
    elif [[ "$pin_verification" == *'Lock credential verified successfully'* ]]; then
        device_pin_ready=true
    fi
    mkdir -p "$report_dir/device-ui-tests"
    python3 "$report_helper" discover "$project_root" | tr -d '\r' > "$report_dir/device-ui-tests/cases.txt"
    [[ -s "$report_dir/device-ui-tests/cases.txt" ]] || fail 'No Android device test methods were found.'
    passed_tests=0
    skipped_tests=0
    while IFS= read -r test_case; do
        requires_pin=false
        case "$test_case" in
            *SafeEntrySettingsUiTest#switchingAndBackupSetupRequireProofAndNeverExposePinDuringDeviceLock|\
            *SafeEntryUiTest#deviceLockCancellationReturnsActualTalkBackFocusAndAllowsRetry|\
            *SafeEntryUiTest#deviceLockEnrollmentAndEveryReentryRequireAndroidAuthentication) requires_pin=true ;;
        esac
        output_file="$report_dir/device-ui-tests/instrumentation.txt"
        if $requires_pin && ! $device_pin_ready; then
            echo "SKIP $test_case (requires test PIN 246810)"
            printf '%s\n' 'Requires a disposable emulator secured with test PIN 246810.' > "$output_file"
            python3 "$report_helper" device-result "$report_dir" "$test_case" SKIPPED 0 "$output_file"
            skipped_tests=$((skipped_tests + 1))
            continue
        fi
        echo "RUN $test_case"
        printf 'Android device UI test %s\n' "$test_case" > "$report_dir/current-test-check.txt"
        started=$SECONDS
        passed=false
        for attempt in 1 2; do
            "$adb" -s "$selected_device" shell am force-stop com.olive.app >/dev/null
            "$adb" -s "$selected_device" shell am force-stop com.olive.app.test >/dev/null
            ensure_device_awake
            result=0
            "$adb" -s "$selected_device" shell am instrument -w -r -e clearPackageData true \
                -e class "$test_case" com.olive.app.test/androidx.test.runner.AndroidJUnitRunner \
                2>&1 | tee "$output_file" || result=$?
            if ((result == 0)) && grep -Eq 'OK \(([1-9][0-9]*) tests?\)' "$output_file" && \
                ! grep -q 'FAILURES!!!' "$output_file"; then passed=true; break; fi
            if ((attempt == 1)) && grep -Eq 'shortMsg=Process crashed|Activity client record must not be null' "$output_file"; then
                echo 'Android framework crashed; retrying once in a clean process.' >&2
            else
                break
            fi
        done
        status=FAILED
        if $passed; then status=PASSED; fi
        python3 "$report_helper" device-result "$report_dir" "$test_case" "$status" "$((SECONDS - started))" "$output_file"
        if ! $passed; then
            printf 'Android device UI test %s|1\n' "$test_case" > "$report_dir/failed-test-check.txt"
            fail "Android device UI test $test_case failed on $selected_device."
        fi
        passed_tests=$((passed_tests + 1))
    done < "$report_dir/device-ui-tests/cases.txt"
    completed_checks+=("Android device UI tests on $selected_device ($passed_tests passed, $skipped_tests device-lock tests skipped)")
fi

printf '\nAll requested Olive tests passed (%s checks):\n' "${#completed_checks[@]}"
printf '  PASS  %s\n' "${completed_checks[@]}"
printf '%s\n' "${completed_checks[@]}" > "$report_dir/completed-test-checks.txt"
rm -f -- "$report_dir/current-test-check.txt"
