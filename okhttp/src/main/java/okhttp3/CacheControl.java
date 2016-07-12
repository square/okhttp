package okhttp3;

import java.util.concurrent.TimeUnit;
import okhttp3.internal.http.HttpHeaders;

/**
 * A Cache-Control header with cache directives from a server or client. These directives set policy
 * on what responses can be stored, and which requests can be satisfied by those stored responses.
 *
 * <p>See <a href="http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.9">RFC 2616,
 * 14.9</a>.
 */
public final class CacheControl {
  /**
   * Cache control request directives that require network validation of responses. Note that such
   * requests may be assisted by the cache via conditional GET requests.
   */
  public static final CacheControl FORCE_NETWORK = new Builder().noCache().build();

  /**
   * Cache control request directives that uses the cache only, even if the cached response is
   * stale. If the response isn't available in the cache or requires server validation, the call
   * will fail with a {@code 504 Unsatisfiable Request}.
   */
  public static final CacheControl FORCE_CACHE = new Builder()
      .onlyIfCached()
      .maxStale(Integer.MAX_VALUE, TimeUnit.SECONDS)
      .build();

  private final boolean noCache;
  private final boolean noStore;
  private final int maxAgeSeconds;
  private final int sMaxAgeSeconds;
  private final boolean isPrivate;
  private final boolean isPublic;
  private final boolean mustRevalidate;
  private final int maxStaleSeconds;
  private final int minFreshSeconds;
  private final boolean onlyIfCached;
  private final boolean noTransform;

  String headerValue; // Lazily computed, null if absent.

  private CacheControl(boolean noCache, boolean noStore, int maxAgeSeconds, int sMaxAgeSeconds,
      boolean isPrivate, boolean isPublic, boolean mustRevalidate, int maxStaleSeconds,
      int minFreshSeconds, boolean onlyIfCached, boolean noTransform, String headerValue) {
    this.noCache = noCache;
    this.noStore = noStore;
    this.maxAgeSeconds = maxAgeSeconds;
    this.sMaxAgeSeconds = sMaxAgeSeconds;
    this.isPrivate = isPrivate;
    this.isPublic = isPublic;
    this.mustRevalidate = mustRevalidate;
    this.maxStaleSeconds = maxStaleSeconds;
    this.minFreshSeconds = minFreshSeconds;
    this.onlyIfCached = onlyIfCached;
    this.noTransform = noTransform;
    this.headerValue = headerValue;
  }

  private CacheControl(Builder builder) {
    this.noCache = builder.noCache;
    this.noStore = builder.noStore;
    this.maxAgeSeconds = builder.maxAgeSeconds;
    this.sMaxAgeSeconds = -1;
    this.isPrivate = false;
    this.isPublic = false;
    this.mustRevalidate = false;
    this.maxStaleSeconds = builder.maxStaleSeconds;
    this.minFreshSeconds = builder.minFreshSeconds;
    this.onlyIfCached = builder.onlyIfCached;
    this.noTransform = builder.noTransform;
  }

  /**
   * In a response, this field's name "no-cache" is misleading. It doesn't prevent us from caching
   * the response; it only means we have to validate the response with the origin server before
   * returning it. We can do this with a conditional GET.
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
   * The duration past the response's served date that it can be served without validation.
   */
  public int maxAgeSeconds() {
    return maxAgeSeconds;
  }

  /**
   * The "s-maxage" directive is the max age for shared caches. Not to be confused with "max-age"
   * for non-shared caches, As in Firefox and Chrome, this directive is not honored by this cache.
   */
  public int sMaxAgeSeconds() {
    return sMaxAgeSeconds;
  }

