Connections
===========

Although you provide only the URL, OkHttp plans its connection to your webserver using three types: URL, Address, and Route.

### [URLs](http://square.github.io/okhttp/4.x/okhttp/okhttp3/-http-url/)

URLs (like `https://github.com/square/okhttp`) are fundamental to HTTP and the Internet. In addition to being a universal, decentralized naming scheme for everything on the web, they also specify how to access web resources.

URLs are abstract:

 * They specify that the call may be plaintext (`http`) or encrypted (`https`), but not which cryptographic algorithms should be used. Nor do they specify how to verify the peer's certificates (the [HostnameVerifier](http://developer.android.com/reference/javax/net/ssl/HostnameVerifier.html)) or which certificates can be trusted (the [SSLSocketFactory](http://developer.android.com/reference/org/apache/http/conn/ssl/SSLSocketFactory.html)).
 * They don't specify whether a specific proxy server should be used or how to authenticate with that proxy server.

They're also concrete: each URL identifies a specific path (like `/square/okhttp`) and query (like `?q=sharks&lang=en`). Each webserver hosts many URLs.

### [Addresses](http://square.github.io/okhttp/4.x/okhttp/okhttp3/-address/)

Addresses specify a webserver (like `github.com`) and all of the **static** configuration necessary to connect to that server: the port number, HTTPS settings, and preferred network protocols (like HTTP/2 or SPDY).

URLs that share the same address may also share the same underlying TCP socket connection. Sharing a connection has substantial performance benefits: lower latency, higher throughput (due to [TCP slow start](http://www.igvita.com/2011/10/20/faster-web-vs-tcp-slow-start/)) and conserved battery. OkHttp uses a [ConnectionPool](http://square.github.io/okhttp/4.x/okhttp/okhttp3/-connection-pool/) that automatically reuses HTTP/1.x connections and multiplexes HTTP/2 and SPDY connections.

In OkHttp some fields of the address come from the URL (scheme, hostname, port) and the rest come from the [OkHttpClient](http://square.github.io/okhttp/4.x/okhttp/okhttp3/-ok-http-client/).

### [Routes](http://square.github.io/okhttp/4.x/okhttp/okhttp3/-route/)

Routes supply the **dynamic** information necessary to actually connect to a webserver. This is the specific IP address to attempt (as discovered by a DNS query), the exact proxy server to use (if a [ProxySelector](http://developer.android.com/reference/java/net/ProxySelector.html) is in use), and which version of TLS to negotiate (for HTTPS connections).

There may be many routes for a single address. For example, a webserver that is hosted in multiple datacenters may yield multiple IP addresses in its DNS response.

### [Connections](http://square.github.io/okhttp/4.x/okhttp/okhttp3/-connection/)

When you request a URL with OkHttp, here's what it does:

 1. It uses the URL and configured OkHttpClient to create an **address**. This address specifies how we'll connect to the webserver.
 2. It attempts to retrieve a connection with that address from the **connection pool**.
 3. If it doesn't find a connection in the pool, it selects a **route** to attempt. This usually means making a DNS request to get the server's IP addresses. It then selects a TLS version and proxy server if necessary.
 4. If it's a new route, it connects by building either a direct socket connection, a TLS tunnel (for HTTPS over an HTTP proxy), or a direct TLS connection. It does TLS handshakes as necessary.
 5. It sends the HTTP request and reads the response.

If there's a problem with the connection, OkHttp will select another route and try again. This allows OkHttp to recover when a subset of a server's addresses are unreachable. It's also useful when a pooled connection is stale or if the attempted TLS version is unsupported.

Once the response has been received, the connection will be returned to the pool so it can be reused for a future request. Connections are evicted from the pool after a period of inactivity.
