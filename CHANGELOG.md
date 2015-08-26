Change Log
==========

## Version 2.5.0

_2015-08-25_

 *  **OkHttp now rejects request headers that contain invalid characters.** This
    includes potential security problems (newline characters) as well as simple
    non-ASCII characters (including international characters and emoji).

 *  **Call canceling is more reliable.**  We had a bug where a socket being
     connected wasn't being closed when the application used `Call.cancel()`.

 *  **Changing a HttpUrl’s scheme now tracks the default port.** We had a bug
    where changing a URL from `http` to `https` would leave it on port 80.

 *  **Okio has been updated to 1.6.0.**
     ```
     <dependency>
       <groupId>com.squareup.okio</groupId>
       <artifactId>okio</artifactId>
       <version>1.6.0</version>
     </dependency>
     ```

 *  New: `Cache.initialize()`. Call this on a background thread to eagerly
    initialize the response cache.
 *  New: Fold `MockWebServerRule` into `MockWebServer`. This makes it easier to
    write JUnit tests with `MockWebServer`. The `MockWebServer` library now
    depends on JUnit, though it continues to work with all testing frameworks.
 *  Fix: Change `MockWebServer` to use the same logic as OkHttp when determining
    whether an HTTP request permits a body.
 *  Fix: `HttpUrl` now uses the canonical form for IPv6 addresses.
 *  Fix: Use `HttpUrl` internally.
 *  Fix: Recover from Android 4.2.2 EBADF crashes.
 *  Fix: Don't crash with an `IllegalStateException` if an HTTP/2 or SPDY
    write fails, leaving the connection in an inconsistent state.
 *  Fix: Make sure the default user agent is ASCII.


## Version 2.4.0

_2015-05-22_

 *  **Forbid response bodies on HTTP 204 and 205 responses.** Webservers that
    return such malformed responses will now trigger a `ProtocolException` in
    the client.

 *  **WebSocketListener has incompatible changes.** The `onOpen()` method is now
    called on the reader thread, so implementations must return before further
    websocket messages will be delivered. The `onFailure()` method now includes
    an HTTP response if one was returned.

## Version 2.4.0-RC1

_2015-05-16_

 *  **New HttpUrl API.** It's like `java.net.URL` but good. Note that
    `Request.Builder.url()` now throws `IllegalArgumentException` on malformed
    URLs. (Previous releases would throw a `MalformedURLException` when calling
    a malformed URL.)

 *  **We've improved connect failure recovery.** We now differentiate between
    setup, connecting, and connected and implement appropriate recovery rules
    for each. This changes `Address` to no longer use `ConnectionSpec`. (This is
    an incompatible API change).

 *  **`FormEncodingBuilder` now uses `%20` instead of `+` for encoded spaces.**
    Both are permitted-by-spec, but `%20` requires fewer special cases.

 *  **Okio has been updated to 1.4.0.**
     ```
     <dependency>
       <groupId>com.squareup.okio</groupId>
       <artifactId>okio</artifactId>
       <version>1.4.0</version>
     </dependency>
     ```

 *  **`Request.Builder` no longer accepts null if a request body is required.**
    Passing null will now fail for request methods that require a body. Instead
    use an empty body such as this one:

    ```
        RequestBody.create(null, new byte[0]);
    ```

 * **`CertificatePinner` now supports wildcard hostnames.** As always with
   certificate pinning, you must be very careful to avoid [bricking][brick]
   your app. You'll need to pin both the top-level domain and the `*.` domain
   for full coverage.

    ```
     client.setCertificatePinner(new CertificatePinner.Builder()
         .add("publicobject.com",   "sha1/DmxUShsZuNiqPQsX2Oi9uv2sCnw=")
         .add("*.publicobject.com", "sha1/DmxUShsZuNiqPQsX2Oi9uv2sCnw=")
         .add("publicobject.com",   "sha1/SXxoaOSEzPC6BgGmxAt/EAcsajw=")
         .add("*.publicobject.com", "sha1/SXxoaOSEzPC6BgGmxAt/EAcsajw=")
         .add("publicobject.com",   "sha1/blhOM3W9V/bVQhsWAcLYwPU6n24=")
         .add("*.publicobject.com", "sha1/blhOM3W9V/bVQhsWAcLYwPU6n24=")
         .add("publicobject.com",   "sha1/T5x9IXmcrQ7YuQxXnxoCmeeQ84c=")
         .add("*.publicobject.com", "sha1/T5x9IXmcrQ7YuQxXnxoCmeeQ84c=")
         .build());
    ```

 *  **Interceptors lists are now deep-copied by `OkHttpClient.clone()`.**
    Previously clones shared interceptors, which made it difficult to customize
    the interceptors on a request-by-request basis.

 *  New: `Headers.toMultimap()`.
 *  New: `RequestBody.create(MediaType, ByteString)`.
 *  New: `ConnectionSpec.isCompatible(SSLSocket)`.
 *  New: `Dispatcher.getQueuedCallCount()` and
    `Dispatcher.getRunningCallCount()`. These can be useful in diagnostics.
 *  Fix: OkHttp no longer shares timeouts between pooled connections. This was
    causing some applications to crash when connections were reused.
 *  Fix: `OkApacheClient` now allows an empty `PUT` and `POST`.
 *  Fix: Websockets no longer rebuffer socket streams.
 *  Fix: Websockets are now better at handling close frames.
 *  Fix: Content type matching is now case insensitive.
 *  Fix: `Vary` headers are not lost with `android.net.http.HttpResponseCache`.
 *  Fix: HTTP/2 wasn't enforcing stream timeouts when writing the underlying
    connection. Now it is.
 *  Fix: Never return null on `call.proceed()`. This was a bug in call
    cancelation.
 *  Fix: When a network interceptor mutates a request, that change is now
    reflected in `Response.networkResponse()`.
 *  Fix: Badly-behaving caches now throw a checked exception instead of a
    `NullPointerException`.
 *  Fix: Better handling of uncaught exceptions in MockWebServer with HTTP/2.

