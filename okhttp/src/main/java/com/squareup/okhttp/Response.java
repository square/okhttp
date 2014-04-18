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
import com.squareup.okhttp.internal.http.OkHeaders;
import com.squareup.okhttp.internal.http.StatusLine;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.List;
import okio.BufferedSource;

import static com.squareup.okhttp.internal.Util.UTF_8;

/**
 * An HTTP response. Instances of this class are not immutable: the response
 * body is a one-shot value that may be consumed only once. All other properties
 * are immutable.
 */
public final class Response {
  private final Request request;
  private final StatusLine statusLine;
  private final Handshake handshake;
  private final Headers headers;
  private final Body body;
  private final Response redirectedBy;

  private volatile CacheControl cacheControl; // Lazily initialized.

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

  public List<String> headers(String name) {
    return headers.values(name);
  }

  public String header(String name) {
    return header(name, null);
  }

  public String header(String name, String defaultValue) {
    String result = headers.get(name);
    return result != null ? result : defaultValue;
  }

  public Headers headers() {
    return headers;
  }

  public Body body() {
    return body;
  }

  public Builder newBuilder() {
    return new Builder(this);
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

  public abstract static class Body implements Closeable {
    /** Multiple calls to {@link #charStream()} must return the same instance. */
    private Reader reader;

    public abstract MediaType contentType();

    /**
     * Returns the number of bytes in that will returned by {@link #bytes}, or
     * {@link #byteStream}, or -1 if unknown.
     */
    public abstract long contentLength();

    public final InputStream byteStream() {
      return source().inputStream();
    }

    public abstract BufferedSource source();

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
      Reader r = reader;
      return r != null ? r : (reader = new InputStreamReader(byteStream(), charset()));
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
      source().close();
    }
  }

  /**
   * Returns the cache control directives for this response. This is never null,
   * even if this response contains no {@code Cache-Control} header.
   */
  public CacheControl cacheControl() {
    CacheControl result = cacheControl;
    return result != null ? result : (cacheControl = CacheControl.parse(headers));
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
     */
    void onResponse(Response response) throws IOException;
  }

  public static class Builder {
    private Request request;
    private StatusLine statusLine;
    private Handshake handshake;
    private Headers.Builder headers;
    private Body body;
    private Response redirectedBy;

    public Builder() {
      headers = new Headers.Builder();
    }

    private Builder(Response response) {
      this.request = response.request;
      this.statusLine = response.statusLine;
      this.handshake = response.handshake;
      this.headers = response.headers.newBuilder();
      this.body = response.body;
      this.redirectedBy = response.redirectedBy;
    }

    public Builder request(Request request) {
      this.request = request;
      return this;
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

    public Builder removeHeader(String name) {
      headers.removeAll(name);
      return this;
    }

    /** Removes all headers on this builder and adds {@code headers}. */
    public Builder headers(Headers headers) {
      this.headers = headers.newBuilder();
      return this;
    }

    public Builder body(Body body) {
      this.body = body;
      return this;
    }

    // TODO: move out of public API
    public Builder setResponseSource(ResponseSource responseSource) {
      return header(OkHeaders.RESPONSE_SOURCE, responseSource + " " + statusLine.code());
    }

    public Builder redirectedBy(Response redirectedBy) {
      this.redirectedBy = redirectedBy;
      return this;
    }

    public Response build() {
      if (request == null) throw new IllegalStateException("request == null");
      if (statusLine == null) throw new IllegalStateException("statusLine == null");
      return new Response(this);
    }
  }
}
