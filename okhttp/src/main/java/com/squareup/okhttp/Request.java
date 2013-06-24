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
import java.io.OutputStream;
import java.util.List;
import java.util.Set;

/**
 * An HTTP request. Instances of this class are immutable if their {@link #body}
 * is null or itself immutable.
 */
public final class Request {
  private final String url;
  private final String method;
  private final RawHeaders headers;
  private final Body body;
  private final Object tag;

  private Request(Builder builder) {
    this.url = builder.url;
    this.method = builder.method;
    this.headers = new RawHeaders(builder.headers);
    this.body = builder.body;
    this.tag = builder.tag;
  }

  public String url() {
    return url;
  }

  public String method() {
    return method;
  }

  public String header(String name) {
    return headers.get(name);
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

  public Object tag() {
    return tag;
  }

  public abstract class Body {
    public String contentType() {
      return null;
    }

    public long contentLength() {
      return -1;
    }

    public abstract void writeTo(OutputStream out) throws IOException;
  }

  public static class Builder {
    private String url;
    private String method;
    private RawHeaders headers = new RawHeaders();
    private Body body;
    private Object tag;

    public Builder url(String url) {
      this.url = url;
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
     * multiply-valued headers like "Cookie".
     */
    public Builder addHeader(String name, String value) {
      headers.add(name, value);
      return this;
    }

    public Builder get() {
      return method("GET", null);
    }

    public Builder head() {
      return method("HEAD", null);
    }

    public Builder post(Body body) {
      return method("POST", body);
    }

    public Builder put(Body body) {
      return method("PUT", body);
    }

    public Builder method(String method, Body body) {
      this.method = method;
      this.body = body;
      return this;
    }

    /**
     * Attaches {@code tag} to the request. It can be used later to cancel the
     * request.
     */
    public Builder tag(Object tag) {
      this.tag = tag;
      return this;
    }

    public Request build() {
      return new Request(this);
    }
  }
}
