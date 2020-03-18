Change Log
==========

## Version 4.4.1

_2020-03-08_

 *  Fix: Don't reuse a connection on redirect if certs match but DNS does not. For better
    locality and performance OkHttp attempts to use the same pooled connection across redirects and
    follow-ups. It independently shares connections when the IP addresses and certificates match,
    even if the host names do not. In 4.4.0 we introduced a regression where we shared a connection
    when certificates matched but the DNS addresses did not. This would only occur when following a
    redirect from one hostname to another, and where both hosts had common certificates.

 *  Fix: Don't fail on a redirect when a client has configured a 'trust everything' trust manager.
    Typically this would cause certain redirects to fail in debug and development configurations.


## Version 4.4.0

_2020-02-17_

 *  New: Support `canceled()` as an event that can be observed by `EventListener`. This should be
    useful for splitting out canceled calls in metrics.

 *  New: Publish a [bill of materials (BOM)][bom] for OkHttp. Depend on this from Gradle or Maven to
    keep all of your OkHttp artifacts on the same version, even if they're declared via transitive
    dependencies. You can even omit versions when declaring other OkHttp dependencies.

    ```kotlin
    dependencies {
       api(platform("com.squareup.okhttp3:okhttp-bom:4.4.0"))
       api("com.squareup.okhttp3:okhttp")              // No version!
       api("com.squareup.okhttp3:logging-interceptor") // No version!
    }
    ```

 *  New: Upgrade to Okio 2.4.3.

    ```kotlin
    implementation("com.squareup.okio:okio:2.4.3")
    ```

 *  Fix: Limit retry attempts for HTTP/2 `REFUSED_STREAM` and `CANCEL` failures.
 *  Fix: Retry automatically when incorrectly sharing a connection among multiple hostnames. OkHttp
    shares connections when hosts share both IP addresses and certificates, such as `squareup.com`
    and `www.squareup.com`. If a server refuses such sharing it will return HTTP 421 and OkHttp will
    automatically retry on an unshared connection.
 *  Fix: Don't crash if a TLS tunnel's response body is truncated.
 *  Fix: Don't track unusable routes beyond their usefulness. We had a bug where we could track
    certain bad routes indefinitely; now we only track the ones that could be necessary.
 *  Fix: Defer proxy selection until a proxy is required. This saves calls to `ProxySelector` on
    calls that use a pooled connection.


## Version 4.3.1

_2020-01-07_

 *  Fix: Don't crash with a `NullPointerException` when a web socket is closed before it connects.
    This regression was introduced in OkHttp 4.3.0.
 *  Fix: Don't crash with an `IllegalArgumentException` when using custom trust managers on 
    Android 10. Android uses reflection to look up a magic `checkServerTrusted()` method and we 
    didn't have it.
 *  Fix: Explicitly specify the remote server name when making HTTPS connections on Android 5. In
    4.3.0 we introduced a regression where server name indication (SNI) was broken on Android 5.


## Version 4.3.0

_2019-12-31_

 *  Fix: Degrade HTTP/2 connections after a timeout. When an HTTP/2 stream times out it may impact
    the stream only or the entire connection. With this fix OkHttp will now send HTTP/2 pings after 
    a stream timeout to determine whether the connection should remain eligible for pooling.

 *  Fix: Don't call `EventListener.responseHeadersStart()` or `responseBodyStart()` until bytes have
    been received. Previously these events were incorrectly sent too early, when OkHttp was ready to
    read the response headers or body, which mislead tracing tools. Note that the `responseFailed()`
    event always used to follow one of these events; now it may be sent without them.

 *  New: Upgrade to Kotlin 1.3.61.
 
 *  New: Match any number of subdomains with two asterisks in `CertificatePinner`. For example, 
    `**.squareup.com` matches `us-west.www.squareup.com`, `www.squareup.com` and `squareup.com`.

 *  New: Share threads more aggressively between OkHttp's HTTP/2 connections, connection pool, 
    web sockets, and cache. OkHttp has a new internal task runner abstraction for managed task
    scheduling. In your debugger you will see new thread names and more use of daemon threads.

 *  Fix: Don't drop callbacks on unexpected exceptions. When an interceptor throws an unchecked 
    exception the callback is now notified that the call was canceled. The exception is still sent 
    to the uncaught exception handler for reporting and recovery.

 *  Fix: Un-deprecate `MockResponse.setHeaders()` and other setters. These were deprecated in OkHttp
    4.0 but that broke method chaining for Java callers.

 *  Fix: Don't crash on HTTP/2 HEAD requests when the `Content-Length` header is present but is not
    consistent with the length of the response body.

 *  Fix: Don't crash when converting a `HttpUrl` instance with an unresolvable hostname to a URI.
    The new behavior strips invalid characters like `"` and `{` from the hostname before converting.

 *  Fix: Undo a performance regression introduced in OkHttp 4.0 caused by differences in behavior
    between Kotlin's `assert()` and Java's `assert()`. (Kotlin always evaluates the argument; Java
    only does when assertions are enabled.) 

 *  Fix: Honor `RequestBody.isOneShot()` in `HttpLoggingInterceptor`.


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


 [bom]: https://docs.gradle.org/6.2/userguide/platforms.html#sub:bom_import
 [iana_websocket]: https://www.iana.org/assignments/websocket/websocket.txt
 [okhttp4_blog_post]: https://cashapp.github.io/2019-06-26/okhttp-4-goes-kotlin
 [upgrading_to_okhttp_4]: https://square.github.io/okhttp/upgrading_to_okhttp_4/
