/*
 * Copyright (C) 2013 Square, Inc.
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
package com.squareup.okhttp;

import com.squareup.okhttp.internal.Platform;
import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.HeaderParser;
import com.squareup.okhttp.internal.http.Headers;
import com.squareup.okhttp.internal.http.HttpDate;
import com.squareup.okhttp.internal.http.StatusLine;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.squareup.okhttp.internal.Util.UTF_8;
import static com.squareup.okhttp.internal.Util.equal;

/**
 * An HTTP response. Instances of this class are not immutable: the response
 * body is a one-shot value that may be consumed only once. All other properties
 * are immutable.
 *
 * <h3>Warning: Experimental OkHttp 2.0 API</h3>
 * This class is in beta. APIs are subject to change!
 */
public final class Response {
  /** HTTP header name for the local time when the request was sent. */
  private static final String SENT_MILLIS = Platform.get().getPrefix() + "-Sent-Millis";

  /** HTTP header name for the local time when the response was received. */
  private static final String RECEIVED_MILLIS = Platform.get().getPrefix() + "-Received-Millis";

  /** HTTP synthetic header with the response source. */
  // TODO: this shouldn't be public.
  public static final String RESPONSE_SOURCE = Platform.get().getPrefix() + "-Response-Source";

  /** HTTP synthetic header with the selected transport (spdy/3, http/1.1, etc). */
  // TODO: this shouldn't be public.
  public static final String SELECTED_TRANSPORT
      = Platform.get().getPrefix() + "-Selected-Transport";

  private final Request request;
  private final StatusLine statusLine;
  private final Handshake handshake;
  private final Headers headers;
  private final Body body;
  private final Response redirectedBy;

  private volatile ParsedHeaders parsedHeaders; // Lazily initialized.

  private Response(Builder builder) {
    this.request = builder.request;
    this.statusLine = builder.statusLine;
    this.handshake = builder.handshake;
    this.headers = builder.headers.build();
    this.body = builder.body;
    this.redirectedBy = builder.redirectedBy;
  }

  /**
   * The wire-level request that initiated this HTTP response. This is usually
   * <strong>not</strong> the same request instance provided to the HTTP client:
   * <ul>
   *     <li>It may be transformed by the HTTP client. For example, the client
   *         may have added its own {@code Content-Encoding} header to enable
   *         response compression.
   *     <li>It may be the request generated in response to an HTTP redirect.
   *         In this case the request URL may be different than the initial
   *         request URL.
   * </ul>
   */
  public Request request() {
    return request;
  }

  public String statusLine() {
    return statusLine.getStatusLine();
  }

  public int code() {
    return statusLine.code();
  }

  public String statusMessage() {
    return statusLine.message();
  }

  public int httpMinorVersion() {
    return statusLine.httpMinorVersion();
  }

  /**
   * Returns the TLS handshake of the connection that carried this response, or
   * null if the response was received without TLS.
   */
  public Handshake handshake() {
    return handshake;
  }

  public String header(String name) {
    return header(name, null);
  }

  public String header(String name, String defaultValue) {
    String result = headers.get(name);
    return result != null ? result : defaultValue;
  }

  public List<String> headers(String name) {
    return headers.values(name);
  }

  public Set<String> headerNames() {
    return headers.names();
  }

  public int headerCount() {
    return headers.length();
  }

  public String headerName(int index) {
    return headers.getFieldName(index);
  }

  // TODO: this shouldn't be public.
  public Headers headers() {
    return headers;
  }

  public String headerValue(int index) {
    return headers.getValue(index);
  }

  public Body body() {
    return body;
  }

  public Builder newBuilder() {
    return new Builder(request)
        .statusLine(statusLine)
        .handshake(handshake)
        .headers(headers)
        .body(body)
        .redirectedBy(redirectedBy);
  }

  /**
   * Returns the response for the HTTP redirect that triggered this response, or
   * null if this response wasn't triggered by an automatic redirect. The body
   * of the returned response should not be read because it has already been
   * consumed by the redirecting client.
   */
  public Response redirectedBy() {
    return redirectedBy;
  }

