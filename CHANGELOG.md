Change Log
==========

Version 1.2.1 *(2013-08-23)*
----------------------------

 * Resolve issue with 'jar-with-dependencies' artifact creation.


Version 1.2.0 *(2013-08-11)*
----------------------------

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


Version 1.1.1 *(2013-06-23)*
----------------------------

 * Fix: ClassCastException when caching responses that were redirected from
   HTTP to HTTPS.


Version 1.1.0 *(2013-06-15)*
----------------------------

 * Fix: Connection reuse was broken for most HTTPS connections due to a bug in
   the way the hostname verifier was selected.
 * Fix: Locking bug in SpdyConnection.
 * Fix: Ignore null header values (for compatibility with HttpURLConnection).
 * Add URLStreamHandlerFactory support so that `URL.openConnection()` uses
   OkHttp.
 * Expose the transport ("http/1.1", "spdy/3", etc.) via magic request headers.
   Use `X-Android-Transports` to write the preferred transports and
   `X-Android-Selected-Transport` to read the negotiated transport.

Version 1.0.2 *(2013-05-11)*
----------------------------

 * Fix: Remove use of Java 6-only APIs.
 * Fix: Properly handle exceptions from `NetworkInterface` when querying MTU.
 * Fix: Ensure MTU has a reasonable default and upper-bound.


Version 1.0.1 *(2013-05-06)*
----------------------------

 * Correct casing of SSL in method names (`getSslSocketFactory`/`setSslSocketFactory`).


Version 1.0.0 *(2013-05-06)*
----------------------------

Initial release.

