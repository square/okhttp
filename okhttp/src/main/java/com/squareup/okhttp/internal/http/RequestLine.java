package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Request;
import java.net.Proxy;
import java.net.URL;

public final class RequestLine {
  private RequestLine() {
  }

  /**
   * Returns the request status line, like "GET / HTTP/1.1". This is exposed
   * to the application by {@link HttpURLConnectionImpl#getHeaderFields}, so
   * it needs to be set even if the transport is SPDY.
   */
  static String get(Request request, Proxy.Type proxyType, int httpMinorVersion) {
    StringBuilder result = new StringBuilder();
    result.append(request.method());
    result.append(" ");

    if (includeAuthorityInRequestLine(request, proxyType)) {
      result.append(request.url());
    } else {
      result.append(requestPath(request.url()));
    }

    result.append(" ");
    result.append(version(httpMinorVersion));
    return result.toString();
  }

  /**
   * Returns true if the request line should contain the full URL with host
   * and port (like "GET http://android.com/foo HTTP/1.1") or only the path
   * (like "GET /foo HTTP/1.1").
   */
  private static boolean includeAuthorityInRequestLine(Request request, Proxy.Type proxyType) {
    return !request.isHttps() && proxyType == Proxy.Type.HTTP;
  }

  /**
   * Returns the path to request, like the '/' in 'GET / HTTP/1.1'. Never empty,
   * even if the request URL is. Includes the query component if it exists.
   */
  public static String requestPath(URL url) {
    String pathAndQuery = url.getFile();
    if (pathAndQuery == null) return "/";
    if (!pathAndQuery.startsWith("/")) return "/" + pathAndQuery;
    return pathAndQuery;
  }

  public static String version(int httpMinorVersion) {
    return httpMinorVersion == 1 ? "HTTP/1.1" : "HTTP/1.0";
  }
}