  public boolean isContentEncodingGzip() {
    return "gzip".equalsIgnoreCase(parsedHeaders().contentEncoding);
  }

  public boolean isChunked() {
    return "chunked".equalsIgnoreCase(parsedHeaders().transferEncoding);
  }

  public boolean hasConnectionClose() {
    return "close".equalsIgnoreCase(parsedHeaders().connection);
  }

  public Date getServedDate() {
    return parsedHeaders().servedDate;
  }

  public Date getLastModified() {
    return parsedHeaders().lastModified;
  }

  public Date getExpires() {
    return parsedHeaders().expires;
  }

  public boolean isNoCache() {
    return parsedHeaders().noCache;
  }

  public boolean isNoStore() {
    return parsedHeaders().noStore;
  }

  public int getMaxAgeSeconds() {
    return parsedHeaders().maxAgeSeconds;
  }

  public int getSMaxAgeSeconds() {
    return parsedHeaders().sMaxAgeSeconds;
  }

  public boolean isPublic() {
    return parsedHeaders().isPublic;
  }

  public boolean isMustRevalidate() {
    return parsedHeaders().mustRevalidate;
  }

  public String getEtag() {
    return parsedHeaders().etag;
  }

  public Set<String> getVaryFields() {
    return parsedHeaders().varyFields;
  }

  public String getContentEncoding() {
    return parsedHeaders().contentEncoding;
  }

  // TODO: this shouldn't be public.
  public long getContentLength() {
    return parsedHeaders().contentLength;
  }

  // TODO: this shouldn't be public.
  public String getContentType() {
    return parsedHeaders().contentType;
  }

  public String getConnection() {
    return parsedHeaders().connection;
  }

  /**
   * Returns true if a Vary header contains an asterisk. Such responses cannot
   * be cached.
   */
  public boolean hasVaryAll() {
    return parsedHeaders().varyFields.contains("*");
  }

  /**
   * Returns true if none of the Vary headers on this response have changed
   * between {@code cachedRequest} and {@code newRequest}.
   */
  public boolean varyMatches(Headers varyHeaders, Request newRequest) {
    for (String field : parsedHeaders().varyFields) {
      if (!equal(varyHeaders.values(field), newRequest.headers(field))) return false;
    }
    return true;
  }

  /**
   * Returns true if this cached response should be used; false if the
   * network response should be used.
   */
  public boolean validate(Response network) {
    if (network.code() == HttpURLConnection.HTTP_NOT_MODIFIED) {
      return true;
    }

    // The HTTP spec says that if the network's response is older than our
    // cached response, we may return the cache's response. Like Chrome (but
    // unlike Firefox), this client prefers to return the newer response.
    ParsedHeaders networkHeaders = network.parsedHeaders();
    if (parsedHeaders().lastModified != null
        && networkHeaders.lastModified != null
        && networkHeaders.lastModified.getTime() < parsedHeaders().lastModified.getTime()) {
      return true;
    }

    return false;
  }

