/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.squareup.okhttp.internal.http;

import com.squareup.okhttp.ResponseSource;
import com.squareup.okhttp.internal.Platform;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import static com.squareup.okhttp.internal.Util.equal;

/** Parsed HTTP response headers. */
public final class ResponseHeaders {

  /** HTTP header name for the local time when the request was sent. */
  private static final String SENT_MILLIS = Platform.get().getPrefix() + "-Sent-Millis";

  /** HTTP header name for the local time when the response was received. */
  private static final String RECEIVED_MILLIS = Platform.get().getPrefix() + "-Received-Millis";

  /** HTTP synthetic header with the response source. */
  static final String RESPONSE_SOURCE = Platform.get().getPrefix() + "-Response-Source";

  /** HTTP synthetic header with the selected transport (spdy/3, http/1.1, etc). */
  static final String SELECTED_TRANSPORT = Platform.get().getPrefix() + "-Selected-Transport";

  private final URI uri;
  private final RawHeaders headers;

  /** The server's time when this response was served, if known. */
  private Date servedDate;

  /** The last modified date of the response, if known. */
  private Date lastModified;

  /**
   * The expiration date of the response, if known. If both this field and the
   * max age are set, the max age is preferred.
   */
  private Date expires;

  /**
   * Extension header set by HttpURLConnectionImpl specifying the timestamp
   * when the HTTP request was first initiated.
   */
  private long sentRequestMillis;

  /**
   * Extension header set by HttpURLConnectionImpl specifying the timestamp
   * when the HTTP response was first received.
   */
  private long receivedResponseMillis;

  /**
   * In the response, this field's name "no-cache" is misleading. It doesn't
   * prevent us from caching the response; it only means we have to validate
   * the response with the origin server before returning it. We can do this
   * with a conditional get.
   */
  private boolean noCache;

  /** If true, this response should not be cached. */
  private boolean noStore;

  /**
   * The duration past the response's served date that it can be served
   * without validation.
   */
  private int maxAgeSeconds = -1;

  /**
   * The "s-maxage" directive is the max age for shared caches. Not to be
   * confused with "max-age" for non-shared caches, As in Firefox and Chrome,
   * this directive is not honored by this cache.
   */
  private int sMaxAgeSeconds = -1;

  /**
   * This request header field's name "only-if-cached" is misleading. It
   * actually means "do not use the network". It is set by a client who only
   * wants to make a request if it can be fully satisfied by the cache.
   * Cached responses that would require validation (ie. conditional gets) are
   * not permitted if this header is set.
   */
  private boolean isPublic;
  private boolean mustRevalidate;
  private String etag;
  private int ageSeconds = -1;

  /** Case-insensitive set of field names. */
  private Set<String> varyFields = Collections.emptySet();

  private String contentEncoding;
  private String transferEncoding;
  private long contentLength = -1;
  private String connection;
  private String contentType;

  public ResponseHeaders(URI uri, RawHeaders headers) {
    this.uri = uri;
    this.headers = headers;

    HeaderParser.CacheControlHandler handler = new HeaderParser.CacheControlHandler() {
      @Override public void handle(String directive, String parameter) {
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
        }
      }
    };

