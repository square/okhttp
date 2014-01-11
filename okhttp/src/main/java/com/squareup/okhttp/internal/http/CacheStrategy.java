package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.CacheControl;
import com.squareup.okhttp.MediaType;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;

import static com.squareup.okhttp.internal.Util.EMPTY_INPUT_STREAM;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Given a request and cached response, this figures out whether to use the
 * network, the cache, or both.
 *
 * <p>Selecting the next action may have side effects. The request may gain
 * conditions such as an "If-None-Match" or "If-Modified-Since" header. The
 * response may gain a warning if it is potentially stale.
 */
public final class CacheStrategy {
  private static final Response.Body EMPTY_BODY = new Response.Body() {
    @Override public boolean ready() throws IOException {
      return true;
    }
    @Override public MediaType contentType() {
      return null;
    }
    @Override public long contentLength() {
      return 0;
    }
    @Override public InputStream byteStream() {
      return EMPTY_INPUT_STREAM;
    }
  };

  private static final StatusLine GATEWAY_TIMEOUT_STATUS_LINE;
  static {
    try {
      GATEWAY_TIMEOUT_STATUS_LINE = new StatusLine("HTTP/1.1 504 Gateway Timeout");
    } catch (IOException e) {
      throw new AssertionError();
    }
  }

  public final Request request;
  public final Response response;
  public final ResponseSource source;

  private CacheStrategy(
      Request request, Response response, ResponseSource source) {
    this.request = request;
    this.response = response;
    this.source = source;
  }

  /**
   * Returns the current age of the response, in milliseconds. The calculation
   * is specified by RFC 2616, 13.2.3 Age Calculations.
   */
  private static long computeAge(Response response, long nowMillis) {
    long apparentReceivedAge = response.getServedDate() != null
        ? Math.max(0, response.getReceivedResponseMillis() - response.getServedDate().getTime())
        : 0;
    long receivedAge = response.getAgeSeconds() != -1
        ? Math.max(apparentReceivedAge, SECONDS.toMillis(response.getAgeSeconds()))
        : apparentReceivedAge;
    long responseDuration = response.getReceivedResponseMillis() - response.getSentRequestMillis();
    long residentDuration = nowMillis - response.getReceivedResponseMillis();
    return receivedAge + responseDuration + residentDuration;
  }

  /**
   * Returns the number of milliseconds that the response was fresh for,
   * starting from the served date.
   */
  private static long computeFreshnessLifetime(Response response) {
    CacheControl responseCaching = response.cacheControl();
    if (responseCaching.maxAgeSeconds() != -1) {
      return SECONDS.toMillis(responseCaching.maxAgeSeconds());
    } else if (response.getExpires() != null) {
      long servedMillis = response.getServedDate() != null
          ? response.getServedDate().getTime()
          : response.getReceivedResponseMillis();
      long delta = response.getExpires().getTime() - servedMillis;
      return delta > 0 ? delta : 0;
    } else if (response.getLastModified() != null && response.request().url().getQuery() == null) {
      // As recommended by the HTTP RFC and implemented in Firefox, the
      // max age of a document should be defaulted to 10% of the
      // document's age at the time it was served. Default expiration
      // dates aren't used for URIs containing a query.
      long servedMillis = response.getServedDate() != null
          ? response.getServedDate().getTime()
          : response.getSentRequestMillis();
      long delta = servedMillis - response.getLastModified().getTime();
      return delta > 0 ? (delta / 10) : 0;
    }
    return 0;
  }

  /**
   * Returns true if computeFreshnessLifetime used a heuristic. If we used a
   * heuristic to serve a cached response older than 24 hours, we are required
   * to attach a warning.
   */
  private static boolean isFreshnessLifetimeHeuristic(Response response) {
    return response.cacheControl().maxAgeSeconds() == -1
        && response.getExpires() == null;
  }

  /**
   * Returns true if this response can be stored to later serve another
   * request.
   */
  public static boolean isCacheable(Response response, Request request) {
    // Always go to network for uncacheable response codes (RFC 2616, 13.4),
    // This implementation doesn't support caching partial content.
    int responseCode = response.code();
    if (responseCode != HttpURLConnection.HTTP_OK
        && responseCode != HttpURLConnection.HTTP_NOT_AUTHORITATIVE
        && responseCode != HttpURLConnection.HTTP_MULT_CHOICE
        && responseCode != HttpURLConnection.HTTP_MOVED_PERM
        && responseCode != HttpURLConnection.HTTP_GONE) {
      return false;
    }

    // Responses to authorized requests aren't cacheable unless they include
    // a 'public', 'must-revalidate' or 's-maxage' directive.
    CacheControl responseCaching = response.cacheControl();
    if (request.header("Authorization") != null
        && !responseCaching.isPublic()
        && !responseCaching.mustRevalidate()
        && responseCaching.sMaxAgeSeconds() == -1) {
      return false;
    }

    if (responseCaching.noStore()) {
      return false;
    }

    return true;
  }

