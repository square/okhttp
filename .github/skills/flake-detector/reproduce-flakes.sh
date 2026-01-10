#!/bin/bash
# Reproduce flaky tests locally.
# Usage: ./reproduce-flakes.sh
#
# This script:
# 1. Reads the list of flaky tests from flaky-tests.txt
# 2. Maps them to the correct Gradle module and task.
# 3. Runs them in appropriate Gradle invocations.
#
# RECOMMENDATION:
# Before running this, manually edit the failing test methods in your IDE
# to use @RepeatedTest(100) instead of @Test. This ensures they run enough
# times to trigger the flake.

SKILL_DIR=$(dirname "$0")
FLAKY_TESTS_FILE="$SKILL_DIR/flaky-tests.txt"

# 1. Check for flaky tests file
if [ ! -f "$FLAKY_TESTS_FILE" ]; then
  echo "Error: flaky-tests.txt not found."
  echo "Run ./identify-flakes.sh first to generate the list of flakes."
  exit 1
fi

if [ ! -s "$FLAKY_TESTS_FILE" ]; then
  echo "No flaky tests found in flaky-tests.txt."
  exit 0
fi

echo "Reading flaky tests from $FLAKY_TESTS_FILE..."
echo "--------------------------------------------------"

# associative array to hold task -> test filters
declare -A TASK_FILTERS

while read -r test_entry; do
  # Skip empty lines
  if [ -z "$test_entry" ]; then continue; fi

  # ClassName is everything before the last dot
  CLASS_NAME="${test_entry%.*}"

  # Find the file
  FILE_PATH=$(find . -name "${CLASS_NAME}.kt" -o -name "${CLASS_NAME}.java" | head -n 1)

  if [ -z "$FILE_PATH" ]; then
    echo "Warning: Could not find file for class $CLASS_NAME. Skipping."
    continue
  fi

  # Determine module and task
  # Example path: ./okhttp/src/jvmTest/kotlin/okhttp3/CacheTest.kt -> module: okhttp, task: jvmTest
  # Example path: ./mockwebserver/src/test/java/... -> module: mockwebserver, task: test

  MODULE=$(echo "$FILE_PATH" | cut -d'/' -f2)

  if [[ "$FILE_PATH" == *"/src/jvmTest/"* ]]; then
    TASK=":$MODULE:jvmTest"
  elif [[ "$FILE_PATH" == *"/src/test/"* ]]; then
    TASK=":$MODULE:test"
  elif [[ "$FILE_PATH" == *"/src/androidTest/"* ]]; then
    # Skip Android instrumentation tests for local reproduction for now
    echo "Skipping Android instrumentation test: $test_entry"
    continue
  else
    # Default fallback
    TASK=":$MODULE:test"
  fi

  # Append to the list for this task
  if [ -z "${TASK_FILTERS[$TASK]}" ]; then
    TASK_FILTERS[$TASK]="--tests $test_entry"
  else
    TASK_FILTERS[$TASK]="${TASK_FILTERS[$TASK]} --tests $test_entry"
  fi

done < "$FLAKY_TESTS_FILE"

echo "--------------------------------------------------"

# Run Gradle commands
for TASK in "${!TASK_FILTERS[@]}"; do
  ARGS="${TASK_FILTERS[$TASK]}"
  echo "Running tests for task $TASK..."
  echo "./gradlew $TASK $ARGS"
  # We intentionally don't quote $ARGS here to allow word splitting of multiple --tests flags
  # shellcheck disable=SC2086
  ./gradlew "$TASK" $ARGS
  echo "--------------------------------------------------"
done
