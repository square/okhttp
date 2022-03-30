#!/bin/sh -e

../gradlew -q --console plain nativeImage

./build/graal/okcurl "$@"
