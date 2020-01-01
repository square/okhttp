Android Test
============

A gradle module for running Android instrumentation tests on a device or emulator.

1. Run an Emulator using Android Studio or from command line i.e. emulator-headless.

2. Turn on logs with logcat

```
$ adb logcat '*:E' OkHttp:D Http2:D TestRunner:D TaskRunner:D
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
...
11:55:40 V/InstrumentationResultParser: Time: 13.271
11:55:40 V/InstrumentationResultParser:
11:55:40 V/InstrumentationResultParser: OK (12 tests)
...
11:55:40 I/XmlResultReporter: XML test result file generated at /Users/myusername/workspace/okhttp/android-test/build/outputs/androidTest-results/connected/TEST-pixel3a-Q(AVD) - 10-android-test-.xml. Total tests 13, passed 11, assumption_failure 1, ignored 1,
...
BUILD SUCCESSFUL in 1m 30s
63 actionable tasks: 61 executed, 2 up-to-date

```