  /**
   * Combines this cached header with a network header as defined by RFC 2616,
   * 13.5.3.
   */
  public Response combine(Response network) throws IOException {
    Headers.Builder result = new Headers.Builder();

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

    return newBuilder().headers(result.build()).build();
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

  public long getReceivedResponseMillis() {
    return parsedHeaders().receivedResponseMillis;
  }

  public long getAgeSeconds() {
    return parsedHeaders().ageSeconds;
  }

  public long getSentRequestMillis() {
    return parsedHeaders.sentRequestMillis;
  }

  public abstract static class Body implements Closeable {
    /** Multiple calls to {@link #charStream()} must return the same instance. */
    private Reader reader;

    /**
     * Returns true if further data from this response body should be read at
     * this time. For asynchronous transports like SPDY and HTTP/2.0, this will
     * return false once all locally-available body bytes have been read.
     *
     * <p>Clients with many concurrent downloads can use this method to reduce
     * the number of idle threads blocking on reads. See {@link
     * Receiver#onResponse} for details.
     */
    // <h3>Body.ready() vs. InputStream.available()</h3>
    // TODO: Can we fix response bodies to implement InputStream.available well?
    // The deflater implementation is broken by default but we could do better.
    public abstract boolean ready() throws IOException;

    public abstract MediaType contentType();

    /**
     * Returns the number of bytes in that will returned by {@link #bytes}, or
     * {@link #byteStream}, or -1 if unknown.
     */
    public abstract long contentLength();

    public abstract InputStream byteStream();

    public final byte[] bytes() throws IOException {
      long contentLength = contentLength();
      if (contentLength > Integer.MAX_VALUE) {
        throw new IOException("Cannot buffer entire body for content length: " + contentLength);
      }

      if (contentLength != -1) {
        byte[] content = new byte[(int) contentLength];
        InputStream in = byteStream();
        Util.readFully(in, content);
        if (in.read() != -1) throw new IOException("Content-Length and stream length disagree");
        return content;

      } else {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Util.copy(byteStream(), out);
        return out.toByteArray();
      }
    }

    /**
     * Returns the response as a character stream decoded with the charset
     * of the Content-Type header. If that header is either absent or lacks a
     * charset, this will attempt to decode the response body as UTF-8.
     */
    public final Reader charStream() {
      if (reader == null) {
        reader = new InputStreamReader(byteStream(), charset());
      }
      return reader;
    }

    /**
     * Returns the response as a string decoded with the charset of the
     * Content-Type header. If that header is either absent or lacks a charset,
     * this will attempt to decode the response body as UTF-8.
     */
    public final String string() throws IOException {
      return new String(bytes(), charset().name());
    }

    private Charset charset() {
      MediaType contentType = contentType();
      return contentType != null ? contentType.charset(UTF_8) : UTF_8;
    }

    @Override public void close() throws IOException {
      byteStream().close();
    }
  }

  private ParsedHeaders parsedHeaders() {
    ParsedHeaders result = parsedHeaders;
    return result != null ? result : (parsedHeaders = new ParsedHeaders(headers));
  }

  /** Parsed response headers, computed on-demand and cached. */
  private static class ParsedHeaders {
    /** The server's time when this response was served, if known. */
    Date servedDate;

    /** The last modified date of the response, if known. */
    Date lastModified;

    /**
     * The expiration date of the response, if known. If both this field and the
     * max age are set, the max age is preferred.
     */
    Date expires;

    /**
     * Extension header set by HttpURLConnectionImpl specifying the timestamp
     * when the HTTP request was first initiated.
     */
    long sentRequestMillis;

    /**
     * Extension header set by HttpURLConnectionImpl specifying the timestamp
     * when the HTTP response was first received.
     */
    long receivedResponseMillis;

    /**
     * In the response, this field's name "no-cache" is misleading. It doesn't
     * prevent us from caching the response; it only means we have to validate
     * the response with the origin server before returning it. We can do this
     * with a conditional get.
     */
    boolean noCache;

    /** If true, this response should not be cached. */
    boolean noStore;

    /**
     * The duration past the response's served date that it can be served
     * without validation.
     */
    int maxAgeSeconds = -1;

    /**
     * The "s-maxage" directive is the max age for shared caches. Not to be
     * confused with "max-age" for non-shared caches, As in Firefox and Chrome,
     * this directive is not honored by this cache.
     */
    int sMaxAgeSeconds = -1;

    /**
     * This request header field's name "only-if-cached" is misleading. It
     * actually means "do not use the network". It is set by a client who only
     * wants to make a request if it can be fully satisfied by the cache.
     * Cached responses that would require validation (ie. conditional gets) are
     * not permitted if this header is set.
     */
    boolean isPublic;
    boolean mustRevalidate;
    String etag;
    int ageSeconds = -1;

    /** Case-insensitive set of field names. */
    private Set<String> varyFields = Collections.emptySet();

    private String contentEncoding;
    private String transferEncoding;
    private long contentLength = -1;
    private String connection;
    private String contentType;

    private ParsedHeaders(Headers headers) {
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
  }

  public interface Receiver {
    /**
     * Called when the request could not be executed due to a connectivity
     * problem or timeout. Because networks can fail during an exchange, it is
     * possible that the remote server accepted the request before the failure.
     */
    void onFailure(Failure failure);

    /**
     * Called when the HTTP response was successfully returned by the remote
     * server. The receiver may proceed to read the response body with the
     * response's {@link #body} method.
     *
     * <p>Note that transport-layer success (receiving a HTTP response code,
     * headers and body) does not necessarily indicate application-layer
     * success: {@code response} may still indicate an unhappy HTTP response
     * code like 404 or 500.
     *
     * <h3>Non-blocking responses</h3>
     *
     * <p>Receivers do not need to block while waiting for the response body to
     * download. Instead, they can get called back as data arrives. Use {@link
     * Body#ready} to check if bytes should be read immediately. While there is
     * data ready, read it. If there isn't, return false: receivers will be
     * called back with {@code onResponse()} as additional data is downloaded.
     *
     * <p>Return true to indicate that the receiver has finished handling the
     * response body. If the response body has unread data, it will be
     * discarded.
     *
     * <p>When the response body has been fully consumed the returned value is
     * undefined.
     *
     * <p>The current implementation of {@link Body#ready} always returns true
     * when the underlying transport is HTTP/1. This results in blocking on that
     * transport. For effective non-blocking your server must support SPDY or
     * HTTP/2.
     */
    boolean onResponse(Response response) throws IOException;
  }

  public static class Builder {
    private final Request request;
    private StatusLine statusLine;
    private Handshake handshake;
    private Headers.Builder headers = new Headers.Builder();
    private Body body;
    private Response redirectedBy;

    public Builder(Request request) {
      if (request == null) throw new IllegalArgumentException("request == null");
      this.request = request;
    }

    public Builder statusLine(StatusLine statusLine) {
      if (statusLine == null) throw new IllegalArgumentException("statusLine == null");
      this.statusLine = statusLine;
      return this;
    }

    public Builder statusLine(String statusLine) {
      try {
        return statusLine(new StatusLine(statusLine));
      } catch (IOException e) {
        throw new IllegalArgumentException(e);
      }
    }

    public Builder handshake(Handshake handshake) {
      this.handshake = handshake;
      return this;
    }

    /**
     * Sets the header named {@code name} to {@code value}. If this request
     * already has any headers with that name, they are all replaced.
     */
    public Builder header(String name, String value) {
      headers.set(name, value);
      return this;
    }

    /**
     * Adds a header with {@code name} and {@code value}. Prefer this method for
     * multiply-valued headers like "Set-Cookie".
     */
    public Builder addHeader(String name, String value) {
      headers.add(name, value);
      return this;
    }

    // TODO: this shouldn't be public.
    public Builder headers(Headers headers) {
      this.headers = headers.newBuilder();
      return this;
    }

    public Builder body(Body body) {
      this.body = body;
      return this;
    }

    public Builder redirectedBy(Response redirectedBy) {
      this.redirectedBy = redirectedBy;
      return this;
    }

    // TODO: this shouldn't be public.
    public Builder stripContentEncoding() {
      headers.removeAll("Content-Encoding");
      return this;
    }

    // TODO: this shouldn't be public.
    public Builder stripContentLength() {
      headers.removeAll("Content-Length");
      return this;
    }

    // TODO: this shouldn't be public.
    public Builder setLocalTimestamps(long sentRequestMillis, long receivedResponseMillis) {
      headers.set(SENT_MILLIS, Long.toString(sentRequestMillis));
      headers.set(RECEIVED_MILLIS, Long.toString(receivedResponseMillis));
      return this;
    }

    // TODO: this shouldn't be public.
    public Builder setResponseSource(ResponseSource responseSource) {
      headers.set(RESPONSE_SOURCE, responseSource.toString() + " " + statusLine.code());
      return this;
    }

    // TODO: this shouldn't be public.
    public Builder addWarning(String message) {
      headers.add("Warning", message);
      return this;
    }

    public Response build() {
      if (request == null) throw new IllegalStateException("request == null");
      if (statusLine == null) throw new IllegalStateException("statusLine == null");
      return new Response(this);
    }
  }
}