## Version 2.3.0

_2015-03-16_

 *  **HTTP/2 support.** We've done interop testing and haven't seen any
    problems. HTTP/2 support has been a big effort and we're particularly
    thankful to Adrian Cole who has helped us to reach this milestone.

 *  **RC4 cipher suites are no longer supported by default.** To connect to
    old, obsolete servers relying on these cipher suites, you must create a
    custom `ConnectionSpec`.

 *  **Beta WebSockets support.**. The `okhttp-ws` subproject offers a new
    websockets client. Please try it out! When it's ready we intend to include
    it with the core OkHttp library.

 *  **Okio updated to 1.3.0.**

    ```
    <dependency>
      <groupId>com.squareup.okio</groupId>
      <artifactId>okio</artifactId>
      <version>1.3.0</version>
    </dependency>
    ```

 *  **Fix: improve parallelism of async requests.** OkHttp's Dispatcher had a
    misconfigured `ExecutorService` that limited the number of worker threads.
    If you're using `Call.enqueue()` this update should significantly improve
    request concurrency.

 *  **Fix: Lazily initialize the response cache.** This avoids strict mode
    warnings when initializing OkHttp on Android‘s main thread.

 *  **Fix: Disable ALPN on Android 4.4.** That release of the feature was
    unstable and prone to native crashes in the underlying OpenSSL code.
 *  Fix: Don't send both `If-None-Match` and `If-Modified-Since` cache headers
    when both are applicable.
 *  Fix: Fail early when a port is out of range.
 *  Fix: Offer `Content-Length` headers for multipart request bodies.
 *  Fix: Throw `UnknownServiceException` if a cleartext connection is attempted
    when explicitly forbidden.
 *  Fix: Throw a `SSLPeerUnverifiedException` when host verification fails.
 *  Fix: MockWebServer explicitly closes sockets. (On some Android releases,
    closing the input stream and output stream of a socket is not sufficient.
 *  Fix: Buffer outgoing HTTP/2 frames to limit how many outgoing frames are
    created.
 *  Fix: Avoid crashing when cache writing fails due to a full disk.
 *  Fix: Improve caching of private responses.
 *  Fix: Update cache-by-default response codes.
 *  Fix: Reused `Request.Builder` instances no longer hold stale URL fields.
 *  New: ConnectionSpec can now be configured to use the SSL socket's default
    cipher suites. To use, set the cipher suites to `null`.
 *  New: Support `DELETE` with a request body.
 *  New: `Headers.of(Map)` creates headers from a Map.


## Version 2.2.0

_2014-12-30_

 *  **`RequestBody.contentLength()` now throws `IOException`.**
    This is a source-incompatible change. If you have code that calls
    `RequestBody.contentLength()`, your compile will break with this
    update. The change is binary-compatible, however: code compiled
    for OkHttp 2.0 and 2.1 will continue to work with this update.

 *  **`COMPATIBLE_TLS` no longer supports SSLv3.** In response to the
    [POODLE](http://googleonlinesecurity.blogspot.ca/2014/10/this-poodle-bites-exploiting-ssl-30.html)
    vulnerability, OkHttp no longer offers SSLv3 when negotiation an
    HTTPS connection. If you continue to need to connect to webservers
    running SSLv3, you must manually configure your own `ConnectionSpec`.

 *  **OkHttp now offers interceptors.** Interceptors are a powerful mechanism
    that can monitor, rewrite, and retry calls. The [project
    wiki](https://github.com/square/okhttp/wiki/Interceptors) has a full
    introduction to this new API.

 *  New: APIs to iterate and selectively clear the response cache.
 *  New: Support for SOCKS proxies.
 *  New: Support for `TLS_FALLBACK_SCSV`.
 *  New: Update HTTP/2 support to to `h2-16` and `hpack-10`.
 *  New: APIs to prevent retrying non-idempotent requests.
 *  Fix: Drop NPN support. Going forward we support ALPN only.
 *  Fix: The hostname verifier is now strict. This is consistent with the hostname
    verifier in modern browsers.
 *  Fix: Improve `CONNECT` handling for misbehaving HTTP proxies.
 *  Fix: Don't retry requests that failed due to timeouts.
 *  Fix: Cache 302s and 308s that include appropriate response headers.
 *  Fix: Improve pooling of connections that use proxy selectors.
 *  Fix: Don't leak connections when using ALPN on the desktop.
 *  Fix: Update Jetty ALPN to `7.1.2.v20141202` (Java 7) and `8.1.2.v20141202` (Java 8).
    This fixes a bug in resumed TLS sessions where the wrong protocol could be
    selected.
 *  Fix: Don't crash in SPDY and HTTP/2 when disconnecting before connecting.
 *  Fix: Avoid a reverse DNS-lookup for a numeric proxy address
 *  Fix: Resurrect http/2 frame logging.
 *  Fix: Limit to 20 authorization attempts.

## Version 2.1.0

_2014-11-11_

 *  New: Typesafe APIs for interacting with cipher suites and TLS versions.
 *  Fix: Don't crash when mixing authorization challenges with upload retries.


## Version 2.1.0-RC1

_2014-11-04_

 *  **OkHttp now caches private responses**. We've changed from a shared cache
    to a private cache, and will now store responses that use an `Authorization`
    header. This means OkHttp's cache shouldn't be used on middleboxes that sit
    between user agents and the origin server.

 *  **TLS configuration updated.** OkHttp now explicitly enables TLSv1.2,
    TLSv1.1 and TLSv1.0 where they are supported. It will continue to perform
    only one fallback, to SSLv3. Applications can now configure this with the
    `ConnectionSpec` class.

    To disable TLS fallback:

    ```
    client.setConnectionSpecs(Arrays.asList(
        ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT));
    ```

    To disable cleartext connections, permitting `https` URLs only:

    ```
    client.setConnectionSpecs(Arrays.asList(
        ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS));
    ```

 *  **New cipher suites.** Please confirm that your webservers are reachable
    with this limited set of cipher suites.

    ```
                                             Android
    Name                                     Version

    TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256  5.0
    TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256    5.0
    TLS_DHE_RSA_WITH_AES_128_GCM_SHA256      5.0
    TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA     4.0
    TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA     4.0
    TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA       4.0
    TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA       4.0
    TLS_ECDHE_ECDSA_WITH_RC4_128_SHA         4.0
    TLS_ECDHE_RSA_WITH_RC4_128_SHA           4.0
    TLS_DHE_RSA_WITH_AES_128_CBC_SHA         2.3
    TLS_DHE_DSS_WITH_AES_128_CBC_SHA         2.3
    TLS_DHE_RSA_WITH_AES_256_CBC_SHA         2.3
    TLS_RSA_WITH_AES_128_GCM_SHA256          5.0
    TLS_RSA_WITH_AES_128_CBC_SHA             2.3
    TLS_RSA_WITH_AES_256_CBC_SHA             2.3
    SSL_RSA_WITH_3DES_EDE_CBC_SHA            2.3  (Deprecated in 5.0)
    SSL_RSA_WITH_RC4_128_SHA                 2.3
    SSL_RSA_WITH_RC4_128_MD5                 2.3  (Deprecated in 5.0)
    ```

 *  **Okio updated to 1.0.1.**

    ```
    <dependency>
      <groupId>com.squareup.okio</groupId>
      <artifactId>okio</artifactId>
      <version>1.0.1</version>
    </dependency>
    ```

 *  **New APIs to permit easy certificate pinning.** Be warned, certificate
    pinning is dangerous and could prevent your application from trusting your
    server!

 *  **Cache improvements.** This release fixes some severe cache problems
    including a bug where the cache could be corrupted upon certain access
    patterns. We also fixed a bug where the cache was being cleared due to a
    corrupted journal. We've added APIs to configure a request's `Cache-Control`
    headers, and to manually clear the cache.

 *  **Request cancellation fixes.** This update fixes a bug where synchronous
    requests couldn't be canceled by tag. This update avoids crashing when
    `onResponse()` throws an `IOException`. That failure will now be logged
    instead of notifying the thread's uncaught exception handler. We've added a
    new API, `Call.isCanceled()` to check if a call has been canceled.

 *  New: Update `MultipartBuilder` to support content length.
 *  New: Make it possible to mock `OkHttpClient` and `Call`.
 *  New: Update to h2-14 and hpack-9.
 *  New: OkHttp includes a user-agent by default, like `okhttp/2.1.0-RC1`.
 *  Fix: Handle response code `308 Permanent Redirect`.
 *  Fix: Don't skip the callback if a call is canceled.
 *  Fix: Permit hostnames with underscores.
 *  Fix: Permit overriding the content-type in `OkApacheClient`.
 *  Fix: Use the socket factory for direct connections.
 *  Fix: Honor `OkUrlFactory` APIs that disable redirects.
 *  Fix: Don't crash on concurrent modification of `SPDY` SPDY settings.

## Version 2.0.0

This release commits to a stable 2.0 API. Read the 2.0.0-RC1 changes for advice
on upgrading from 1.x to 2.x.

_2014-06-21_

 *  **API Change**: Use `IOException` in `Callback.onFailure()`. This is
    a source-incompatible change, and is different from OkHttp 2.0.0-RC2 which
    used `Throwable`.
 *  Fix: Fixed a caching bug where we weren't storing rewritten request headers
    like `Accept-Encoding`.
 *  Fix: Fixed bugs in handling the SPDY window size. This was stalling certain
    large downloads
 *  Update the language level to Java 7. (OkHttp requires Android 2.3+ or Java 7+.)

## Version 2.0.0-RC2

_2014-06-11_

This update fixes problems in 2.0.0-RC1. Read the 2.0.0-RC1 changes for
advice on upgrading from 1.x to 2.x.

 *  Fix: Don't leak connections! There was a regression in 2.0.0-RC1 where
    connections were neither closed nor pooled.
 *  Fix: Revert builder-style return types from OkHttpClient's timeout methods
    for binary compatibility with OkHttp 1.x.
 *  Fix: Don't skip client stream 1 on SPDY/3.1. This fixes SPDY connectivity to
    `https://google.com`, which doesn't follow the SPDY/3.1 spec!
 *  Fix: Always configure NPN headers. This fixes connectivity to
    `https://facebook.com` when SPDY and HTTP/2 are both disabled. Otherwise an
    unexpected NPN response is received and OkHttp crashes.
 *  Fix: Write continuation frames when HPACK data is larger than 16383 bytes.
 *  Fix: Don't drop uncaught exceptions thrown in async calls.
 *  Fix: Throw an exception eagerly when a request body is not legal. Previously
    we ignored the problem at request-building time, only to crash later with a
    `NullPointerException`.
 *  Fix: Include a backwards-compatible `OkHttp-Response-Source` header with
    `OkUrlFactory `responses.
 *  Fix: Don't include a default User-Agent header in requests made with the Call
    API. Requests made with OkUrlFactory will continue to have a default user
    agent.
 *  New: Guava-like API to create headers:

    ```
    Headers headers = Headers.of(name1, value1, name2, value2, ...).
    ```

 *  New: Make the content-type header optional for request bodies.
 *  New: `Response.isSuccessful()` is a convenient API to check response codes.
 *  New: The response body can now be read outside of the callback. Response
    bodies must always be closed, otherwise they will leak connections!
 *  New: APIs to create multipart request bodies (`MultipartBuilder`) and form
    encoding bodies (`FormEncodingBuilder`).

## Version 2.0.0-RC1

_2014-05-23_

OkHttp 2 is designed around a new API that is true to HTTP, with classes for
requests, responses, headers, and calls. It uses modern Java patterns like
immutability and chained builders. The API now offers asynchronous callbacks
in addition to synchronous blocking calls.

#### API Changes

 *  **New Request and Response types,** each with their own builder. There's also
    a `RequestBody` class to write the request body to the network and a
    `ResponseBody` to read the response body from the network. The standalone
    `Headers` class offers full access to the HTTP headers.

 *  **Okio dependency added.** OkHttp now depends on
    [Okio](https://github.com/square/okio), an I/O library that makes it easier
    to access, store and process data. Using this library internally makes OkHttp
    faster while consuming less memory. You can write a `RequestBody` as an Okio
    `BufferedSink` and a `ResponseBody` as an Okio `BufferedSource`. Standard
    `InputStream` and `OutputStream` access is also available.

 *  **New Call and Callback types** execute requests and receive their
    responses. Both types of calls can be canceled via the `Call` or the
    `OkHttpClient`.

 *  **URLConnection support has moved to the okhttp-urlconnection module.**
    If you're upgrading from 1.x, this change will impact you. You will need to
    add the `okhttp-urlconnection` module to your project and use the
    `OkUrlFactory` to create new instances of `HttpURLConnection`:

    ```
    // OkHttp 1.x:
    HttpURLConnection connection = client.open(url);

    // OkHttp 2.x:
    HttpURLConnection connection = new OkUrlFactory(client).open(url);
    ```

 *  **Custom caches are no longer supported.** In OkHttp 1.x it was possible to
    define your own response cache with the `java.net.ResponseCache` and OkHttp's
    `OkResponseCache` interfaces. Both of these APIs have been dropped. In
    OkHttp 2 the built-in disk cache is the only supported response cache.

 *  **HttpResponseCache has been renamed to Cache.** Install it with
    `OkHttpClient.setCache(...)` instead of `OkHttpClient.setResponseCache(...)`.

 *  **OkAuthenticator has been replaced with Authenticator.** This new
    authenticator has access to the full incoming response and can respond with
    whichever followup request is appropriate. The `Challenge` class is now a
    top-level class and `Credential` is replaced with a utility class called
    `Credentials`.

 *  **OkHttpClient.getFollowProtocolRedirects() renamed to
    getFollowSslRedirects()**. We reserve the word _protocol_ for the HTTP
    version being used (HTTP/1.1, HTTP/2). The old name of this method was
    misleading; it was always used to configure redirects between `https://` and
    `http://` schemes.

 *  **RouteDatabase is no longer public API.** OkHttp continues to track which
    routes have failed but this is no exposed in the API.

 *  **ResponseSource is gone.** This enum exposed whether a response came from
    the cache, network, or both. OkHttp 2 offers more detail with raw access to
    the cache and network responses in the new `Response` class.

 *  **TunnelRequest is gone.** It specified how to connect to an HTTP proxy.
    OkHttp 2 uses the new `Request` class for this.

 *  **Dispatcher** is a new class that manages the queue of asynchronous calls. It
    implements limits on total in-flight calls and in-flight calls per host.

#### Implementation changes

 * Support Android `TrafficStats` socket tagging.
 * Drop authentication headers on redirect.
 * Added support for compressed data frames.
 * Process push promise callbacks in order.
 * Update to http/2 draft 12.
 * Update to HPACK draft 07.
 * Add ALPN support. Maven will use ALPN on OpenJDK 8.
 * Update NPN dependency to target `jdk7u60-b13` and `Oracle jdk7u55-b13`.
 * Ensure SPDY variants support zero-length DELETE and POST.
 * Prevent leaking a cache item's InputStreams when metadata read fails.
 * Use a string to identify TLS versions in routes.
 * Add frame logger for HTTP/2.
 * Replacing `httpMinorVersion` with `Protocol`. Expose HTTP/1.0 as a potential protocol.
 * Use `Protocol` to describe framing.
 * Implement write timeouts for HTTP/1.1 streams.
 * Avoid use of SPDY stream ID 1, as that's typically used for UPGRADE.
 * Support OAuth in `Authenticator`.
 * Permit a dangling semicolon in media type parsing.

## Version 1.6.0

_2014-05-23_

 * Offer bridges to make it easier to migrate from OkHttp 1.x to OkHttp 2.0.
   This adds `OkUrlFactory`, `Cache`, and `@Deprecated` annotations for APIs
   dropped in 2.0.

## Version 1.5.4

_2014-04-14_

 * Drop ALPN support in Android. There's a concurrency bug in all
   currently-shipping versions.
 * Support asynchronous disconnects by breaking the socket only. This should
   prevent flakiness from multiple threads concurrently accessing a stream.

## Version 1.5.3

_2014-03-29_

 * Fix bug where the Content-Length header was not always dropped when
   following a redirect from a POST to a GET.
 * Implement basic support for `Thread.interrupt()`. OkHttp now checks
   for an interruption before doing a blocking call. If it is interrupted,
   it throws an `InterruptedIOException`.

## Version 1.5.2

_2014-03-17_

 * Fix bug where deleting a file that was absent from the `HttpResponseCache`
   caused an IOException.
 * Fix bug in HTTP/2 where our HPACK decoder wasn't emitting entries in
   certain eviction scenarios, leading to dropped response headers.

## Version 1.5.1

_2014-03-11_

 * Fix 1.5.0 regression where connections should not have been recycled.
 * Fix 1.5.0 regression where transparent Gzip was broken by attempting to
   recover from another I/O failure.
 * Fix problems where spdy/3.1 headers may not have been compressed properly.
 * Fix problems with spdy/3.1 and http/2 where the wrong window size was being
   used.
 * Fix 1.5.0 regression where conditional cache responses could corrupt the
   connection pool.


## Version 1.5.0

_2014-03-07_


##### OkHttp no longer uses the default SSL context.

Applications that want to use the global SSL context with OkHttp should configure their
OkHttpClient instances with the following:

```java
okHttpClient.setSslSocketFactory(HttpsURLConnection.getDefaultSSLSocketFactory());
```

A simpler solution is to avoid the shared default SSL socket factory. Instead, if you
need to customize SSL, do so for your specific OkHttpClient instance only.

##### Synthetic headers have changed

Previously OkHttp added a synthetic response header, `OkHttp-Selected-Transport`. It
has been replaced with a new synthetic header, `OkHttp-Selected-Protocol`.

##### Changes

 * New: Support for `HTTP-draft-09/2.0`.
 * New: Support for `spdy/3.1`. Dropped support for `spdy/3`.
 * New: Use ALPN on Android platforms that support it (4.4+)
 * New: CacheControl model and parser.
 * New: Protocol selection in MockWebServer.
 * Fix: Route selection shouldn't use TLS modes that we know will fail.
 * Fix: Cache SPDY responses even if the response body is closed prematurely.
 * Fix: Use strict timeouts when aborting a download.
 * Fix: Support Shoutcast HTTP responses like `ICY 200 OK`.
 * Fix: Don't unzip if there isn't a response body.
 * Fix: Don't leak gzip streams on redirects.
 * Fix: Don't do DNS lookups on invalid hosts.
 * Fix: Exhaust the underlying stream when reading gzip streams.
 * Fix: Support the `PATCH` method.
 * Fix: Support request bodies on `DELETE` method.
 * Fix: Drop the `okhttp-protocols` module.
 * Internal: Replaced internal byte array buffers with pooled buffers ("OkBuffer").


## Version 1.3.0

_2014-01-11_

 * New: Support for "PATCH" HTTP method in client and MockWebServer.
 * Fix: Drop `Content-Length` header when redirected from POST to GET.
 * Fix: Correctly read cached header entries with malformed header names.
 * Fix: Do not directly support any authentication schemes other than "Basic".
 * Fix: Respect read timeouts on recycled connections.
 * Fix: Transmit multiple cookie values as a single header with delimiter.
 * Fix: Ensure `null` is never returned from a connection's `getHeaderFields()`.
 * Fix: Persist proper `Content-Encoding` header to cache for GZip responses.
 * Fix: Eliminate rare race condition in SPDY streams that would prevent connection reuse.
 * Fix: Change HTTP date formats to UTC to conform to RFC2616 section 3.3.
 * Fix: Support SPDY header blocks with trailing bytes.
 * Fix: Allow `;` as separator for `Cache-Control` header.
 * Fix: Correct bug where HTTPS POST requests were always automatically buffered.
 * Fix: Honor read timeout when parsing SPDY headers.


## Version 1.2.1

_2013-08-23_

 * Resolve issue with 'jar-with-dependencies' artifact creation.
 * Fix: Support empty SPDY header values.


## Version 1.2.0

_2013-08-11_

 *  New APIs on OkHttpClient to set default timeouts for connect and read.
 *  Fix bug when caching SPDY responses.
 *  Fix a bug with SPDY plus half-closed streams. (thanks kwuollett)
 *  Fix a bug in `Content-Length` reporting for gzipped streams in the Apache
    HTTP client adapter. (thanks kwuollett)
 *  Work around the Alcatel `getByInetAddress` bug (thanks k.kocel)
 *  Be more aggressive about testing pooled sockets before reuse. (thanks
    warpspin)
 *  Include `Content-Type` and `Content-Encoding` in the Apache HTTP client
    adapter. (thanks kwuollett)
 *  Add a media type class to OkHttp.
 *  Change custom header prefix:

    ```
    X-Android-Sent-Millis is now OkHttp-Sent-Millis
    X-Android-Received-Millis is now OkHttp-Received-Millis
    X-Android-Response-Source is now OkHttp-Response-Source
    X-Android-Selected-Transport is now OkHttp-Selected-Transport
    ```
 *  Improve cache invalidation for POST-like requests.
 *  Bring MockWebServer into OkHttp and teach it SPDY.


## Version 1.1.1

_2013-06-23_

 * Fix: ClassCastException when caching responses that were redirected from
   HTTP to HTTPS.


## Version 1.1.0

_2013-06-15_

 * Fix: Connection reuse was broken for most HTTPS connections due to a bug in
   the way the hostname verifier was selected.
 * Fix: Locking bug in SpdyConnection.
 * Fix: Ignore null header values (for compatibility with HttpURLConnection).
 * Add URLStreamHandlerFactory support so that `URL.openConnection()` uses
   OkHttp.
 * Expose the transport ("http/1.1", "spdy/3", etc.) via magic request headers.
   Use `X-Android-Transports` to write the preferred transports and
   `X-Android-Selected-Transport` to read the negotiated transport.


## Version 1.0.2

_2013-05-11_

 * Fix: Remove use of Java 6-only APIs.
 * Fix: Properly handle exceptions from `NetworkInterface` when querying MTU.
 * Fix: Ensure MTU has a reasonable default and upper-bound.


## Version 1.0.1

_2013-05-06_

 * Correct casing of SSL in method names (`getSslSocketFactory`/`setSslSocketFactory`).


## Version 1.0.0

_2013-05-06_

Initial release.

 [brick]: (https://noncombatant.org/2015/05/01/about-http-public-key-pinning/)
