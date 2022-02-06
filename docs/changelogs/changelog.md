Change Log
==========

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


## Version 4.x

[Change log](changelog_4x.md)


 [graalvm]: https://www.graalvm.org/
 [graalvm_21]: https://www.graalvm.org/release-notes/21_0/
 [kotlin_1_4_20]: https://github.com/JetBrains/kotlin/releases/tag/v1.4.20
 [kotlin_1_5_31]: https://github.com/JetBrains/kotlin/releases/tag/v1.5.31
 [kotlin_1_6_10]: https://github.com/JetBrains/kotlin/releases/tag/v1.6.10
 [okio_2_9_0]: https://square.github.io/okio/changelog/#version-290
 [okio_3_0_0]: https://square.github.io/okio/changelog/#version-300
 [rfc_8305]: https://tools.ietf.org/html/rfc8305
