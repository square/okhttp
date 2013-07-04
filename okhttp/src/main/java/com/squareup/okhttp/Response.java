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
import java.util.List;
import java.util.Set;

/**
 * An HTTP response. Instances of this class are not immutable: the response
 * body is a one-shot value that may be consumed only once. All other properties
 * are immutable.
 *
 * <h3>Warning: Experimental OkHttp 2.0 API</h3>
 * This class is in beta. APIs are subject to change!
 */
public final class Response {
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
    public String contentType() {
      return null;
    }

    public long contentLength() {
      return -1;
    }

    public abstract InputStream byteStream() throws IOException;

    public byte[] bytes() throws IOException {
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
     * Returns the response bytes as a UTF-8 character stream. Do not call this
     * method if the response content is not a UTF-8 character stream.
     */
    public Reader charStream() throws IOException {
      // TODO: parse content-type.
      return new InputStreamReader(byteStream(), "UTF-8");
    }

    /**
     * Returns the response bytes as a UTF-8 string. Do not call this method if
     * the response content is not a UTF-8 character stream.
     */
    public String string() throws IOException {
      // TODO: parse content-type.
      return new String(bytes(), "UTF-8");
    }
  }

  public interface Receiver {
    void onFailure(Failure failure);
    void onResponse(Response response) throws IOException;
  }

  public static class Builder {
    private final Request request;
    private final int code;
    private final RawHeaders headers = new RawHeaders();
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
