Upgrading to OkHttp 4
=====================

OkHttp 4.x upgrades our implementation language from Java to Kotlin and keeps everything else the
same. We've chosen Kotlin because it gives us powerful new capabilities while integrating closely
with Java.

We spent a lot of time and energy on retaining strict compatibility with OkHttp 3.x. We're even
keeping the package name the same: `okhttp3`!

There are three kinds of compatibility we're tracking:

 * **Binary compatibility** is the ability to compile a program against OkHttp 3.x, and then to run
   it against OkHttp 4.x. We're using use the excellent [japicmp][japicmp] library via its
   [Gradle plugin][japicmp_gradle] to enforce binary compatibility.

 * **Java source compatibility** is the ability to upgrade Java uses of OkHttp 3.x to 4.x without
   changing `.java` files.

 * **Kotlin source compatibility** is the ability to upgrade Kotlin uses of OkHttp 3.x to 4.x
   without changing `.kt` files.

With one exception, OkHttp 4.x is both binary- and Java source-compatible with OkHttp 3.x. You can
use an OkHttp 4.x .jar file with applications or libraries built for OkHttp 3.x. (The exception?
`OkHttpClient` makes more things `final`.)

OkHttp is **not** source-compatible for Kotlin callers, but upgrading should be automatic thanks to
Kotlin's powerful deprecation features. Most developers should be able to use IntelliJ's _Code
Cleanup_ for a safe and fast upgrade.

For example, when we upgraded Square's Kotlin and Java codebases to OkHttp 4.x we had these
problems:

 * Single Abstract Method (SAM) conversions required us to replace lambdas with objects for Kotlin
   callers.

 * OkHttp 4.x's new `RequestBody.create()` overload conflicted with an overload in one of our
   subclasses. The compiler caught this!

We expect most projects to have similarly trivial problems with the upgrade, if any. This guide
walks through all of the changes and how to address them.


Backwards-Incompatible Changes
------------------------------

`OkHttpClient` has 26 accessors like `interceptors()` and `writeTimeoutMillis()` that were non-final
in OkHttp 3.x and are final in 4.x. These were made non-final for use with mocking frameworks like
[Mockito][mockito]. We believe subtyping `OkHttpClient` is the wrong way to test with OkHttp.

The `okhttp3.internal` package is not a published API and we change it frequently without warning.
Depending on code in this package is bad and will cause you problems with any upgrade. But the 4.x
will be particularly painful to naughty developers that import from this package! We changed a lot
to take advantage of sweet Kotlin features.


Code Cleanup
------------

IntelliJ and Android Studio offer a **Code Cleanup** feature that will automatically update
deprecated APIs with their replacements. Access this feature from the _Search Anywhere_ dialog
(double-press shift) or under the _Analyze_ menu.

The deprecation replacements that Code Cleanup possible are included in OkHttp 4.0. We will remove
them in a future update to OkHttp, so if you're skipping releases you should upgrade to OkHttp 4.0
as an intermediate step.


SAM Conversions
---------------

When you use Java APIs from Kotlin you can operate on Java interfaces as if they were Kotlin
lambdas. The [feature][kotlin_sam] is available for interfaces that define a Single Abstract Method
(SAM).

But when you use Kotlin APIs from Kotlin there's no automatic conversion. Code that used SAM lambdas
with OkHttp 3.x: must use `object :` with OkHttp 4.x:

Kotlin calling OkHttp 3.x:

```
val client = OkHttpClient.Builder()
    .dns { hostname -> InetAddress.getAllByName(hostname).toList() }
    .build()
```

Kotlin calling OkHttp 4.x:

```
val client = OkHttpClient.Builder()
    .dns(object : Dns {
      override fun lookup(hostname: String) =
          InetAddress.getAllByName(hostname).toList()
    })
    .build()
```

SAM conversion impacts these APIs:

 * Authenticator
 * Dispatcher.setIdleCallback(Runnable)
 * Dns
 * EventListener.Factory
 * HttpLoggingInterceptor.Logger
 * LoggingEventListener.Factory
 * OkHttpClient.Builder.hostnameVerifier(HostnameVerifier)


Vars and Vals
-------------

Java doesn't have language support for properties so developers make do with getters and setters.
Kotlin does have properties and we take advantage of them in OkHttp.

