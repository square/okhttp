# Flake Detector Skill

## Description
This skill helps identify flaky tests in the OkHttp project by analyzing recent failures in the GitHub Actions `build.yml` workflow on the `master` branch. It fetches failed run logs, extracts test failure patterns, and **aggregates the number of failures encountered per test class**.

## Reproduction
To reproduce these flakes locally:

1.  **Identify** the specific failing method using the detector:
    ```bash
    ./.github/skills/flake-detector/identify-flakes.sh
    ```

2.  **Modify** the test code to repeat the test.
    *   Open the failing test file.
    *   Replace `@Test` with `@RepeatedTest(100)` for the failing test method.
    *   Add import `org.junit.jupiter.api.RepeatedTest`.

3.  **Run** the reproduction script:
    ```bash
    ./.github/skills/flake-detector/reproduce-flakes.sh
    ```
    This script will automatically detect recent failing methods and execute them. If you have applied `@RepeatedTest`, it will run them 100 times.

4.  **Cleanup**: `@RepeatedTest` should be removed from tests that aren't recently flaky in CI, and that run fine locally also. Revert them to `@Test` to avoid slowing down the test suite.

## Known Flakes (as of Jan 2026)
Based on recent analysis, the following tests are known to be flaky, ordered by observed frequency:

1.  **`CacheTest` (Golden Cache) - High Frequency**
    *   `testGoldenCacheHttpsResponseOkHttp27`, `testGoldenCacheHttpsResponseOkHttp30`
    *   **Symptom**: `java.net.SocketTimeoutException: Read timed out` or `timeout`. Also `AssertionFailedError` on content mismatches.

2.  **`RouteFailureTest`**
    *   `http2OneBadHostRetryOnConnectionFailureFastFallback`
    *   `http2OneBadHostOneGoodNoRetryOnConnectionFailureFastFallback`
    *   `http2OneBadHostRetryOnConnectionFailure`
    *   **Symptom**: `AssertionFailedError: expected:<[1]> but was:<[0]>` (Retry count mismatch).

3.  **`ServerTruncatesRequestTest`**
    *   `serverTruncatesRequestButTrailersCanStillBeReadHttp1`
    *   `serverTruncatesRequestOnLongPostHttp1`
    *   **Symptom**: `java.net.SocketException: An established connection was aborted by the software in your host machine` (likely environment specific).

4.  **`WebSocketHttpTest`**
    *   `closeWithoutSuccessfulConnect`
    *   **Symptom**: `AssertionFailedError: Still 0 connections open ==> expected: <0> but was: <1>`

5.  **`Http2ConnectionTest`**
    *   `discardedDataFramesAreCounted`
    *   **Symptom**: Data frame count mismatch (`1024` vs `2048`).

6.  **`EventListenerTest_Relay`**
    *   `cancelAsyncCall`
    *   **Symptom**: Unexpected event sequence.

7.  **`DuplexTest`**
    *   `duplexWithRedirect`
    *   **Symptom**: `java.util.concurrent.TimeoutException` (timed out after 30 seconds).

8.  **`AlpnOverrideTest`**
    *   **Symptom**: `java.net.ConnectException` (often transient CI network issues connecting to google.com).

9.  **`ThreadInterruptTest`**
    *   `forciblyStopDispatcher`
    *   **Symptom**: `java.util.concurrent.TimeoutException`.

10. **`HttpOverHttp2Test`**
    *   `recoverFromMultipleCancelReusesConnection`
    *   **Symptom**: `AssertionFailedError` (Connection count mismatch).
