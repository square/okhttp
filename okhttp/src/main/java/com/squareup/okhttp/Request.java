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
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.Set;

/**
 * An HTTP request. Instances of this class are immutable if their {@link #body}
 * is null or itself immutable.
 *
 * <h3>Warning: Experimental OkHttp 2.0 API</h3>
 * This class is in beta. APIs are subject to change!
 */
/* OkHttp 2.0: public */ final class Request {
  private final URL url;
  private final String method;
  private final RawHeaders headers;
  private final Body body;
  private final Object tag;

  private Request(Builder builder) {
    this.url = builder.url;
    this.method = builder.method;
    this.headers = new RawHeaders(builder.headers);
    this.body = builder.body;
    this.tag = builder.tag != null ? builder.tag : this;
  }

  public URL url() {
    return url;
  }

  public String urlString() {
    return url.toString();
  }

  public String method() {
    return method;
  }

  public String header(String name) {
    return headers.get(name);
  }

  public List<String> headers(String name) {
    return headers.values(name);
  }

  public Set<String> headerNames() {
    return headers.names();
  }

  RawHeaders rawHeaders() {
    return new RawHeaders(headers);
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

  public Object tag() {
    return tag;
  }

  Builder newBuilder() {
    return new Builder(url)
        .method(method, body)
        .rawHeaders(headers)
        .tag(tag);
  }

  public abstract static class Body {
    /** Returns the Content-Type header for this body. */
    public abstract MediaType contentType();

    /**
     * Returns the number of bytes that will be written to {@code out} in a call
     * to {@link #writeTo}, or -1 if that count is unknown.
     */
    public long contentLength() {
      return -1;
    }

    /** Writes the content of this request to {@code out}. */
    public abstract void writeTo(OutputStream out) throws IOException;

    /**
     * Returns a new request body that transmits {@code content}. If {@code
     * contentType} lacks a charset, this will use UTF-8.
     */
    public static Body create(MediaType contentType, String content) {
      contentType = contentType.charset() != null
          ? contentType
          : MediaType.parse(contentType + "; charset=utf-8");
      try {
        byte[] bytes = content.getBytes(contentType.charset().name());
        return create(contentType, bytes);
      } catch (UnsupportedEncodingException e) {
        throw new AssertionError();
      }
    }

    /** Returns a new request body that transmits {@code content}. */
    public static Body create(final MediaType contentType, final byte[] content) {
      if (contentType == null) throw new NullPointerException("contentType == null");
      if (content == null) throw new NullPointerException("content == null");

      return new Body() {
        @Override public MediaType contentType() {
          return contentType;
        }

        @Override public long contentLength() {
          return content.length;
        }

        @Override public void writeTo(OutputStream out) throws IOException {
          out.write(content);
        }
      };
    }

    /** Returns a new request body that transmits the content of {@code file}. */
    public static Body create(final MediaType contentType, final File file) {
      if (contentType == null) throw new NullPointerException("contentType == null");
      if (file == null) throw new NullPointerException("content == null");

      return new Body() {
        @Override public MediaType contentType() {
          return contentType;
        }

        @Override public long contentLength() {
          return file.length();
        }

        @Override public void writeTo(OutputStream out) throws IOException {
          long length = contentLength();
          if (length == 0) return;

          InputStream in = null;
          try {
            in = new FileInputStream(file);
            byte[] buffer = new byte[(int) Math.min(8192, length)];
            for (int c; (c = in.read(buffer)) != -1; ) {
              out.write(buffer, 0, c);
            }
          } finally {
            Util.closeQuietly(in);
          }
        }
      };
    }
  }

  public static class Builder {
    private URL url;
    private String method = "GET";
    private RawHeaders headers = new RawHeaders();
    private Body body;
    private Object tag;

    public Builder(String url) {
      url(url);
    }

    public Builder(URL url) {
      url(url);
    }

    public Builder url(String url) {
      try {
        this.url = new URL(url);
        return this;
      } catch (MalformedURLException e) {
        throw new IllegalArgumentException("Malformed URL: " + url);
      }
    }

    public Builder url(URL url) {
      if (url == null) throw new IllegalStateException("url == null");
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

    Builder rawHeaders(RawHeaders rawHeaders) {
      headers = new RawHeaders(rawHeaders);
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
      if (method == null || method.length() == 0) {
        throw new IllegalArgumentException("method == null || method.length() == 0");
      }
      this.method = method;
      this.body = body;
      return this;
    }

    /**
     * Attaches {@code tag} to the request. It can be used later to cancel the
     * request. If the tag is unspecified or null, the request is canceled by
     * using the request itself as the tag.
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