We recommend using _Code Cleanup_ to fix these; it'll use `@Deprecated` to find replacements and fix
them automatically.

 * **Address**: certificatePinner, connectionSpecs, dns, hostnameVerifier, protocols, proxy,
   proxyAuthenticator, proxySelector, socketFactory, sslSocketFactory, url
 * **Cache**: directory
 * **CacheControl**: immutable, maxAgeSeconds, maxStaleSeconds, minFreshSeconds, mustRevalidate,
   noCache, noStore, noTransform, onlyIfCached, sMaxAgeSeconds
 * **Challenge**: authParams, charset, realm, scheme
 * **CipherSuite**: javaName
 * **ConnectionSpec**: cipherSuites, supportsTlsExtensions, tlsVersions
 * **Cookie**: domain, expiresAt, hostOnly, httpOnly, name, path, persistent, value
 * **Dispatcher**: executorService
 * **FormBody**: size
 * **Handshake**: cipherSuite, localCertificates, localPrincipal, peerCertificates, peerPrincipal,
   tlsVersion
 * **HandshakeCertificates**: keyManager, trustManager
 * **Headers**: size
 * **HeldCertificate**: certificate, keyPair
 * **HttpLoggingInterceptor**: level
 * **HttpUrl**: encodedFragment, encodedPassword, encodedPath, encodedPathSegments, encodedQuery,
   encodedUsername, fragment, host, password, pathSegments, pathSize, port, query,
   queryParameterNames, querySize, scheme, username
 * **MockResponse**: headers, http2ErrorCode, socketPolicy, status, trailers
 * **MockWebServer**: bodyLimit, port, protocolNegotiationEnabled, protocols, requestCount,
   serverSocketFactory
 * **MultipartBody.Part**: body, headers
 * **MultipartBody.**: boundary, parts, size, type
 * **OkHttpClient**: authenticator, cache, callTimeoutMillis, certificatePinner,
   connectTimeoutMillis, connectionPool, connectionSpecs, cookieJar, dispatcher, dns,
   eventListenerFactory, followRedirects, followSslRedirects, hostnameVerifier, interceptors,
   networkInterceptors, pingIntervalMillis, protocols, proxy, proxyAuthenticator, proxySelector,
   readTimeoutMillis, retryOnConnectionFailure, socketFactory, sslSocketFactory, writeTimeoutMillis
 * **PushPromise**: headers, method, path, response
 * **Request**: body, cacheControl, headers, method, url
 * **Response**: body, cacheControl, cacheResponse, code, handshake, headers, message,
   networkResponse, priorResponse, protocol, receivedResponseAtMillis, request, sentRequestAtMillis
 * **Route**: address, proxy, socketAddress
 * **TlsVersion**: javaName


Extension Functions
-------------------

_Code Cleanup_ will fix these too:

| Java                                | Kotlin                          |
| :---------------------------------- | :------------------------------ |
| Handshake.get(SSLSession)           | SSLSession.handshake()          |
| Headers.of(Map<String, String>)     | Map<String, String>.toHeaders() |
| HttpUrl.get(String)                 | String.toHttpUrl()              |
| HttpUrl.get(URI)                    | URI.toHttpUrlOrNull()           |
| HttpUrl.get(URL)                    | URL.toHttpUrlOrNull()           |
| HttpUrl.parse(String)               | String.toHttpUrlOrNull()        |
| HttpUrl.uri()                       | HttpUrl.toUri()                 |
| HttpUrl.url()                       | HttpUrl.toUrl()                 |
| MediaType.get(String)               | String.toMediaType()            |
| MediaType.parse(String)             | String.toMediaTypeOrNull()      |
| RequestBody.create(ByteArray)       | ByteArray.toRequestBody()       |
| RequestBody.create(ByteString)      | ByteString.toRequestBody()      |
| RequestBody.create(File)            | File.toRequestBody()            |
| RequestBody.create(String)          | String.toRequestBody()          |
| ResponseBody.create(BufferedSource) | BufferedSource.toResponseBody() |
| ResponseBody.create(ByteArray)      | ByteArray.toResponseBody()      |
| ResponseBody.create(ByteString)     | ByteString.toResponseBody()     |
| ResponseBody.create(String)         | String.toResponseBody()         |


headersOf()
-----------

For symmetry with `listOf()`, `setOf()`, etc., we've replaced `Headers.of(String...)` with
`headersOf(vararg String)`.


queryParameterValues()
----------------------

The return type of `HttpUrl.queryParameterValues()` is `List<String?>`. Lists that may contain
null are uncommon and Kotlin callers may have incorrectly assigned the result to `List<String>`.


[japicmp]: https://github.com/siom79/japicmp
[japicmp_gradle]: https://github.com/melix/japicmp-gradle-plugin
[mockito]: https://site.mockito.org/
[kotlin_sam]: https://kotlinlang.org/docs/reference/java-interop.html#sam-conversions
