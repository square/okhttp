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

import com.squareup.okhttp.internal.Util;
import com.squareup.okhttp.internal.http.RawHeaders;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Set;

import static com.squareup.okhttp.internal.Util.UTF_8;

/**
 * An HTTP response. Instances of this class are not immutable: the response
 * body is a one-shot value that may be consumed only once. All other properties
 * are immutable.
 *
 * <h3>Warning: Experimental OkHttp 2.0 API</h3>
 * This class is in beta. APIs are subject to change!
 */
/* OkHttp 2.0: public */ final class Response {
  private final Request request;
  private final int code;
  private final RawHeaders headers;
  private final Body body;
  private final Response redirectedBy;

  private Response(Builder builder) {
    this.request = builder.request;
    this.code = builder.code;
    this.headers = new RawHeaders(builder.headers);
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

  public int code() {
    return code;
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

  RawHeaders rawHeaders() {
    return new RawHeaders(headers);
  }

  public String headerValue(int index) {
    return headers.getValue(index);
  }

  public Body body() {
    return body;
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

  public abstract static class Body {
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

    public abstract InputStream byteStream() throws IOException;

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
    public final Reader charStream() throws IOException {
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
    private final int code;
    private RawHeaders headers = new RawHeaders();
    private Body body;
    private Response redirectedBy;

    public Builder(Request request, int code) {
      if (request == null) throw new IllegalArgumentException("request == null");
      if (code <= 0) throw new IllegalArgumentException("code <= 0");
      this.request = request;
      this.code = code;
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

    Builder rawHeaders(RawHeaders rawHeaders) {
      headers = new RawHeaders(rawHeaders);
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

    public Response build() {
      if (request == null) throw new IllegalStateException("Response has no request.");
      if (code == -1) throw new IllegalStateException("Response has no code.");
      return new Response(this);
    }
  }
}
