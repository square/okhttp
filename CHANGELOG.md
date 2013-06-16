Change Log
==========

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

