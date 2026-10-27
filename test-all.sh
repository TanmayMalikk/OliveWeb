#!/usr/bin/env bash
#Linux friendly version of the bat file
set -e

echo "=============================================="
echo "Running Olive Branch Test Suite"
echo "=============================================="
# this is very different than the test-all.bat, since this is using gradlew specific tests, this does not touch our unit tests at all.
./gradlew testDebugUnitTest check
./gradlew check

echo "ConnectedAndroidTest requires a emulated device or a device using Usb debugging to run."
./gradlew connectedAndroidTest

echo "=============================================="
echo "All tests completed successfully!"
echo "=============================================="