  public boolean isPrivate() {
    return isPrivate;
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
   * This field's name "only-if-cached" is misleading. It actually means "do not use the network".
   * It is set by a client who only wants to make a request if it can be fully satisfied by the
   * cache. Cached responses that would require validation (ie. conditional gets) are not permitted
   * if this header is set.
   */
  public boolean onlyIfCached() {
    return onlyIfCached;
  }

  public boolean noTransform() {
    return noTransform;
  }

  /**
   * Returns the cache directives of {@code headers}. This honors both Cache-Control and Pragma
   * headers if they are present.
   */
  public static CacheControl parse(Headers headers) {
    boolean noCache = false;
    boolean noStore = false;
    int maxAgeSeconds = -1;
    int sMaxAgeSeconds = -1;
    boolean isPrivate = false;
    boolean isPublic = false;
    boolean mustRevalidate = false;
    int maxStaleSeconds = -1;
    int minFreshSeconds = -1;
    boolean onlyIfCached = false;
    boolean noTransform = false;

    boolean canUseHeaderValue = true;
    String headerValue = null;

    for (int i = 0, size = headers.size(); i < size; i++) {
      String name = headers.name(i);
      String value = headers.value(i);

      if (name.equalsIgnoreCase("Cache-Control")) {
        if (headerValue != null) {
          // Multiple cache-control headers means we can't use the raw value.
          canUseHeaderValue = false;
        } else {
          headerValue = value;
        }
      } else if (name.equalsIgnoreCase("Pragma")) {
        // Might specify additional cache-control params. We invalidate just in case.
        canUseHeaderValue = false;
      } else {
        continue;
      }

      int pos = 0;
      while (pos < value.length()) {
        int tokenStart = pos;
        pos = HttpHeaders.skipUntil(value, pos, "=,;");
        String directive = value.substring(tokenStart, pos).trim();
        String parameter;

        if (pos == value.length() || value.charAt(pos) == ',' || value.charAt(pos) == ';') {
          pos++; // consume ',' or ';' (if necessary)
          parameter = null;
        } else {
          pos++; // consume '='
          pos = HttpHeaders.skipWhitespace(value, pos);

          // quoted string
          if (pos < value.length() && value.charAt(pos) == '\"') {
            pos++; // consume '"' open quote
            int parameterStart = pos;
            pos = HttpHeaders.skipUntil(value, pos, "\"");
            parameter = value.substring(parameterStart, pos);
            pos++; // consume '"' close quote (if necessary)

            // unquoted string
          } else {
            int parameterStart = pos;
            pos = HttpHeaders.skipUntil(value, pos, ",;");
            parameter = value.substring(parameterStart, pos).trim();
          }
        }

        if ("no-cache".equalsIgnoreCase(directive)) {
          noCache = true;
        } else if ("no-store".equalsIgnoreCase(directive)) {
          noStore = true;
        } else if ("max-age".equalsIgnoreCase(directive)) {
          maxAgeSeconds = HttpHeaders.parseSeconds(parameter, -1);
        } else if ("s-maxage".equalsIgnoreCase(directive)) {
          sMaxAgeSeconds = HttpHeaders.parseSeconds(parameter, -1);
        } else if ("private".equalsIgnoreCase(directive)) {
          isPrivate = true;
        } else if ("public".equalsIgnoreCase(directive)) {
          isPublic = true;
        } else if ("must-revalidate".equalsIgnoreCase(directive)) {
          mustRevalidate = true;
        } else if ("max-stale".equalsIgnoreCase(directive)) {
          maxStaleSeconds = HttpHeaders.parseSeconds(parameter, Integer.MAX_VALUE);
        } else if ("min-fresh".equalsIgnoreCase(directive)) {
          minFreshSeconds = HttpHeaders.parseSeconds(parameter, -1);
        } else if ("only-if-cached".equalsIgnoreCase(directive)) {
          onlyIfCached = true;
        } else if ("no-transform".equalsIgnoreCase(directive)) {
          noTransform = true;
        }
      }
    }

    if (!canUseHeaderValue) {
      headerValue = null;
    }
    return new CacheControl(noCache, noStore, maxAgeSeconds, sMaxAgeSeconds, isPrivate, isPublic,
        mustRevalidate, maxStaleSeconds, minFreshSeconds, onlyIfCached, noTransform, headerValue);
  }

  @Override public String toString() {
    String result = headerValue;
    return result != null ? result : (headerValue = headerValue());
  }

  private String headerValue() {
    StringBuilder result = new StringBuilder();
    if (noCache) result.append("no-cache, ");
    if (noStore) result.append("no-store, ");
    if (maxAgeSeconds != -1) result.append("max-age=").append(maxAgeSeconds).append(", ");
    if (sMaxAgeSeconds != -1) result.append("s-maxage=").append(sMaxAgeSeconds).append(", ");
    if (isPrivate) result.append("private, ");
    if (isPublic) result.append("public, ");
    if (mustRevalidate) result.append("must-revalidate, ");
    if (maxStaleSeconds != -1) result.append("max-stale=").append(maxStaleSeconds).append(", ");
    if (minFreshSeconds != -1) result.append("min-fresh=").append(minFreshSeconds).append(", ");
    if (onlyIfCached) result.append("only-if-cached, ");
    if (noTransform) result.append("no-transform, ");
    if (result.length() == 0) return "";
    result.delete(result.length() - 2, result.length());
    return result.toString();
  }

  /** Builds a {@code Cache-Control} request header. */
  public static final class Builder {
    boolean noCache;
    boolean noStore;
    int maxAgeSeconds = -1;
    int maxStaleSeconds = -1;
    int minFreshSeconds = -1;
    boolean onlyIfCached;
    boolean noTransform;

    /** Don't accept an unvalidated cached response. */
    public Builder noCache() {
      this.noCache = true;
      return this;
    }

    /** Don't store the server's response in any cache. */
    public Builder noStore() {
      this.noStore = true;
      return this;
    }

    /**
     * Sets the maximum age of a cached response. If the cache response's age exceeds {@code
     * maxAge}, it will not be used and a network request will be made.
     *
     * @param maxAge a non-negative integer. This is stored and transmitted with {@link
     * TimeUnit#SECONDS} precision; finer precision will be lost.
     */
    public Builder maxAge(int maxAge, TimeUnit timeUnit) {
      if (maxAge < 0) throw new IllegalArgumentException("maxAge < 0: " + maxAge);
      long maxAgeSecondsLong = timeUnit.toSeconds(maxAge);
      this.maxAgeSeconds = maxAgeSecondsLong > Integer.MAX_VALUE
          ? Integer.MAX_VALUE
          : (int) maxAgeSecondsLong;
      return this;
    }

    /**
     * Accept cached responses that have exceeded their freshness lifetime by up to {@code
     * maxStale}. If unspecified, stale cache responses will not be used.
     *
     * @param maxStale a non-negative integer. This is stored and transmitted with {@link
     * TimeUnit#SECONDS} precision; finer precision will be lost.
     */
    public Builder maxStale(int maxStale, TimeUnit timeUnit) {
      if (maxStale < 0) throw new IllegalArgumentException("maxStale < 0: " + maxStale);
      long maxStaleSecondsLong = timeUnit.toSeconds(maxStale);
      this.maxStaleSeconds = maxStaleSecondsLong > Integer.MAX_VALUE
          ? Integer.MAX_VALUE
          : (int) maxStaleSecondsLong;
      return this;
    }

    /**
     * Sets the minimum number of seconds that a response will continue to be fresh for. If the
     * response will be stale when {@code minFresh} have elapsed, the cached response will not be
     * used and a network request will be made.
     *
     * @param minFresh a non-negative integer. This is stored and transmitted with {@link
     * TimeUnit#SECONDS} precision; finer precision will be lost.
     */
    public Builder minFresh(int minFresh, TimeUnit timeUnit) {
      if (minFresh < 0) throw new IllegalArgumentException("minFresh < 0: " + minFresh);
      long minFreshSecondsLong = timeUnit.toSeconds(minFresh);
      this.minFreshSeconds = minFreshSecondsLong > Integer.MAX_VALUE
          ? Integer.MAX_VALUE
          : (int) minFreshSecondsLong;
      return this;
    }

    /**
     * Only accept the response if it is in the cache. If the response isn't cached, a {@code 504
     * Unsatisfiable Request} response will be returned.
     */
    public Builder onlyIfCached() {
      this.onlyIfCached = true;
      return this;
    }

    /** Don't accept a transformed response. */
    public Builder noTransform() {
      this.noTransform = true;
      return this;
    }

    public CacheControl build() {
      return new CacheControl(this);
    }
  }
}
