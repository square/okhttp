#!/usr/bin/env bash

SCRIPT_DIR="$( cd "$( dirname "$0" )" && pwd )"
cd "$SCRIPT_DIR"

which wstest
if [ $? != 0 ]; then
  echo "Run 'pip install autobahntestsuite', maybe with 'sudo'."
  exit 1
fi
which jq
if [ $? != 0 ]; then
  echo "Run 'brew install jq'"
  exit 1
fi

trap 'kill $(jobs -pr)' SIGINT SIGTERM EXIT

set -ex

wstest -m fuzzingserver -s fuzzingserver-config.json &
sleep 2 # wait for wstest to start

java -jar target/okhttp-tests-*-jar-with-dependencies.jar

jq '.[] as $in | $in | keys[] | . + " " + $in[.].behavior' target/fuzzingserver-report/index.json > target/fuzzingserver-actual.txt

diff fuzzingserver-expected.txt target/fuzzingserver-actual.txt
