Android Test
============

A gradle module for running Android instrumentation tests on a device or emulator.

## Running with emulator.wtf (recommended)

[emulator.wtf](https://emulator.wtf) runs instrumented tests in the cloud without needing a local emulator.

### Local CLI usage

1. Install the ew-cli:

```
$ brew install emulator-wtf/tap/ew-cli
```

2. Authenticate (set your API token):

```
$ export EW_API_TOKEN=your-token-here
```

3. Build the test APK and run:

```
$ ./gradlew -PandroidBuild=true :android-test:assembleDebugAndroidTest
$ ew-cli \
    --test android-test/build/outputs/apk/androidTest/debug/android-test-debug-androidTest.apk \
    --devices version=29 \
    --outputs-dir build/test-results/android-test
```

You can test on multiple API levels by specifying additional `--devices`:

```
$ ew-cli \
    --test android-test/build/outputs/apk/androidTest/debug/android-test-debug-androidTest.apk \
    --devices version=21 --devices version=29 --devices version=34 \
    --outputs-dir build/test-results/android-test
```

## Running with a local emulator

1. Add an Emulator named `pixel5`, if you don't already have one

```
$ sdkmanager --install "system-images;android-29;google_apis;x86"
$ echo "no" | avdmanager --verbose create avd --force --name "pixel5" --device "pixel" --package "system-images;android-29;google_apis;x86" --tag "google_apis" --abi "x86"
```

2. Run an Emulator using Android Studio or from command line.

```
$ emulator -no-window -no-snapshot-load @pixel5
```

3. Turn on logs with logcat

```
$ adb logcat '*:E' OkHttp:D Http2:D TestRunner:D TaskRunner:D OkHttpTest:D GnssHAL_GnssInterface:F DeviceStateChecker:F memtrack:F
```

4. Run tests using gradle

```
$ ANDROID_SDK_ROOT=/Users/myusername/Library/Android/sdk ./gradlew :android-test:connectedCheck -PandroidBuild=true
```

n.b. use ANDROID_SERIAL=emulator-5554 or similar if you need to select between devices.
