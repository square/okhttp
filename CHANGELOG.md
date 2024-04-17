Change Log
==========

## Version 4.x

See [4.x Change log](https://square.github.io/okhttp/changelogs/changelog_4x/) for the stable version changelogs.

## Version 5.0.0-alpha.13

_2024-04-16_

 *  Breaking: Tag unstable new APIs as `@ExperimentalOkHttpApi`. We intend to release OkHttp 5.0
    without stabilizing these new APIs first.

    Do not use these experimental APIs in modules that may be executed using a version of OkHttp
    different from the version that the module was compiled with. Do not use them in published
    libraries. Do not use them if you aren't willing to track changes to them.

 *  Breaking: Drop support for Kotlin Multiplatform.

    We planned to support multiplatform in OkHttp 5.0, but after building it, we weren't happy with
    the implementation trade-offs. We can't use our HTTP client engine on Kotlin/JS, and we weren't
    prepared to build a TLS API for Kotlin/Native.

    We'd prefer a multiplatform HTTP client API that's backed by OkHttp on Android and JVM, and
    other engines on other platforms. [Ktor] does this pretty well today!

 *  Breaking: Use `kotlin.time.Duration` in APIs like `OkHttpClient.Builder.callTimeout()`. This
    update also drops support for the `DurationUnit` functions introduced in earlier alpha releases
    of OkHttp 5.

 *  Breaking: Reorder the parameters in the Cache constructor that was introduced in 5.0.0-alpha.3.

 *  New: `Request.Builder.cacheUrlOverride()` customizes the cache key used for a request. This can
    be used to make canonical URLs for the cache that omit insignificant query parameters or other
    irrelevant data.

    This feature may be used with `POST` requests to cache their responses. In such cases the
    request body is not used to determine the cache key, so you must manually add cache-relevant
    data to the override URL. For example, you could add a `request-body-sha256` query parameter so
    requests with the same POST data get the same cache entry.

 *  New: `HttpLoggingInterceptor.redactQueryParams()` configures the query parameters to redact
    in logs. For best security, don't put sensitive information in query parameters.

 *  New: `ConnectionPool.setPolicy()` configures a minimum connection pool size for a target
    address. Use this to proactively open HTTP connections.

    Connections opened to fulfill this policy are subject to the connection pool's
    `keepAliveDuration` but do not count against the pool-wide `maxIdleConnections` limit.

    This feature increases the client's traffic and the load on the server. Talking to your server's
    operators before adopting it.

 *  New in okhttp-android: `HttpLoggingInterceptor.androidLogging()` and
    `LoggingEventListener.androidLogging()` write HTTP calls or events to Logcat.

 *  New: `OkHttpClient.webSocketCloseTimeout` configures how long a web socket connection will wait
    for a graceful shutdown before it performs an abrupt shutdown.

 *  Fix: Honor `RequestBody.isOneShot()` in `MultipartBody`

 *  Fix in `okhttp-coroutines`: Don't leak response bodies in `executeAsync()`. We had a bug where
    we didn't call `Response.close()` if the coroutine was canceled before its response was
    returned.

 *  Upgrade: [Okio 3.9.0][okio_3_9_0].

 *  Upgrade: [Kotlin 1.9.23][kotlin_1_9_23].

 *  Upgrade: [Unicode® IDNA 15.1.0][idna_15_1_0]


## Version 5.0.0-alpha.12

_2023-12-17_

We took too long to cut this release and there's a lot of changes in it. We've been busy.

Although this release is labeled _alpha_, the only unstable thing in it is our new APIs. This
release has many critical bug fixes and is safe to run in production. We're eager to stabilize our
new APIs so we can get out of alpha.

 *  New: Support Java 21's virtual threads (‘OpenJDK Project Loom’). We changed OkHttp's internals
    to use `Lock` and `Condition` instead of `synchronized` for best resource utilization.

 *  New: Switch our Internationalized Domain Name (IDN) implementation to [UTS #46 Nontransitional
    Processing][uts46]. With this fix, the `ß` code point no longer maps to `ss`. OkHttp now embeds
    its own IDN mapping table in the library.

 *  New: Prefer the client's configured precedence order for TLS cipher suites. (OkHttp used to
    prefer the JDK’s precedence order.) This change may cause your HTTP calls to negotiate a
    different cipher suite than before! OkHttp's defaults cipher suites are selected for good
    security and performance.

 *  New: `ConnectionListener` publishes events for connects, disconnects, and use of pooled
    connections.

 *  Fix: Immediately update the connection's flow control window instead of waiting for the
    receiving stream to process it.

    This change may increase OkHttp's memory use for applications that make many concurrent HTTP
    calls and that can receive data faster than they can process it. Previously, OkHttp limited
    HTTP/2 to 16 MiB of unacknowledged data per connection. With this fix there is a limit of 16 MiB
    of unacknowledged data per stream and no per-connection limit.

 *  Fix: Don't close a `Deflater` while we're still using it to compress a web socket message. We
    had a severe bug where web sockets were closed on the wrong thread, which caused
    `NullPointerException` crashes in `Deflater`.

 *  Fix: Don't crash after a web socket fails its connection upgrade. We incorrectly released
    the web socket's connections back to the pool before their resources were cleaned up.

 *  Fix: Don't infinite loop when a received web socket message has self-terminating compressed
    data.

 *  Fix: Don't fail the call when the response code is ‘HTTP 102 Processing’ or ‘HTTP 103 Early
    Hints’.

 *  Fix: Honor interceptors' changes to connect and read timeouts.

 *  Fix: Recover gracefully when a cached response is corrupted on disk.

 *  Fix: Don't leak file handles when a cache disk write fails.

 *  Fix: Don't hang when the public suffix database cannot be loaded. We had a bug where a failure
    reading the public suffix database would cause subsequent reads to hang when they should have
    crashed.

 *  Fix: Avoid `InetAddress.getCanonicalHostName()` in MockWebServer. This avoids problems if the
    host machine's IP address has additional DNS registrations.

 *  New: Create a JPMS-compatible artifact for `JavaNetCookieJar`. Previously, multiple OkHttp
    artifacts defined classes in the `okhttp3` package, but this is forbidden by the Java module
    system. We've fixed this with a new package (`okhttp3.java.net.cookiejar`) and a new artifact,
    `com.squareup.okhttp3:okhttp-java-net-cookiehandler`. (The original artifact now delegates to
    this new one.)

    ```kotlin
    implementation("com.squareup.okhttp3:okhttp-java-net-cookiehandler:5.0.0-alpha.12")
    ```

 *  New: `Cookie.sameSite` determines whether cookies should be sent on cross-site requests. This
    is used by servers to defend against Cross-Site Request Forgery (CSRF) attacks.

 *  New: Log the total time of the HTTP call in `HttpLoggingInterceptor`.

 *  New: `OkHttpClient.Builder` now has APIs that use `kotlin.time.Duration`.

 *  New: `mockwebserver3.SocketPolicy` is now a sealed interface. This is one of several
    backwards-incompatible API changes that may impact early adopters of this alpha API.

 *  New: `mockwebserver3.Stream` for duplex streams.

 *  New: `mockwebserver3.MockResponseBody` for streamed response bodies.

 *  New: `mockwebserver3.MockResponse` is now immutable, with a `Builder`.

 *  New: `mockwebserver3.RecordedRequest.handshakeServerNames` returns the SNI (Server Name
    Indication) attribute from the TLS handshake.

 *  Upgrade: [Kotlin 1.9.21][kotlin_1_9_21].

 *  Upgrade: [Okio 3.7.0][okio_3_7_0].


