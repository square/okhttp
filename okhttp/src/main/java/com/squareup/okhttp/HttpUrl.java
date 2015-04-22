/*
 * Copyright (C) 2015 Square, Inc.
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Set;

/**
 * A <a href="https://url.spec.whatwg.org/">URL</a> with an {@code http} or {@code https} scheme.
 *
 * TODO: discussion on canonicalization
 *
 * TODO: discussion on encoding-by-parts
 *
 * TODO: discussion on this vs. java.net.URL vs. java.net.URI
 */
public final class HttpUrl {
  private HttpUrl(Builder builder) {
    throw new UnsupportedOperationException();
  }

  public URL url() {
    throw new UnsupportedOperationException();
  }

  public URI uri() throws IOException {
    throw new UnsupportedOperationException();
  }

  /** Returns either "http" or "https". */
  public String scheme() {
    throw new UnsupportedOperationException();
  }

  public boolean isHttps() {
    return scheme().equals("https");
  }

  public String user() {
    throw new UnsupportedOperationException();
  }

  public String encodedUser() {
    throw new UnsupportedOperationException();
  }

  public String password() {
    throw new UnsupportedOperationException();
  }

  public String encodedPassword() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the decoded (potentially non-ASCII) hostname. The returned string may contain non-ASCII
   * characters and is <strong>not suitable</strong> for DNS lookups; for that use {@link
   * #encodedHost}. For example, this may return {@code ☃.net} which is a user-displayable IDN that
   * cannot be used for DNS lookups without encoding.
   */
  public String host() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the host address suitable for use with {@link InetAddress#getAllByName(String)}. May
   * be a regular host name ({@code android.com}), an IPv4 address ({@code 127.0.0.1}), an IPv6
   * address ({@code ::1}; note that there are no square braces), or an encoded IDN ({@code
   * xn--n3h.net}).
   */
  public String encodedHost() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the explicitly-specified port if one was provided, or the default port for this URL's
   * scheme. For example, this returns 8443 for {@code https://square.com:8443/} and 443 for {@code
   * https://square.com/}.
   */
  public int port() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns 80 if {@code scheme.equals("http")}, 443 if {@code scheme.equals("https")} and -1
   * otherwise.
   */
  public static int defaultPort(String scheme) {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the entire path of this URL, encoded for use in HTTP resource resolution. The
   * returned path is always nonempty and is prefixed with {@code /}.
   */
  public String encodedPath() {
    throw new UnsupportedOperationException();
  }

  public List<String> pathSegments() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the query of this URL, encoded for use in HTTP resource resolution. The returned string
   * may be null (for URLs with no query), empty (for URLs with an empty query) or non-empty (all
   * other URLs).
   */
  public String encodedQuery() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the first query parameter named {@code name} decoded using UTF-8, or null if there is
   * no such query parameter.
   */
  public String queryParameter(String name) {
    throw new UnsupportedOperationException();
  }

  public Set<String> queryParameterNames() {
    throw new UnsupportedOperationException();
  }

  public List<String> queryParameterValues(String name) {
    throw new UnsupportedOperationException();
  }

  public String queryParameterName(int index) {
    throw new UnsupportedOperationException();
  }

  public String queryParameterValue(int index) {
    throw new UnsupportedOperationException();
  }

  public String fragment() {
    throw new UnsupportedOperationException();
  }

  /**
   * Returns the URL that would be retrieved by following {@code link} from this URL.
   *
   * TODO: explain better.
   */
  public HttpUrl resolve(String link) {
    throw new UnsupportedOperationException();
  }

  public Builder newBuilder() {
    return new Builder(this);
  }

  /**
   * Returns a new {@code OkUrl} representing {@code url} if it is a well-formed HTTP or HTTPS URL,
   * or null if it isn't.
   */
  public static HttpUrl parse(String url) {
    throw new UnsupportedOperationException();
  }

  public static HttpUrl get(URL url) {
    throw new UnsupportedOperationException();
  }

  public static HttpUrl get(URI uri) {
    throw new UnsupportedOperationException();
  }

  @Override public boolean equals(Object o) {
    throw new UnsupportedOperationException();
  }

  @Override public int hashCode() {
    throw new UnsupportedOperationException();
  }

  @Override public String toString() {
    throw new UnsupportedOperationException();
  }

  public static final class Builder {
    public Builder() {
    }

    private Builder(HttpUrl url) {
    }

    public Builder scheme(String scheme) {
      throw new UnsupportedOperationException();
    }

    public Builder user(String user) {
      throw new UnsupportedOperationException();
    }

    public Builder encodedUser(String encodedUser) {
      throw new UnsupportedOperationException();
    }

    public Builder password(String password) {
      throw new UnsupportedOperationException();
    }

    public Builder encodedPassword(String encodedPassword) {
      throw new UnsupportedOperationException();
    }

    /**
     * @param host either a regular hostname, International Domain Name, IPv4 address, or IPv6
     *     address.
     */
    public Builder host(String host) {
      throw new UnsupportedOperationException();
    }

    public Builder port(int port) {
      throw new UnsupportedOperationException();
    }

    public Builder addPathSegment(String pathSegment) {
      if (pathSegment == null) throw new IllegalArgumentException("pathSegment == null");
      throw new UnsupportedOperationException();
    }

    public Builder addEncodedPathSegment(String encodedPathSegment) {
      if (encodedPathSegment == null) {
        throw new IllegalArgumentException("encodedPathSegment == null");
      }
      throw new UnsupportedOperationException();
    }

    public Builder encodedPath(String encodedPath) {
      throw new UnsupportedOperationException();
    }

    public Builder encodedQuery(String encodedQuery) {
      throw new UnsupportedOperationException();
    }

    /** Encodes the query parameter using UTF-8 and adds it to this URL's query string. */
    public Builder addQueryParameter(String name, String value) {
      if (name == null) throw new IllegalArgumentException("name == null");
      if (value == null) throw new IllegalArgumentException("value == null");
      throw new UnsupportedOperationException();
    }

    /** Adds the pre-encoded query parameter to this URL's query string. */
    public Builder addEncodedQueryParameter(String encodedName, String encodedValue) {
      if (encodedName == null) throw new IllegalArgumentException("encodedName == null");
      if (encodedValue == null) throw new IllegalArgumentException("encodedValue == null");
      throw new UnsupportedOperationException();
    }

    public Builder setQueryParameter(String name, String value) {
      if (name == null) throw new IllegalArgumentException("name == null");
      if (value == null) throw new IllegalArgumentException("value == null");
      throw new UnsupportedOperationException();
    }

    public Builder setEncodedQueryParameter(String encodedName, String encodedValue) {
      if (encodedName == null) throw new IllegalArgumentException("encodedName == null");
      if (encodedValue == null) throw new IllegalArgumentException("encodedValue == null");
      throw new UnsupportedOperationException();
    }

    public Builder removeAllQueryParameters(String name) {
      if (name == null) throw new IllegalArgumentException("name == null");
      throw new UnsupportedOperationException();
    }

    public Builder removeAllEncodedQueryParameters(String encodedName) {
      if (encodedName == null) throw new IllegalArgumentException("encodedName == null");
      throw new UnsupportedOperationException();
    }

    public Builder fragment(String fragment) {
      throw new UnsupportedOperationException();
    }

    public HttpUrl build() {
      throw new UnsupportedOperationException();
    }
  }
}
