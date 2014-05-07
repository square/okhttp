OkHttp
======

An HTTP & SPDY client for Android and Java applications.

For more information please see [the website][1].

Making Connections
------------------

Although you provide only the URL, OkHttp plans its connection to your webserver
using three types: URL, Address, and Route.

#### [URLs](http://developer.android.com/reference/java/net/URL.html)

URLs (like `https://github.com/square/okhttp`) are fundamental to HTTP and the
Internet. In addition to being a universal, decentralized naming scheme for
everything on the web, they also specify how to access web resources.

URLs are abstract:

 * They specify that the call may be plaintext (`http`) or encrypted (`https`),
   but not which cryptographic algorithms should be used. Nor do they specify
   how to verify the peer's certificates (the [HostnameVerifier](http://developer.android.com/reference/javax/net/ssl/HostnameVerifier.html))
   or which certificates can be trusted (the [SSLSocketFactory](http://developer.android.com/reference/org/apache/http/conn/ssl/SSLSocketFactory.html)).
 * They don't specify whether a specific proxy server should be used or how to
   authenticate with that proxy server.

They're also concrete: each URL identifies a specific path (like `/square/okhttp`)
and query (like `?q=sharks&lang=en`). Each webserver hosts many URLs.

#### [Addresses](http://square.github.io/okhttp/javadoc/com/squareup/okhttp/Address.html)

Addresses specify a webserver (like `github.com`) and all of the **static**
configuration necessary to connect to that server: the port number, HTTPS
settings, and preferred network protocols (like HTTP/2 or SPDY).

URLs that share the same address may also share the same underlying TCP socket
connection. Sharing a connection has substantial performance benefits: lower
latency, higher throughput (due to [TCP slow start](http://www.igvita.com/2011/10/20/faster-web-vs-tcp-slow-start/))
and conserved battery. OkHttp uses a [ConnectionPool](http://square.github.io/okhttp/javadoc/com/squareup/okhttp/ConnectionPool.html)
that automatically reuses HTTP/1.x connections and multiplexes HTTP/2 and SPDY
connections.

In OkHttp some fields of the address come from the URL (scheme, hostname, port)
and the rest come from the [OkHttpClient](http://square.github.io/okhttp/javadoc/com/squareup/okhttp/OkHttpClient.html).

#### [Routes](http://square.github.io/okhttp/javadoc/com/squareup/okhttp/Route.html)

Routes supply the **dynamic** information necessary to actually connect to a webserver.
This is the specific IP address to attempt (as discovered by a DNS query), the
exact proxy server to use (if a [ProxySelector](http://developer.android.com/reference/java/net/ProxySelector.html)
is in use), and which version of TLS to negotiate (for HTTPS connections).

There may be many routes for a single address. For example, a webserver that
is hosted in multiple datacenters may yield multiple IP addresses in its DNS
response.

#### [Connections](http://square.github.io/okhttp/javadoc/com/squareup/okhttp/Connection.html)

When you request a URL with OkHttp, here's what it does:

 1. Use the URL and configured OkHttpClient to create an **address**. This address
    specifies how we'll connect to the webserver.
 2. Attempt to retrieve a connection with that address in the **connection pool**.
 3. If it didn't find a connection in the pool, select a **route** to attempt.
    This usually means making a DNS request to get the server's IP addresses.
    Select a TLS version and proxy server if necessary.
 4. If it's a new route, connect. Build either a direct socket connection, a TLS
    tunnel (for HTTPS over an HTTP proxy), or a direct TLS connection. Do TLS
    handshakes as necessary.
 5. Send the HTTP request and read its HTTP response.

If there's a problem with the connection, OkHttp will select another route and
try again. This can be used to automatically fail over on webservers that offer
multiple IP addresses. It's also useful when a pooled connection is stale or if
the attempted TLS version is unsupported.

Once the response has been received, the connection will be returned to the pool
so it can be reused for a future request. Connections are evicted from the pool
after a period of inactivity.

Download
--------

Download [the latest JAR][2] or grab via Maven:

```xml
<dependency>
    <groupId>com.squareup.okhttp</groupId>
    <artifactId>okhttp</artifactId>
    <version>(insert latest version)</version>
</dependency>
```


Building
--------

OkHttp requires Java 7 to build and run tests. Runtime compatibility with Java 6 is enforced as
part of the build to ensure compliance with Android and older versions of the JVM.



Testing
-------

### On the Desktop

Run OkHttp tests on the desktop with Maven. Running HTTP/2 and SPDY tests on the desktop uses
[Jetty-NPN][3] when running OpenJDK 7 or [Jetty-ALPN][4] when OpenJDK 8.

```
mvn clean test
```

### On a Device

OkHttp's test suite creates an in-process HTTPS server. Prior to Android 2.3, SSL server sockets
were broken, and so HTTPS tests will time out when run on such devices.

Test on a USB-attached Android using [Vogar][5]. Unfortunately `dx` requires that you build with
Java 6, otherwise the test class will be silently omitted from the `.dex` file.

```
mvn clean
mvn package -DskipTests
vogar \
    --classpath ~/.m2/repository/org/bouncycastle/bcprov-jdk15on/1.48/bcprov-jdk15on-1.48.jar \
    --classpath mockwebserver/target/mockwebserver-2.0.0-SNAPSHOT.jar \
    --classpath okhttp-protocols/target/okhttp-protocols-2.0.0-SNAPSHOT.jar \
    --classpath okhttp/target/okhttp-2.0.0-SNAPSHOT.jar \
    okhttp/src/test
```

MockWebServer
-------------

A library for testing HTTP, HTTPS, HTTP/2.0, and SPDY clients.

MockWebServer coupling with OkHttp is essential for proper testing of SPDY and HTTP/2.0 so that code can be shared.

### Download

Download [the latest JAR][6] or grab via Maven:

```xml
<dependency>
    <groupId>com.squareup.okhttp</groupId>
    <artifactId>mockwebserver</artifactId>
    <version>(insert latest version)</version>
    <scope>test</scope>
</dependency>
```


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




 [1]: http://square.github.io/okhttp
 [2]: http://repository.sonatype.org/service/local/artifact/maven/redirect?r=central-proxy&g=com.squareup.okhttp&a=okhttp&v=LATEST
 [3]: https://github.com/jetty-project/jetty-npn
 [4]: https://github.com/jetty-project/jetty-alpn
 [5]: https://code.google.com/p/vogar/
 [6]: http://repository.sonatype.org/service/local/artifact/maven/redirect?r=central-proxy&g=com.squareup.okhttp&a=mockwebserver&v=LATEST