    for (int i = 0; i < headers.length(); i++) {
      String fieldName = headers.getFieldName(i);
      String value = headers.getValue(i);
      if ("Cache-Control".equalsIgnoreCase(fieldName)) {
        HeaderParser.parseCacheControl(value, handler);
      } else if ("Date".equalsIgnoreCase(fieldName)) {
        servedDate = HttpDate.parse(value);
      } else if ("Expires".equalsIgnoreCase(fieldName)) {
        expires = HttpDate.parse(value);
      } else if ("Last-Modified".equalsIgnoreCase(fieldName)) {
        lastModified = HttpDate.parse(value);
      } else if ("ETag".equalsIgnoreCase(fieldName)) {
        etag = value;
      } else if ("Pragma".equalsIgnoreCase(fieldName)) {
        if ("no-cache".equalsIgnoreCase(value)) {
          noCache = true;
        }
      } else if ("Age".equalsIgnoreCase(fieldName)) {
        ageSeconds = HeaderParser.parseSeconds(value);
      } else if ("Vary".equalsIgnoreCase(fieldName)) {
        // Replace the immutable empty set with something we can mutate.
        if (varyFields.isEmpty()) {
          varyFields = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        }
        for (String varyField : value.split(",")) {
          varyFields.add(varyField.trim());
        }
      } else if ("Content-Encoding".equalsIgnoreCase(fieldName)) {
        contentEncoding = value;
      } else if ("Transfer-Encoding".equalsIgnoreCase(fieldName)) {
        transferEncoding = value;
      } else if ("Content-Length".equalsIgnoreCase(fieldName)) {
        try {
          contentLength = Long.parseLong(value);
        } catch (NumberFormatException ignored) {
        }
      } else if ("Content-Type".equalsIgnoreCase(fieldName)) {
        contentType = value;
      } else if ("Connection".equalsIgnoreCase(fieldName)) {
        connection = value;
      } else if (SENT_MILLIS.equalsIgnoreCase(fieldName)) {
        sentRequestMillis = Long.parseLong(value);
      } else if (RECEIVED_MILLIS.equalsIgnoreCase(fieldName)) {
        receivedResponseMillis = Long.parseLong(value);
      }
    }
  }

  public boolean isContentEncodingGzip() {
    return "gzip".equalsIgnoreCase(contentEncoding);
  }

  public void stripContentEncoding() {
    contentEncoding = null;
    headers.removeAll("Content-Encoding");
  }

  public void stripContentLength() {
    contentLength = -1;
    headers.removeAll("Content-Length");
  }

  public boolean isChunked() {
    return "chunked".equalsIgnoreCase(transferEncoding);
  }

  public boolean hasConnectionClose() {
    return "close".equalsIgnoreCase(connection);
  }

  public URI getUri() {
    return uri;
  }

  public RawHeaders getHeaders() {
    return headers;
  }

  public Date getServedDate() {
    return servedDate;
  }

  public Date getLastModified() {
    return lastModified;
  }

  public Date getExpires() {
    return expires;
  }

  public boolean isNoCache() {
    return noCache;
  }

  public boolean isNoStore() {
    return noStore;
  }

  public int getMaxAgeSeconds() {
    return maxAgeSeconds;
  }

  public int getSMaxAgeSeconds() {
    return sMaxAgeSeconds;
  }

  public boolean isPublic() {
    return isPublic;
  }

  public boolean isMustRevalidate() {
    return mustRevalidate;
  }

  public String getEtag() {
    return etag;
  }

  public Set<String> getVaryFields() {
    return varyFields;
  }

  public String getContentEncoding() {
    return contentEncoding;
  }

  public long getContentLength() {
    return contentLength;
  }

  public String getContentType() {
    return contentType;
  }

  public String getConnection() {
    return connection;
  }

  public void setLocalTimestamps(long sentRequestMillis, long receivedResponseMillis) {
    this.sentRequestMillis = sentRequestMillis;
    headers.add(SENT_MILLIS, Long.toString(sentRequestMillis));
    this.receivedResponseMillis = receivedResponseMillis;
    headers.add(RECEIVED_MILLIS, Long.toString(receivedResponseMillis));
  }

  public void setResponseSource(ResponseSource responseSource) {
    headers.set(RESPONSE_SOURCE, responseSource.toString() + " " + headers.getResponseCode());
  }

  public void setTransport(String transport) {
    headers.set(SELECTED_TRANSPORT, transport);
  }

  /**
   * Returns the current age of the response, in milliseconds. The calculation
   * is specified by RFC 2616, 13.2.3 Age Calculations.
   */
  private long computeAge(long nowMillis) {
    long apparentReceivedAge =
        servedDate != null ? Math.max(0, receivedResponseMillis - servedDate.getTime()) : 0;
    long receivedAge =
        ageSeconds != -1 ? Math.max(apparentReceivedAge, TimeUnit.SECONDS.toMillis(ageSeconds))
            : apparentReceivedAge;
    long responseDuration = receivedResponseMillis - sentRequestMillis;
    long residentDuration = nowMillis - receivedResponseMillis;
    return receivedAge + responseDuration + residentDuration;
  }

  /**
   * Returns the number of milliseconds that the response was fresh for,
   * starting from the served date.
   */
  private long computeFreshnessLifetime() {
    if (maxAgeSeconds != -1) {
      return TimeUnit.SECONDS.toMillis(maxAgeSeconds);
    } else if (expires != null) {
      long servedMillis = servedDate != null ? servedDate.getTime() : receivedResponseMillis;
      long delta = expires.getTime() - servedMillis;
      return delta > 0 ? delta : 0;
    } else if (lastModified != null && uri.getRawQuery() == null) {
      // As recommended by the HTTP RFC and implemented in Firefox, the
      // max age of a document should be defaulted to 10% of the
      // document's age at the time it was served. Default expiration
      // dates aren't used for URIs containing a query.
      long servedMillis = servedDate != null ? servedDate.getTime() : sentRequestMillis;
      long delta = servedMillis - lastModified.getTime();
      return delta > 0 ? (delta / 10) : 0;
    }
    return 0;
  }

  /**
   * Returns true if computeFreshnessLifetime used a heuristic. If we used a
   * heuristic to serve a cached response older than 24 hours, we are required
   * to attach a warning.
   */
  private boolean isFreshnessLifetimeHeuristic() {
    return maxAgeSeconds == -1 && expires == null;
  }

  /**
   * Returns true if this response can be stored to later serve another
   * request.
   */
  public boolean isCacheable(RequestHeaders request) {
    // Always go to network for uncacheable response codes (RFC 2616, 13.4),
    // This implementation doesn't support caching partial content.
    int responseCode = headers.getResponseCode();
    if (responseCode != HttpURLConnection.HTTP_OK
        && responseCode != HttpURLConnection.HTTP_NOT_AUTHORITATIVE
        && responseCode != HttpURLConnection.HTTP_MULT_CHOICE
        && responseCode != HttpURLConnection.HTTP_MOVED_PERM
        && responseCode != HttpURLConnection.HTTP_GONE) {
      return false;
    }

    // Responses to authorized requests aren't cacheable unless they include
    // a 'public', 'must-revalidate' or 's-maxage' directive.
    if (request.hasAuthorization() && !isPublic && !mustRevalidate && sMaxAgeSeconds == -1) {
      return false;
    }

    if (noStore) {
      return false;
    }

    return true;
  }

  /**
   * Returns true if a Vary header contains an asterisk. Such responses cannot
   * be cached.
   */
  public boolean hasVaryAll() {
    return varyFields.contains("*");
  }

  /**
   * Returns true if none of the Vary headers on this response have changed
   * between {@code cachedRequest} and {@code newRequest}.
   */
  public boolean varyMatches(Map<String, List<String>> cachedRequest,
      Map<String, List<String>> newRequest) {
    for (String field : varyFields) {
      if (!equal(cachedRequest.get(field), newRequest.get(field))) {
        return false;
      }
    }
    return true;
  }

  /** Returns the source to satisfy {@code request} given this cached response. */
  public ResponseSource chooseResponseSource(long nowMillis, RequestHeaders request) {
    // If this response shouldn't have been stored, it should never be used
    // as a response source. This check should be redundant as long as the
    // persistence store is well-behaved and the rules are constant.
    if (!isCacheable(request)) {
      return ResponseSource.NETWORK;
    }

    if (request.isNoCache() || request.hasConditions()) {
      return ResponseSource.NETWORK;
    }

    long ageMillis = computeAge(nowMillis);
    long freshMillis = computeFreshnessLifetime();

    if (request.getMaxAgeSeconds() != -1) {
      freshMillis = Math.min(freshMillis, TimeUnit.SECONDS.toMillis(request.getMaxAgeSeconds()));
    }

    long minFreshMillis = 0;
    if (request.getMinFreshSeconds() != -1) {
      minFreshMillis = TimeUnit.SECONDS.toMillis(request.getMinFreshSeconds());
    }

    long maxStaleMillis = 0;
    if (!mustRevalidate && request.getMaxStaleSeconds() != -1) {
      maxStaleMillis = TimeUnit.SECONDS.toMillis(request.getMaxStaleSeconds());
    }

    if (!noCache && ageMillis + minFreshMillis < freshMillis + maxStaleMillis) {
      if (ageMillis + minFreshMillis >= freshMillis) {
        headers.add("Warning", "110 HttpURLConnection \"Response is stale\"");
      }
      long oneDayMillis = 24 * 60 * 60 * 1000L;
      if (ageMillis > oneDayMillis && isFreshnessLifetimeHeuristic()) {
        headers.add("Warning", "113 HttpURLConnection \"Heuristic expiration\"");
      }
      return ResponseSource.CACHE;
    }

    if (lastModified != null) {
      request.setIfModifiedSince(lastModified);
    } else if (servedDate != null) {
      request.setIfModifiedSince(servedDate);
    }

    if (etag != null) {
      request.setIfNoneMatch(etag);
    }

    return request.hasConditions() ? ResponseSource.CONDITIONAL_CACHE : ResponseSource.NETWORK;
  }

  /**
   * Returns true if this cached response should be used; false if the
   * network response should be used.
   */
  public boolean validate(ResponseHeaders networkResponse) {
    if (networkResponse.headers.getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED) {
      return true;
    }

    // The HTTP spec says that if the network's response is older than our
    // cached response, we may return the cache's response. Like Chrome (but
    // unlike Firefox), this client prefers to return the newer response.
    if (lastModified != null
        && networkResponse.lastModified != null
        && networkResponse.lastModified.getTime() < lastModified.getTime()) {
      return true;
    }

    return false;
  }

  /**
   * Combines this cached header with a network header as defined by RFC 2616,
   * 13.5.3.
   */
  public ResponseHeaders combine(ResponseHeaders network) throws IOException {
    RawHeaders result = new RawHeaders();
    result.setStatusLine(headers.getStatusLine());

    for (int i = 0; i < headers.length(); i++) {
      String fieldName = headers.getFieldName(i);
      String value = headers.getValue(i);
      if ("Warning".equals(fieldName) && value.startsWith("1")) {
        continue; // drop 100-level freshness warnings
      }
      if (!isEndToEnd(fieldName) || network.headers.get(fieldName) == null) {
        result.add(fieldName, value);
      }
    }

    for (int i = 0; i < network.headers.length(); i++) {
      String fieldName = network.headers.getFieldName(i);
      if (isEndToEnd(fieldName)) {
        result.add(fieldName, network.headers.getValue(i));
      }
    }

    return new ResponseHeaders(uri, result);
  }

  /**
   * Returns true if {@code fieldName} is an end-to-end HTTP header, as
   * defined by RFC 2616, 13.5.1.
   */
  private static boolean isEndToEnd(String fieldName) {
    return !"Connection".equalsIgnoreCase(fieldName)
        && !"Keep-Alive".equalsIgnoreCase(fieldName)
        && !"Proxy-Authenticate".equalsIgnoreCase(fieldName)
        && !"Proxy-Authorization".equalsIgnoreCase(fieldName)
        && !"TE".equalsIgnoreCase(fieldName)
        && !"Trailers".equalsIgnoreCase(fieldName)
        && !"Transfer-Encoding".equalsIgnoreCase(fieldName)
        && !"Upgrade".equalsIgnoreCase(fieldName);
  }
}
