package com.squareup.okhttp;

import com.squareup.okhttp.internal.http.HeaderParser;
import com.squareup.okhttp.internal.http.Headers;

/**
 * A Cache-Control header with cache directives from a server or client. These
 * directives set policy on what responses can be stored, and which requests can
 * be satisfied by those stored responses.
 *
 * <p>See <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9">RFC
 * 2616, 14.9</a>.
 */
public final class CacheControl {
  private final boolean noCache;
  private final boolean noStore;
  private final int maxAgeSeconds;
  private final int sMaxAgeSeconds;
  private final boolean isPublic;
  private final boolean mustRevalidate;
  private final int maxStaleSeconds;
  private final int minFreshSeconds;
  private final boolean onlyIfCached;

  private CacheControl(boolean noCache, boolean noStore, int maxAgeSeconds, int sMaxAgeSeconds,
      boolean isPublic, boolean mustRevalidate, int maxStaleSeconds, int minFreshSeconds,
      boolean onlyIfCached) {
    this.noCache = noCache;
    this.noStore = noStore;
    this.maxAgeSeconds = maxAgeSeconds;
    this.sMaxAgeSeconds = sMaxAgeSeconds;
    this.isPublic = isPublic;
    this.mustRevalidate = mustRevalidate;
    this.maxStaleSeconds = maxStaleSeconds;
    this.minFreshSeconds = minFreshSeconds;
    this.onlyIfCached = onlyIfCached;
  }

  /**
   * In a response, this field's name "no-cache" is misleading. It doesn't
   * prevent us from caching the response; it only means we have to validate the
   * response with the origin server before returning it. We can do this with a
   * conditional GET.
   *
   * <p>In a request, it means do not use a cache to satisfy the request.
   */
  public boolean noCache() {
    return noCache;
  }

  /** If true, this response should not be cached. */
  public boolean noStore() {
    return noStore;
  }

  /**
   * The duration past the response's served date that it can be served without
   * validation.
   */
  public int maxAgeSeconds() {
    return maxAgeSeconds;
  }

  /**
   * The "s-maxage" directive is the max age for shared caches. Not to be
   * confused with "max-age" for non-shared caches, As in Firefox and Chrome,
   * this directive is not honored by this cache.
   */
  public int sMaxAgeSeconds() {
    return sMaxAgeSeconds;
  }

  public boolean isPublic() {
    return isPublic;
  }

  public boolean mustRevalidate() {
    return mustRevalidate;
  }

  public int maxStaleSeconds() {
    return maxStaleSeconds;
  }

  public int minFreshSeconds() {
    return minFreshSeconds;
  }

  /**
   * This field's name "only-if-cached" is misleading. It actually means "do
   * not use the network". It is set by a client who only wants to make a
   * request if it can be fully satisfied by the cache. Cached responses that
   * would require validation (ie. conditional gets) are not permitted if this
   * header is set.
   */
  public boolean onlyIfCached() {
    return onlyIfCached;
  }

  /**
   * Returns the cache directives of {@code headers}. This honors both
   * Cache-Control and Pragma headers if they are present.
   */
  public static CacheControl parse(Headers headers) {
    boolean noCache = false;
    boolean noStore = false;
    int maxAgeSeconds = -1;
    int sMaxAgeSeconds = -1;
    boolean isPublic = false;
    boolean mustRevalidate = false;
    int maxStaleSeconds = -1;
    int minFreshSeconds = -1;
    boolean onlyIfCached = false;

    for (int i = 0; i < headers.size(); i++) {
      if (!headers.name(i).equalsIgnoreCase("Cache-Control")
          && !headers.name(i).equalsIgnoreCase("Pragma")) {
        continue;
      }

      String string = headers.value(i);
      int pos = 0;
      while (pos < string.length()) {
        int tokenStart = pos;
        pos = HeaderParser.skipUntil(string, pos, "=,;");
        String directive = string.substring(tokenStart, pos).trim();
        String parameter;

        if (pos == string.length() || string.charAt(pos) == ',' || string.charAt(pos) == ';') {
          pos++; // consume ',' or ';' (if necessary)
          parameter = null;
        } else {
          pos++; // consume '='
          pos = HeaderParser.skipWhitespace(string, pos);

          // quoted string
          if (pos < string.length() && string.charAt(pos) == '\"') {
            pos++; // consume '"' open quote
            int parameterStart = pos;
            pos = HeaderParser.skipUntil(string, pos, "\"");
            parameter = string.substring(parameterStart, pos);
            pos++; // consume '"' close quote (if necessary)

            // unquoted string
          } else {
            int parameterStart = pos;
            pos = HeaderParser.skipUntil(string, pos, ",;");
            parameter = string.substring(parameterStart, pos).trim();
          }
        }

        if ("no-cache".equalsIgnoreCase(directive)) {
          noCache = true;
        } else if ("no-store".equalsIgnoreCase(directive)) {
          noStore = true;
        } else if ("max-age".equalsIgnoreCase(directive)) {
          maxAgeSeconds = HeaderParser.parseSeconds(parameter);
        } else if ("s-maxage".equalsIgnoreCase(directive)) {
          sMaxAgeSeconds = HeaderParser.parseSeconds(parameter);
        } else if ("public".equalsIgnoreCase(directive)) {
          isPublic = true;
        } else if ("must-revalidate".equalsIgnoreCase(directive)) {
          mustRevalidate = true;
        } else if ("max-stale".equalsIgnoreCase(directive)) {
          maxStaleSeconds = HeaderParser.parseSeconds(parameter);
        } else if ("min-fresh".equalsIgnoreCase(directive)) {
          minFreshSeconds = HeaderParser.parseSeconds(parameter);
        } else if ("only-if-cached".equalsIgnoreCase(directive)) {
          onlyIfCached = true;
        }
      }
    }

    return new CacheControl(noCache, noStore, maxAgeSeconds, sMaxAgeSeconds, isPublic,
        mustRevalidate, maxStaleSeconds, minFreshSeconds, onlyIfCached);
  }
}
