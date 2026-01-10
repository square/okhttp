# Flake Detector Skill

## Description
This skill helps identify flaky tests in the OkHttp project by analyzing recent failures in the GitHub Actions `build.yml` workflow on the `master` branch. It fetches failed run logs, extracts test failure patterns, and **aggregates the number of failures encountered per test class**.

## Usage
Run the script from the root of the repository. It requires the GitHub CLI (`gh`) to be installed and authenticated.

```bash
./.github/skills/flake-detector/identify-flakes.sh [limit]
```

- `limit` (optional): The number of recent failed runs to analyze. Defaults to 10.

**Output:**
The script prints the failure details for each run and concludes with a **Summary Table** showing the most frequent failing classes across all analyzed runs (e.g., `12 CacheTest`).

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
