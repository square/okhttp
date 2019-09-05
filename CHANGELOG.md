Change Log
==========

## Version 4.1.1

_2019-09-05_

 *  Fix: Don't drop repeated headers when validating cached responses. In our Kotlin upgrade we
    introduced a regression where we iterated the number of unique header names rather than then
    number of unique headers. If you're using OkHttp's response cache this may impact you.


## Version 4.1.0

_2019-08-12_

 [brotli]: https://github.com/google/brotli

 *  **OkHttp's new okhttp-brotli module implements Brotli compression.** Install the interceptor to
    enable [Brotli compression][brotli], which compresses 5-20% smaller than gzip.

    ```
    val client = OkHttpClient.Builder()
        .addInterceptor(BrotliInterceptor)
        .build()
    ```

    This artifact has a dependency on Google's Brotli decoder (95 KiB).

 *  New: `EventListener.proxySelectStart()`, `proxySelectEnd()` events give visibility into the
    proxy selection process.
 *  New: `Response.byteString()` reads the entire response into memory as a byte string.
 *  New: `OkHttpClient.x509TrustManager` accessor.
 *  New: Permit [new WebSocket response codes][iana_websocket]: 1012 (Service Restart), 1013 (Try
    Again Later), and 1014 (invalid response from the upstream).
 *  New: Build with Kotlin 1.3.41, BouncyCastle 1.62, and Conscrypt 2.2.1.
 *  Fix: Recover gracefully when a a coalesced connection immediately goes unhealthy.
 *  Fix: Defer the `SecurityException` when looking up the default proxy selector.
 *  Fix: Don't use brackets formatting IPv6 host names in MockWebServer.
 *  Fix: Don't permit cache iterators to remove entries that are being written.


## Version 4.0.1

_2019-07-10_

 *  Fix: Tolerate null-hostile lists in public API. Lists created with `List.of(...)` don't like it
    when you call `contains(null)` on them!
 *  Fix: Retain binary-compatibility in `okhttp3.internal.HttpHeaders.hasBody()`. Some unscrupulous
    coders call this and we don't want their users to suffer.


## Version 4.0.0

_2019-06-26_

**This release upgrades OkHttp to Kotlin.** We tried our best to make fast and safe to upgrade
from OkHttp 3.x. We wrote an [upgrade guide][upgrading_to_okhttp_4] to help with the migration and a
[blog post][okhttp4_blog_post] to explain it.

 *  Fix: Target Java 8 bytecode for Java and Kotlin.


## Version 4.0.0-RC3

_2019-06-24_

 *  Fix: Retain binary-compatibility in `okhttp3.internal.HttpMethod`. Naughty third party SDKs
    import this and we want to ease upgrades for their users.


## Version 4.0.0-RC2

_2019-06-21_

 *  New: Require Kotlin 1.3.40.
 *  New: Change the Kotlin API from `File.toRequestBody()` to `File.asRequestBody()` and
    `BufferedSource.toResponseBody()` to `BufferedSource.asResponseBody()`. If the returned value
    is a view of what created it, we use _as_.
 *  Fix: Permit response codes of zero for compatibility with OkHttp 3.x.
 *  Fix: Change the return type of `MockWebServer.takeRequest()` to be nullable.
 *  Fix: Make `Call.clone()` public to Kotlin callers.


## Version 4.0.0-RC1

_2019-06-03_

 *  First stable preview of OkHttp 4.


## Version 3.x

[Change log](http://square.github.io/okhttp/changelog_3x/)


 [iana_websocket]: https://www.iana.org/assignments/websocket/websocket.txt
 [okhttp4_blog_post]: https://cashapp.github.io/2019-06-26/okhttp-4-goes-kotlin
 [upgrading_to_okhttp_4]: https://square.github.io/okhttp/upgrading_to_okhttp_4/
