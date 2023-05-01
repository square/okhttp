Change Log
==========

## Version 4.x

See [4.x Change log](https://square.github.io/okhttp/changelogs/changelog_4x/) for the stable version changelogs.

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


[assertk]: https://github.com/willowtreeapps/assertk
[graalvm]: https://www.graalvm.org/
[graalvm_21]: https://www.graalvm.org/release-notes/21_0/
[graalvm_22]: https://www.graalvm.org/release-notes/22_2/
[kotlin_1_4_20]: https://github.com/JetBrains/kotlin/releases/tag/v1.4.20
[kotlin_1_5_31]: https://github.com/JetBrains/kotlin/releases/tag/v1.5.31
[kotlin_1_6_10]: https://github.com/JetBrains/kotlin/releases/tag/v1.6.10
[kotlin_1_6_21]: https://github.com/JetBrains/kotlin/releases/tag/v1.6.21
[kotlin_1_7_10]: https://github.com/JetBrains/kotlin/releases/tag/v1.7.10
[okio_2_9_0]: https://square.github.io/okio/changelog/#version-290
[okio_3_0_0]: https://square.github.io/okio/changelog/#version-300
[okio_3_1_0]: https://square.github.io/okio/changelog/#version-310
[okio_3_2_0]: https://square.github.io/okio/changelog/#version-320
[rfc_8305]: https://tools.ietf.org/html/rfc8305
