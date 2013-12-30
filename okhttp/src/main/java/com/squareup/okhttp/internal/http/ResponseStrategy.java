package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import com.squareup.okhttp.ResponseSource;
import java.net.HttpURLConnection;
import java.util.concurrent.TimeUnit;

/**
 * Given a request and cached response, this figures out the next action. It
 * may also update the request to add conditions, or the response to add
 * warnings.
 */
public final class ResponseStrategy {
  public final Request request;
  public final Response response;
  public final ResponseSource source;

  private ResponseStrategy(
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
        ? Math.max(apparentReceivedAge, TimeUnit.SECONDS.toMillis(response.getAgeSeconds()))
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
    if (response.getMaxAgeSeconds() != -1) {
      return TimeUnit.SECONDS.toMillis(response.getMaxAgeSeconds());
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
    return response.getMaxAgeSeconds() == -1 && response.getExpires() == null;
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
    if (request.hasAuthorization()
        && !response.isPublic()
        && !response.isMustRevalidate()
        && response.getSMaxAgeSeconds() == -1) {
      return false;
    }

    if (response.isNoStore()) {
      return false;
    }

    return true;
  }

  /**
   * Returns a strategy to satisfy {@code request} using the a cached response
   * {@code response}.
   */
  public static ResponseStrategy get(
      long nowMillis, Response response, Request request) {
    // If this response shouldn't have been stored, it should never be used
    // as a response source. This check should be redundant as long as the
    // persistence store is well-behaved and the rules are constant.
    if (!isCacheable(response, request)) {
      return new ResponseStrategy(request, response, ResponseSource.NETWORK);
    }

    if (request.isNoCache() || request.hasConditions()) {
      return new ResponseStrategy(request, response, ResponseSource.NETWORK);
    }

    long ageMillis = computeAge(response, nowMillis);
    long freshMillis = computeFreshnessLifetime(response);

    if (request.getMaxAgeSeconds() != -1) {
      freshMillis = Math.min(freshMillis, TimeUnit.SECONDS.toMillis(request.getMaxAgeSeconds()));
    }

    long minFreshMillis = 0;
    if (request.getMinFreshSeconds() != -1) {
      minFreshMillis = TimeUnit.SECONDS.toMillis(request.getMinFreshSeconds());
    }

    long maxStaleMillis = 0;
    if (!response.isMustRevalidate() && request.getMaxStaleSeconds() != -1) {
      maxStaleMillis = TimeUnit.SECONDS.toMillis(request.getMaxStaleSeconds());
    }

    if (!response.isNoCache() && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
      Response.Builder builder = response.newBuilder();
      if (ageMillis + minFreshMillis >= freshMillis) {
        builder.addWarning("110 HttpURLConnection \"Response is stale\"");
      }
      long oneDayMillis = 24 * 60 * 60 * 1000L;
      if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic(response)) {
        builder.addWarning("113 HttpURLConnection \"Heuristic expiration\"");
      }
      return new ResponseStrategy(request, builder.build(), ResponseSource.CACHE);
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
    ResponseSource responseSource = conditionalRequest.hasConditions()
        ? ResponseSource.CONDITIONAL_CACHE
        : ResponseSource.NETWORK;
    return new ResponseStrategy(conditionalRequest, response, responseSource);
  }
}