  /**
   * Returns a strategy to satisfy {@code request} using the a cached response
   * {@code response}.
   */
  public static CacheStrategy get(long nowMillis, Response response, Request request) {
    CacheStrategy candidate = getCandidate(nowMillis, response, request);

    if (candidate.source != ResponseSource.CACHE && request.cacheControl().onlyIfCached()) {
      // We're forbidden from using the network, but the cache is insufficient.
      Response noneResponse = new Response.Builder()
          .request(candidate.request)
          .statusLine(GATEWAY_TIMEOUT_STATUS_LINE)
          .setResponseSource(ResponseSource.NONE)
          .body(EMPTY_BODY)
          .build();
      return new CacheStrategy(candidate.request, noneResponse, ResponseSource.NONE);
    }

    return candidate;
  }

  /** Returns a strategy to use assuming the request can use the network. */
  private static CacheStrategy getCandidate(long nowMillis, Response response, Request request) {
    // No cached response.
    if (response == null) {
      return new CacheStrategy(request, response, ResponseSource.NETWORK);
    }

    // Drop the cached response if it's missing a required handshake.
    if (request.isHttps() && response.handshake() == null) {
      return new CacheStrategy(request, response, ResponseSource.NETWORK);
    }

    // If this response shouldn't have been stored, it should never be used
    // as a response source. This check should be redundant as long as the
    // persistence store is well-behaved and the rules are constant.
    if (!isCacheable(response, request)) {
      return new CacheStrategy(request, response, ResponseSource.NETWORK);
    }

    CacheControl requestCaching = request.cacheControl();
    if (requestCaching.noCache() || hasConditions(request)) {
      return new CacheStrategy(request, response, ResponseSource.NETWORK);
    }

    long ageMillis = computeAge(response, nowMillis);
    long freshMillis = computeFreshnessLifetime(response);

    if (requestCaching.maxAgeSeconds() != -1) {
      freshMillis = Math.min(freshMillis, SECONDS.toMillis(requestCaching.maxAgeSeconds()));
    }

    long minFreshMillis = 0;
    if (requestCaching.minFreshSeconds() != -1) {
      minFreshMillis = SECONDS.toMillis(requestCaching.minFreshSeconds());
    }

    long maxStaleMillis = 0;
    CacheControl responseCaching = response.cacheControl();
    if (!responseCaching.mustRevalidate() && requestCaching.maxStaleSeconds() != -1) {
      maxStaleMillis = SECONDS.toMillis(requestCaching.maxStaleSeconds());
    }

    if (!responseCaching.noCache() && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
      Response.Builder builder = response.newBuilder()
          .setResponseSource(ResponseSource.CACHE); // Overwrite any stored response source.
      if (ageMillis + minFreshMillis >= freshMillis) {
        builder.addHeader("Warning", "110 HttpURLConnection \"Response is stale\"");
      }
      long oneDayMillis = 24 * 60 * 60 * 1000L;
      if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic(response)) {
        builder.addHeader("Warning", "113 HttpURLConnection \"Heuristic expiration\"");
      }
      return new CacheStrategy(request, builder.build(), ResponseSource.CACHE);
    }

    Request.Builder conditionalRequestBuilder = request.newBuilder();

    if (response.getLastModified() != null) {
      conditionalRequestBuilder.setIfModifiedSince(response.getLastModified());
    } else if (response.getServedDate() != null) {
      conditionalRequestBuilder.setIfModifiedSince(response.getServedDate());
    }

    if (response.getEtag() != null) {
      conditionalRequestBuilder.setIfNoneMatch(response.getEtag());
    }

    Request conditionalRequest = conditionalRequestBuilder.build();
    ResponseSource responseSource = hasConditions(conditionalRequest)
        ? ResponseSource.CONDITIONAL_CACHE
        : ResponseSource.NETWORK;
    return new CacheStrategy(conditionalRequest, response, responseSource);
  }

  /**
   * Returns true if the request contains conditions that save the server from
   * sending a response that the client has locally. When a request is enqueued
   * with its own conditions, the built-in response cache won't be used.
   */
  private static boolean hasConditions(Request request) {
    return request.header("If-Modified-Since") != null || request.header("If-None-Match") != null;
  }
}
