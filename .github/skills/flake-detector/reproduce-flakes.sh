#!/bin/bash
# Reproduce flaky tests locally.
# Usage: ./reproduce-flakes.sh [test_filter] [repetitions]
# Example: ./reproduce-flakes.sh "okhttp3.RouteFailureTest" 20

TEST_FILTER=${1:-""}
REPETITIONS=${2:-20}
PROJECT_DIR=$(pwd)

if [ -z "$TEST_FILTER" ]; then
  echo "Usage: $0 [test_filter] [repetitions]"
  echo "Example: $0 \"okhttp3.RouteFailureTest\" 20"
  echo ""
  echo "You can find candidates using ./identify-flakes.sh"
  exit 1
fi

echo "Verifying compilation..."
if ! ./gradlew :okhttp:jvmTestClasses > /dev/null; then
  echo "Compilation failed. Fix build errors before running tests."
  exit 1
fi
echo "Compilation successful."

echo "Attempting to reproduce flake in: $TEST_FILTER"
echo "Repetitions: $REPETITIONS"
echo "--------------------------------------------------"

FAIL_COUNT=0

for ((i=1; i<=REPETITIONS; i++)); do
  echo "Run $i/$REPETITIONS..."
  
  # Run the test. output to a temp file to check for failure.
  # We use --continue to ensure it doesn't just stop the script on error code, 
  # though we want to count failures.
  if ./gradlew :okhttp:test --tests "$TEST_FILTER" > /dev/null 2>&1; then
    echo "  PASS"
  else
    echo "  FAIL"
    FAIL_COUNT=$((FAIL_COUNT+1))
  fi
done

echo "--------------------------------------------------"
echo "Summary for $TEST_FILTER:"
echo "Total Runs: $REPETITIONS"
echo "Failures: $FAIL_COUNT"

if [ $FAIL_COUNT -gt 0 ]; then
  echo "Result: REPRODUCED"
else
  echo "Result: NOT REPRODUCED"
fi