## Version 5.0.0-alpha.11

_2022-12-24_

 *  New: Enable fast fallback by default. It's our implementation of Happy Eyeballs,
    [RFC 8305][rfc_8305]. Disable with `OkHttpClient.Builder.fastFallback(false)`.
 *  Fix: Don't log response bodies for server-sent events.
 *  Fix: Skip early hints (status code 103) responses.
 *  Fix: Don't log sensitive headers in `Request.toString()`.
 *  Fix: Don't crash when the dispatcher's `ExecutorService` is shutdown with many
    calls still enqueued.
 *  Upgrade: [GraalVM 22][graalvm_22].
 *  Upgrade: [Kotlin 1.7.10][kotlin_1_7_10].


## Version 5.0.0-alpha.10

_2022-06-26_

 *  Fix: Configure the multiplatform artifact (`com.squareup.okhttp3:okhttp:3.x.x`) to depend on the
    JVM artifact (`com.squareup.okhttp3:okhttp-jvm:3.x.x`) for Maven builds. This should work-around
    an issue where Maven doesn't interpret Gradle metadata.
 *  Fix: Make another attempt at supporting Kotlin 1.5.31 at runtime. We were crashing on
    `DurationUnit` which was a typealias in 1.5.x.
 *  Upgrade: [Okio 3.2.0][okio_3_2_0].


