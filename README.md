OkHttp
======

An HTTP & HTTP/2 client for Android and Java applications. For more information see [the
website][website] and [the wiki][wiki].


Requirements
------------

OkHttp works on Android 5.0+ (API level 21+) and on Java 8+.

OkHttp has one library dependency on [Okio][okio], a small library for high-performance I/O. It
works with either Okio 1.x (implemented in Java) or Okio 2.x (upgraded to Kotlin).

We highly recommend you keep OkHttp up-to-date. As with auto-updating web browsers, staying current
with HTTPS clients is an important defense against potential security problems. [We
track][tls_history] the dynamic TLS ecosystem and adjust OkHttp to improve connectivity and
security.

OkHttp uses your platform's built-in TLS implementation. On Java platforms OkHttp also supports
[Conscrypt][conscrypt], which integrates BoringSSL with Java. OkHttp will use Conscrypt if it is
the first security provider:

```java
Security.insertProviderAt(Conscrypt.newProvider(), 1);
```

The OkHttp 3.12.x branch supports Android 2.3+ (API level 9+) and Java 7+. These platforms lack
support for TLS 1.2 and should not be used. But because upgrading is difficult we will backport
critical fixes to the [3.12.x branch][okhttp_312x] through December 31, 2020.

Download
--------

Download [the latest JAR][okhttp_latest_jar] or configure this dependency:

```kotlin
implementation("com.squareup.okhttp3:okhttp:3.12.1")
```

Snapshots of the development version are available in [Sonatype's `snapshots` repository][snap].


MockWebServer
-------------

A library for testing HTTP, HTTPS, and HTTP/2 clients.

MockWebServer coupling with OkHttp is essential for proper testing of HTTP/2 so that code can be shared.

### Download

Download [the latest JAR][mockwebserver_latest_jar] or configure this dependency:
```xml
testImplementation("com.squareup.okhttp3:mockwebserver:3.12.1")
```

R8 / ProGuard
-------------

If you are using R8 or ProGuard add the options from [`okhttp3.pro`][okhttp3_pro].

You might also need rules for Okio which is a dependency of this library.


License
-------

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.


 [conscrypt]: https://github.com/google/conscrypt/
 [mockwebserver_latest_jar]: https://search.maven.org/remote_content?g=com.squareup.okhttp3&a=mockwebserver&v=LATEST
 [okhttp_312x]: https://github.com/square/okhttp/tree/okhttp_3.12.x
 [okhttp_latest_jar]: https://search.maven.org/remote_content?g=com.squareup.okhttp3&a=okhttp&v=LATEST
 [okio]: https://github.com/square/okio/
 [snap]: https://oss.sonatype.org/content/repositories/snapshots/
 [tls_history]: https://github.com/square/okhttp/wiki/TLS-Configuration-History
 [website]: https://square.github.io/okhttp
 [wiki]: https://github.com/square/okhttp/wiki
 [okhttp3_pro]: https://github.com/square/okhttp/blob/master/okhttp/src/main/resources/META-INF/proguard/okhttp3.pro
