Android Test
============

A gradle module for running Android instrumentation tests on device or emulator.

1. Run an Emulator using Android Studio or from command line i.e. emulator-headless.

2. Turn on logs with logcat

```
$ adb logcat '*:E' OkHttp:D
...
01-01 12:53:32.811 10999 11089 D OkHttp  : [49 ms] responseHeadersEnd: Response{protocol=h2, code=200, message=, url=https://1.1.1.1/dns-query?dns=AAABAAABAAAAAAAAA3d3dwhmYWNlYm9vawNjb20AABwAAQ}
01-01 12:53:32.811 10999 11089 D OkHttp  : [49 ms] responseBodyStart
01-01 12:53:32.811 10999 11089 D OkHttp  : [49 ms] responseBodyEnd: byteCount=128
01-01 12:53:32.811 10999 11089 D OkHttp  : [49 ms] connectionReleased
01-01 12:53:32.811 10999 11089 D OkHttp  : [49 ms] callEnd
01-01 12:53:32.816 10999 11090 D OkHttp  : [54 ms] responseHeadersStart
01-01 12:53:32.816 10999 11090 D OkHttp  : [54 ms] responseHeadersEnd: Response{protocol=h2, code=200, message=, url=https://1.1.1.1/dns-query?dns=AAABAAABAAAAAAAAA3d3dwhmYWNlYm9vawNjb20AAAEAAQ}
01-01 12:53:32.817 10999 11090 D OkHttp  : [55 ms] responseBodyStart
01-01 12:53:32.818 10999 11090 D OkHttp  : [56 ms] responseBodyEnd: byteCount=128
01-01 12:53:32.818 10999 11090 D OkHttp  : [56 ms] connectionReleased
01-01 12:53:32.818 10999 11090 D OkHttp  : [56 ms] callEnd
```

3. Run tests using gradle

```
$ ANDROID_SDK_ROOT=/Users/myusername/Library/Android/sdk ./gradlew :android-test:connectedCheck
...
> Task :android-test:connectedDebugAndroidTest
11:55:22 V/ddms: execute: running am get-config
11:55:22 V/ddms: execute 'am get-config' on 'emulator-5554' : EOF hit. Read: -1
11:55:22 V/ddms: execute: returning
11:55:22 D/android-test-debug-androidTest.apk: Uploading android-test-debug-androidTest.apk onto device 'emulator-5554'
11:55:22 D/Device: Uploading file onto device 'emulator-5554'
11:55:22 D/ddms: Reading file permision of /Users/myusername/workspace/okhttp/android-test/build/outputs/apk/androidTest/debug/android-test-debug-androidTest.apk as: rw-r--r--
11:55:23 V/ddms: execute: running pm install -r -t "/data/local/tmp/android-test-debug-androidTest.apk"
11:55:23 V/ddms: execute 'pm install -r -t "/data/local/tmp/android-test-debug-androidTest.apk"' on 'emulator-5554' : EOF hit. Read: -1
11:55:23 V/ddms: execute: returning
11:55:23 V/ddms: execute: running rm "/data/local/tmp/android-test-debug-androidTest.apk"
11:55:23 V/ddms: execute 'rm "/data/local/tmp/android-test-debug-androidTest.apk"' on 'emulator-5554' : EOF hit. Read: -1
11:55:23 V/ddms: execute: returning
11:55:23 I/RemoteAndroidTest: Running am instrument -w -r   -e notClass org.conscrypt.KitKatPlatformOpenSSLSocketImplAdapter okhttp.android.test.test/android.support.test.runner.AndroidJUnitRunner on pixel3a-Q(AVD) - 10
11:55:23 V/ddms: execute: running am instrument -w -r   -e notClass org.conscrypt.KitKatPlatformOpenSSLSocketImplAdapter okhttp.android.test.test/android.support.test.runner.AndroidJUnitRunner
...
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS: class=okhttp.android.test.OkHttpTest
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS: current=13
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS: numtests=13
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS: stream=
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS: test=testConscryptRequest
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS_CODE: 1
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS: class=okhttp.android.test.OkHttpTest
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS: current=13
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS: numtests=13
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS: stream=.
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS: test=testConscryptRequest
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_STATUS_CODE: 0
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_RESULT: stream=
11:55:40 V/InstrumentationResultParser:
11:55:40 V/InstrumentationResultParser: Time: 13.271
11:55:40 V/InstrumentationResultParser:
11:55:40 V/InstrumentationResultParser: OK (12 tests)
11:55:40 V/InstrumentationResultParser:
11:55:40 V/InstrumentationResultParser:
11:55:40 V/InstrumentationResultParser: INSTRUMENTATION_CODE: -1
11:55:40 V/InstrumentationResultParser:
11:55:40 I/XmlResultReporter: XML test result file generated at /Users/myusername/workspace/okhttp/android-test/build/outputs/androidTest-results/connected/TEST-pixel3a-Q(AVD) - 10-android-test-.xml. Total tests 13, passed 11, assumption_failure 1, ignored 1,
11:55:40 V/ddms: execute 'am instrument -w -r   -e notClass org.conscrypt.KitKatPlatformOpenSSLSocketImplAdapter okhttp.android.test.test/android.support.test.runner.AndroidJUnitRunner' on 'emulator-5554' : EOF hit. Read: -1
11:55:40 V/ddms: execute: returning
11:55:40 V/ddms: execute: running pm uninstall okhttp.android.test.test
11:55:40 V/ddms: execute 'pm uninstall okhttp.android.test.test' on 'emulator-5554' : EOF hit. Read: -1
11:55:40 V/ddms: execute: returning

Deprecated Gradle features were used in this build, making it incompatible with Gradle 7.0.
Use '--warning-mode all' to show the individual deprecation warnings.
See https://docs.gradle.org/6.0.1/userguide/command_line_interface.html#sec:command_line_warnings

BUILD SUCCESSFUL in 1m 30s
63 actionable tasks: 61 executed, 2 up-to-date

```