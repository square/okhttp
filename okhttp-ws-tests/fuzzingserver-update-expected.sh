#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
cd "$SCRIPT_DIR"

if [ ! -f target/fuzzingserver-actual.txt ]; then
  echo "File not found. Did you run the Autobahn test script?"
  exit 1
fi

cp target/fuzzingserver-actual.txt fuzzingserver-expected.txt
