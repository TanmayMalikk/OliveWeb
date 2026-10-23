#!/usr/bin/env bash
#Linux friendly version of the bat file
set -e

echo "=============================================="
echo "Running Olive Branch Test Suite"
echo "=============================================="

./gradlew testDebugUnitTest check

echo "=============================================="
echo "All tests completed successfully!"
echo "=============================================="
