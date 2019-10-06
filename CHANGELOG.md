Change Log
==========

## Version 4.2.2

_2019-10-06_

 *  Fix: When closing a canceled HTTP/2 stream, don't send the `END_STREAM` flag. This could cause
    the server to incorrectly interpret the stream as having completed normally. This is most useful
    when a request body needs to cancel its own call.


## Version 4.2.1

_2019-10-02_

 *  Fix: In 4.1.0 we introduced a performance regression that prevented connections from being
    pooled in certain situations. We have good test coverage for connection pooling but we missed
    this because it only occurs if you have proxy configured and you share a connection pool among
    multiple `OkHttpClient` instances.

    This particularly-subtle bug was caused by us assigning each `OkHttpClient` instance its own
    `NullProxySelector` when an explicit proxy is configured. But we don't share connections when
    the proxy selectors are different. Ugh!


## Version 4.2.0

_2019-09-10_

 *  New: API to decode a certificate and private key to create a `HeldCertificate`. This accepts a
    string containing both a certificate and PKCS #8-encoded private key.

    ```kotlin
    val heldCertificate = HeldCertificate.decode("""
        |-----BEGIN CERTIFICATE-----
        |MIIBYTCCAQegAwIBAgIBKjAKBggqhkjOPQQDAjApMRQwEgYDVQQLEwtlbmdpbmVl
        |cmluZzERMA8GA1UEAxMIY2FzaC5hcHAwHhcNNzAwMTAxMDAwMDA1WhcNNzAwMTAx
        |MDAwMDEwWjApMRQwEgYDVQQLEwtlbmdpbmVlcmluZzERMA8GA1UEAxMIY2FzaC5h
        |cHAwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASda8ChkQXxGELnrV/oBnIAx3dD
        |ocUOJfdz4pOJTP6dVQB9U3UBiW5uSX/MoOD0LL5zG3bVyL3Y6pDwKuYvfLNhoyAw
        |HjAcBgNVHREBAf8EEjAQhwQBAQEBgghjYXNoLmFwcDAKBggqhkjOPQQDAgNIADBF
        |AiAyHHg1N6YDDQiY920+cnI5XSZwEGhAtb9PYWO8bLmkcQIhAI2CfEZf3V/obmdT
        |yyaoEufLKVXhrTQhRfodTeigi4RX
        |-----END CERTIFICATE-----
        |-----BEGIN PRIVATE KEY-----
        |MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCA7ODT0xhGSNn4ESj6J
        |lu/GJQZoU9lDrCPeUcQ28tzOWw==
        |-----END PRIVATE KEY-----
        """.trimMargin())
    val handshakeCertificates = HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .build()
    val server = MockWebServer()
    server.useHttps(handshakeCertificates.sslSocketFactory(), false)
    ```

    Get these strings with `HeldCertificate.certificatePem()` and `privateKeyPkcs8Pem()`.

 *  Fix: Handshake now returns peer certificates in canonical order: each certificate is signed by
    the certificate that follows and the last certificate is signed by a trusted root.

 *  Fix: Don't lose HTTP/2 flow control bytes when incoming data races with a stream close. If this
    happened enough then eventually the connection would stall.

 *  Fix: Acknowledge and apply inbound HTTP/2 settings atomically. Previously we had a race where we
    could use new flow control capacity before acknowledging it, causing strict HTTP/2 servers to
    fail the call.


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
 *  Fix: Recover gracefully when a coalesced connection immediately goes unhealthy.
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
