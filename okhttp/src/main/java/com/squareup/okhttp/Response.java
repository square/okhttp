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

import com.squareup.okhttp.internal.http.RawHeaders;
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
    throw new UnsupportedOperationException("TODO");
  }

  public Set<String> headerNames() {
    throw new UnsupportedOperationException("TODO");
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

  public abstract class Body {
    public String contentType() {
      return null;
    }

    public long contentLength() {
      return -1;
    }

    public InputStream byteStream() throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    public byte[] bytes() throws IOException {
      throw new UnsupportedOperationException("TODO");
    }

    /**
     * Returns the response bytes as a UTF-8 character stream. Do not call this
     * method if the response content is not a UTF-8 character stream.
     */
    public Reader charStream() throws IOException {
      return new InputStreamReader(byteStream(), "UTF-8");
    }

    /**
     * Returns the response bytes as a UTF-8 string. Do not call this method if
     * the response content is not a UTF-8 character stream.
     */
    public String string() throws IOException {
      return new String(bytes(), "UTF-8");
    }
  }

  public interface Receiver {
    void receive(Response response) throws IOException;
  }

  public static class Builder {
    private Request request;
    private int code;
    private RawHeaders headers = new RawHeaders();
    private Body body;
    private Response redirectedBy;

    public Builder request(Request request) {
      this.request = request;
      return this;
    }

    public Builder code(int code) {
      this.code = code;
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

    public Builder body(Body body) {
      this.body = body;
      return this;
    }

    public Builder redirectedBy(Response redirectedBy) {
      this.redirectedBy = redirectedBy;
      return this;
    }
  }
}