## Version 5.0.0-alpha.9

_2022-06-16_

 *  New: Enforce label length limits in URLs. `HttpUrl` now rejects URLs whose domains aren't valid.
    This includes overly-long domain names (longer than 253 characters), overly-long labels (more
    than 63 characters between dots), and empty labels.
 *  New: Don't include the `Content-Length` header in multipart bodies. Servers must delimit
    OkHttp's request bodies using the boundary only. (This change makes OkHttp more consistent with
    browsers and other HTTP clients.)
 *  New: Drop the `tunnelProxy` argument in `MockWebServer.useHttps()`. This change only impacts
    the OkHttp 5.x API which uses the `mockwebserver3` package.
 *  Fix: Don't call `toDuration()` which isn't available in kotlin-stdlib 1.4.


## Version 5.0.0-alpha.8

_2022-06-08_

 *  Fix: Change how `H2_PRIOR_KNOWLEDGE` works with HTTP proxies. Previously OkHttp assumed the
    proxy itself was a prior knowledge HTTP/2 server. With this update, OkHttp attempts a `CONNECT`
    tunnel just as it would with HTTPS. For prior knowledge with proxies OkHttp's is now consistent
    with these curl arguments:

    ```
    curl \
      --http2-prior-knowledge \
      --proxy localhost:8888 \
      --proxytunnel \
      http://squareup.com/robots.txt
    ```

 *  Fix: Support executing OkHttp on kotlin-stdlib versions as old as 1.4. The library still builds
    on up-to-date Kotlin releases (1.6.21) but no longer needs that version as a runtime dependency.
    This should make it easier to use OkHttp in Gradle plugins.

 *  Fix: Don't start the clock on response timeouts until the request body is fully transmitted.
    This is only relevant for duplex request bodies, because they are written concurrently when
    reading the response body.

 *  New: `MockResponse.inTunnel()` is a new `mockwebserver3` API to configure responses that are
    served while creating a proxy tunnel. This obsoletes both the `tunnelProxy` argument on
    `MockWebServer` and the `UPGRADE_TO_SSL_AT_END` socket option. (Only APIs on `mockwebserver3`
    are changed; the old `okhttp3.mockwebserver` APIs remain as they always have been.


## Version 5.0.0-alpha.7

_2022-04-26_

**This release introduces new Kotlin-friendly APIs.** When we migrated OkHttp from Java to Kotlin in
OkHttp 4.0, we kept our Java-first APIs. With 5.0 we're continuing to support Java and adding
additional improvements for Kotlin users. In this alpha we're excited to skip-the-builder for
requests and remove a common source of non-null assertions (`!!`) on the response body.

The alpha releases in the 5.0.0 series have production-quality code and an unstable API. We expect
to make changes to the APIs introduced in 5.0.0-alpha.X. These releases are safe for production use
and 'alpha' strictly signals that we're still experimenting with some new APIs. If you're eager for
the fixes or features below, please upgrade.

 *  New: Named and default parameters constructor for `Request`:

    ```
    val request = Request(
      url = "https://cash.app/".toHttpUrl(),
    )
    ```

 *  New: `Response.body` is now non-null. This was generally the case in OkHttp 4.x, but the Kotlin
    type declaration was nullable to support rare cases like the body on `Response.cacheResponse`,
    `Response.networkResponse`, and `Response.priorResponse`. In such cases the body is now
    non-null, but attempts to read its content will fail.
 *  New: Kotlin-specific APIs for request tags. Kotlin language users can lookup tags with a type
    parameter only, like `request.tag<MyTagClass>()`.
 *  New: MockWebServer has improved support for HTTP/1xx responses. Once you've migrated to the new
    `mockwebserver3` package, there's a new field, `MockResponse.informationalResponses`.
 *  Fix: Don't interpret trailers as headers after an HTTP/100 response. This was a bug only when
    the HTTP response body itself is empty.
 *  Fix: Don't crash when a fast fallback call has both a deferred connection and a held connection.
 *  Fix: `OkHttpClient` no longer implements `Cloneable`. It never should have; the class is
    immutable. This is left over from OkHttp 2.x (!) when that class was mutable. We're using the
    5.x upgrade as an opportunity to remove very obsolete APIs.
 *  Fix: Recover gracefully when Android's `NativeCrypto` crashes with `"ssl == null"`. This occurs
    when OkHttp retrieves ALPN state on a closed connection.
 *  Upgrade: [Kotlin 1.6.21][kotlin_1_6_21].
 *  Upgrade: [Okio 3.1.0][okio_3_1_0].


## Version 5.0.0-alpha.6

_2022-03-14_

 *  Fix: Don't attempt to close pooled connections. We saw occasional fast fallback calls crash in
    the previous alpha due to an unexpected race.


## Version 5.0.0-alpha.5

_2022-02-21_

 *  Fix: Don't include [Assertk][assertk] in OkHttp's production dependencies. This regression was
    introduced in the 5.0.0-alpha.4 release.
 *  Fix: Don't ask `Dns` implementations to resolve strings that are already IP addresses.
 *  Fix: Change fast fallback to race TCP handshakes only. To avoid wasted work, OkHttp will not
    attempt multiple TLS handshakes for the same call concurrently.
 *  Fix: Don't crash loading the public suffix database in GraalVM native images. The function
    `HttpUrl.topPrivateDomain()` uses a resource file to identify private domains, but we didn't
    include this file on GraalVM.


## Version 5.0.0-alpha.4

_2022-02-01_

**This release introduces fast fallback to better support mixed IPv4+IPv6 networks.** Fast fallback
is what we're calling our implementation of Happy Eyeballs, [RFC 8305][rfc_8305]. With this
feature OkHttp will attempt both IPv6 and IPv4 connections concurrently, keeping whichever connects
first. Fast fallback gives IPv6 connections a 250 ms head start so IPv6 is preferred on networks
where it's available.

To opt-in, configure your `OkHttpClient.Builder`:


```
OkHttpClient client = new OkHttpClient.Builder()
    .fastFallback(true)
    .build();
```

 *  New: Change the build from Kotlin-JVM to Kotlin-multiplatform (which includes JVM). Both
    native and JavaScript platforms are unstable preview releases and subject to
    backwards-incompatible changes in forthcoming releases.
 *  Fix: Don't crash loading the public suffix database resource in obfuscated builds.
 *  Fix: Don't silently ignore calls to `EventSource.cancel()` made from
    `EventSourceListener.onOpen()`.
 *  Fix: Enforce the max intermediates constraint when using pinned certificates with Conscrypt.
    This impacts Conscrypt when the server's presented certificates form both a trusted-but-unpinned
    chain and an untrusted-but-pinned chain.
 *  Upgrade: [Kotlin 1.6.10][kotlin_1_6_10].


## Version 5.0.0-alpha.3

_2021-11-22_

 *  Fix: Change `Headers.toString()` to redact authorization and cookie headers.
 *  Fix: Don't do DNS to get the hostname for `RecordedRequest.requestUrl`. This was doing a DNS
    lookup for the local hostname, but we really just wanted the `Host` header.
 *  Fix: Don't crash with a `InaccessibleObjectException` when detecting the platform trust manager
    on Java 17+.
 *  Fix: Don't crash if a cookie's value is a lone double quote character.
 *  Fix: Don't crash when canceling an event source created by `EventSources.processResponse()`.
 *  New: `Cache` now has a public constructor that takes an [okio.FileSystem]. This should make it
    possible to implement decorators for cache encryption or compression.
 *  New: `Cookie.newBuilder()` to build upon an existing cookie.
 *  New: Use TLSv1.3 when running on JDK 8u261 or newer.
 *  New: `QueueDispatcher.clear()` may be used to reset a MockWebServer instance.
 *  New: `FileDescriptor.toRequestBody()` may be particularly useful for users of Android's Storage
    Access Framework.
 *  Upgrade: [Kotlin 1.5.31][kotlin_1_5_31].
 *  Upgrade: [Okio 3.0.0][okio_3_0_0].


## Version 5.0.0-alpha.2

_2021-01-30_

**In this release MockWebServer has a new Maven coordinate and package name.** A longstanding
problem with MockWebServer has been its API dependency on JUnit 4. We've reorganized things to
remove that dependency while preserving backwards compatibility.

| Maven Coordinate                                         | Package Name          | Description                       |
| :------------------------------------------------------- | :-------------------- | :-------------------------------- |
| com.squareup.okhttp3:mockwebserver3:5.0.0-alpha.2        | mockwebserver3        | Core module. No JUnit dependency! |
| com.squareup.okhttp3:mockwebserver3-junit4:5.0.0-alpha.2 | mockwebserver3.junit4 | Optional JUnit 4 integration.     |
| com.squareup.okhttp3:mockwebserver3-junit5:5.0.0-alpha.2 | mockwebserver3.junit5 | Optional JUnit 5 integration.     |
| com.squareup.okhttp3:mockwebserver:5.0.0-alpha.2         | okhttp3.mockwebserver | Obsolete. Depends on JUnit 4.     |

The new APIs use `mockwebserver3` in both the Maven coordinate and package name. This new API is
**not stable** and will likely change before the final 5.0.0 release.

If you have code that subclasses `okhttp3.mockwebserver.QueueDispatcher`, this update is not source
or binary compatible. Migrating to the new `mockwebserver3` package will fix this problem.

 *  New: DNS over HTTPS is now a stable feature of OkHttp. We introduced this as an experimental
    module in 2018. We are confident in its stable API and solid implementation.
 *  Fix: Work around a crash in Android 10 and 11 that may be triggered when two threads
    concurrently close an SSL socket. This would have appeared in crash logs as
    `NullPointerException: bio == null`.
 *  Fix: Use plus `+` instead of `%20` to encode space characters in `FormBody`. This was a
    longstanding bug in OkHttp. The fix makes OkHttp consistent with major web browsers.
 *  Fix: Don't crash if Conscrypt returns a null version.
 *  Fix: Include the public suffix data as a resource in GraalVM native images.
 *  Fix: Fail fast when the cache is corrupted.
 *  Fix: Fail fast when a private key cannot be encoded.
 *  Fix: Fail fast when attempting to verify a non-ASCII hostname.
 *  Upgrade: [GraalVM 21][graalvm_21].
 *  Upgrade: [Kotlin 1.4.20][kotlin_1_4_20].


## Version 5.0.0-alpha.1

_2021-01-30_

**This release adds initial support for [GraalVM][graalvm].**

GraalVM is an exciting new platform and we're eager to adopt it. The startup time improvements over
the JVM are particularly impressive. Try it with okcurl:

```
$ ./gradlew okcurl:nativeImage
$ ./okcurl/build/graal/okcurl https://cash.app/robots.txt
```

This is our first release that supports GraalVM. Our code on this platform is less mature than JVM
and Android! Please report any issues you encounter: we'll fix them urgently.

 *  Fix: Attempt to read the response body even if the server canceled the request. This will cause
    some calls to return nice error codes like `HTTP/1.1 429 Too Many Requests` instead of transport
    errors like `SocketException: Connection reset` and `StreamResetException: stream was reset:
    CANCEL`.
 *  New: Support OSGi metadata.
 *  Upgrade: [Okio 2.9.0][okio_2_9_0].

    ```kotlin
    implementation("com.squareup.okio:okio:2.9.0")
    ```

Note that this was originally released on 2020-10-06 as 4.10.0-RC1. The only change from that
release is the version name.


[Ktor]: https://ktor.io/
[assertk]: https://github.com/willowtreeapps/assertk
[graalvm]: https://www.graalvm.org/
[graalvm_21]: https://www.graalvm.org/release-notes/21_0/
[graalvm_22]: https://www.graalvm.org/release-notes/22_2/
[idna_15_1_0]: https://www.unicode.org/reports/tr46/#Modifications
[kotlin_1_4_20]: https://github.com/JetBrains/kotlin/releases/tag/v1.4.20
[kotlin_1_5_31]: https://github.com/JetBrains/kotlin/releases/tag/v1.5.31
[kotlin_1_6_10]: https://github.com/JetBrains/kotlin/releases/tag/v1.6.10
[kotlin_1_6_21]: https://github.com/JetBrains/kotlin/releases/tag/v1.6.21
[kotlin_1_7_10]: https://github.com/JetBrains/kotlin/releases/tag/v1.7.10
[kotlin_1_9_21]: https://github.com/JetBrains/kotlin/releases/tag/v1.9.21
[kotlin_1_9_23]: https://github.com/JetBrains/kotlin/releases/tag/v1.9.23
[loom]: https://docs.oracle.com/en/java/javase/21/core/virtual-threads.html
[okio_2_9_0]: https://square.github.io/okio/changelog/#version-290
[okio_3_0_0]: https://square.github.io/okio/changelog/#version-300
[okio_3_1_0]: https://square.github.io/okio/changelog/#version-310
[okio_3_2_0]: https://square.github.io/okio/changelog/#version-320
[okio_3_7_0]: https://square.github.io/okio/changelog/#version-370
[okio_3_9_0]: https://square.github.io/okio/changelog/#version-390
[rfc_8305]: https://tools.ietf.org/html/rfc8305
[uts46]: https://www.unicode.org/reports/tr46
