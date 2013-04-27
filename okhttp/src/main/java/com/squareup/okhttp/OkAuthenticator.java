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

import com.squareup.okhttp.internal.Base64;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Proxy;
import java.net.URL;
import java.util.List;

/**
 * Responds to authentication challenges from the remote web or proxy server by
 * returning credentials.
 */
public interface OkAuthenticator {
  /**
   * Returns a credential that satisfies the authentication challenge made by
   * {@code url}. Returns null if the challenge cannot be satisfied. This method
   * is called in response to an HTTP 401 unauthorized status code sent by the
   * origin server.
   *
   * @param challenges parsed "WWW-Authenticate" challenge headers from the HTTP
   *     response.
   */
  Credential authenticate(Proxy proxy, URL url, List<Challenge> challenges) throws IOException;

  /**
   * Returns a credential that satisfies the authentication challenge made by
   * {@code proxy}. Returns null if the challenge cannot be satisfied. This
   * method is called in response to an HTTP 401 unauthorized status code sent
   * by the proxy server.
   *
   * @param challenges parsed "Proxy-Authenticate" challenge headers from the
   *     HTTP response.
   */
  Credential authenticateProxy(Proxy proxy, URL url, List<Challenge> challenges) throws IOException;

  /** An RFC 2617 challenge. */
  public final class Challenge {
    private final String scheme;
    private final String realm;

    public Challenge(String scheme, String realm) {
      this.scheme = scheme;
      this.realm = realm;
    }

    /** Returns the authentication scheme, like {@code Basic}. */
    public String getScheme() {
      return scheme;
    }

    /** Returns the protection space. */
    public String getRealm() {
      return realm;
    }

    @Override public boolean equals(Object o) {
      return o instanceof Challenge
          && ((Challenge) o).scheme.equals(scheme)
          && ((Challenge) o).realm.equals(realm);
    }

    @Override public int hashCode() {
      return scheme.hashCode() + 31 * realm.hashCode();
    }

    @Override public String toString() {
      return scheme + " realm=\"" + realm + "\"";
    }
  }

  /** An RFC 2617 credential. */
  public final class Credential {
    private final String headerValue;

    private Credential(String headerValue) {
      this.headerValue = headerValue;
    }

    /** Returns an auth credential for the Basic scheme. */
    public static Credential basic(String userName, String password) {
      try {
        String usernameAndPassword = userName + ":" + password;
        byte[] bytes = usernameAndPassword.getBytes("ISO-8859-1");
        String encoded = Base64.encode(bytes);
        return new Credential("Basic " + encoded);
      } catch (UnsupportedEncodingException e) {
        throw new AssertionError();
      }
    }

    public String getHeaderValue() {
      return headerValue;
    }

    @Override public boolean equals(Object o) {
      return o instanceof Credential && ((Credential) o).headerValue.equals(headerValue);
    }

    @Override public int hashCode() {
      return headerValue.hashCode();
    }

    @Override public String toString() {
      return headerValue;
    }
  }
}
