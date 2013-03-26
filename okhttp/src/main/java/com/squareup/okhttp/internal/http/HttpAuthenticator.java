/*
 * Copyright (C) 2012 Square, Inc.
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

import com.squareup.okhttp.internal.Base64;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;

/** Handles HTTP authentication headers from origin and proxy servers. */
public final class HttpAuthenticator {
  private HttpAuthenticator() {
  }

  /**
   * React to a failed authorization response by looking up new credentials.
   *
   * @return true if credentials have been added to successorRequestHeaders
   *         and another request should be attempted.
   */
  public static boolean processAuthHeader(int responseCode, RawHeaders responseHeaders,
      RawHeaders successorRequestHeaders, Proxy proxy, URL url) throws IOException {
    if (responseCode != HTTP_PROXY_AUTH && responseCode != HTTP_UNAUTHORIZED) {
      throw new IllegalArgumentException();
    }

    // Keep asking for username/password until authorized.
    String challengeHeader =
        responseCode == HTTP_PROXY_AUTH ? "Proxy-Authenticate" : "WWW-Authenticate";
    String credentials = getCredentials(responseHeaders, challengeHeader, proxy, url);
    if (credentials == null) {
      return false; // Could not find credentials so end the request cycle.
    }

    // Add authorization credentials, bypassing the already-connected check.
    String fieldName = responseCode == HTTP_PROXY_AUTH ? "Proxy-Authorization" : "Authorization";
    successorRequestHeaders.set(fieldName, credentials);
    return true;
  }

  /**
   * Returns the authorization credentials that may satisfy the challenge.
   * Returns null if a challenge header was not provided or if credentials
   * were not available.
   */
  private static String getCredentials(RawHeaders responseHeaders, String challengeHeader,
      Proxy proxy, URL url) throws IOException {
    List<Challenge> challenges = parseChallenges(responseHeaders, challengeHeader);
    if (challenges.isEmpty()) {
      return null;
    }

    for (Challenge challenge : challenges) {
      // Use the global authenticator to get the password.
      PasswordAuthentication auth;
      if (responseHeaders.getResponseCode() == HTTP_PROXY_AUTH) {
        InetSocketAddress proxyAddress = (InetSocketAddress) proxy.address();
        auth = Authenticator.requestPasswordAuthentication(proxyAddress.getHostName(),
            getConnectToInetAddress(proxy, url), proxyAddress.getPort(), url.getProtocol(),
            challenge.realm, challenge.scheme, url, Authenticator.RequestorType.PROXY);
      } else {
        auth = Authenticator.requestPasswordAuthentication(url.getHost(),
            getConnectToInetAddress(proxy, url), url.getPort(), url.getProtocol(), challenge.realm,
            challenge.scheme, url, Authenticator.RequestorType.SERVER);
      }
      if (auth == null) {
        continue;
      }

      // Use base64 to encode the username and password.
      String usernameAndPassword = auth.getUserName() + ":" + new String(auth.getPassword());
      byte[] bytes = usernameAndPassword.getBytes("ISO-8859-1");
      String encoded = Base64.encode(bytes);
      return challenge.scheme + " " + encoded;
    }

    return null;
  }

  private static InetAddress getConnectToInetAddress(Proxy proxy, URL url) throws IOException {
    return (proxy != null && proxy.type() != Proxy.Type.DIRECT)
        ? ((InetSocketAddress) proxy.address()).getAddress() : InetAddress.getByName(url.getHost());
  }

  /**
   * Parse RFC 2617 challenges. This API is only interested in the scheme
   * name and realm.
   */
  private static List<Challenge> parseChallenges(RawHeaders responseHeaders,
      String challengeHeader) {
    // auth-scheme = token
    // auth-param  = token "=" ( token | quoted-string )
    // challenge   = auth-scheme 1*SP 1#auth-param
    // realm       = "realm" "=" realm-value
    // realm-value = quoted-string
    List<Challenge> result = new ArrayList<Challenge>();
    for (int h = 0; h < responseHeaders.length(); h++) {
      if (!challengeHeader.equalsIgnoreCase(responseHeaders.getFieldName(h))) {
        continue;
      }
      String value = responseHeaders.getValue(h);
      int pos = 0;
      while (pos < value.length()) {
        int tokenStart = pos;
        pos = HeaderParser.skipUntil(value, pos, " ");

        String scheme = value.substring(tokenStart, pos).trim();
        pos = HeaderParser.skipWhitespace(value, pos);

        // TODO: This currently only handles schemes with a 'realm' parameter;
        //       It needs to be fixed to handle any scheme and any parameters
        //       http://code.google.com/p/android/issues/detail?id=11140

        if (!value.regionMatches(pos, "realm=\"", 0, "realm=\"".length())) {
          break; // Unexpected challenge parameter; give up!
        }

        pos += "realm=\"".length();
        int realmStart = pos;
        pos = HeaderParser.skipUntil(value, pos, "\"");
        String realm = value.substring(realmStart, pos);
        pos++; // Consume '"' close quote.
        pos = HeaderParser.skipUntil(value, pos, ",");
        pos++; // Consume ',' comma.
        pos = HeaderParser.skipWhitespace(value, pos);
        result.add(new Challenge(scheme, realm));
      }
    }
    return result;
  }

  /** An RFC 2617 challenge. */
  private static final class Challenge {
    final String scheme;
    final String realm;

    Challenge(String scheme, String realm) {
      this.scheme = scheme;
      this.realm = realm;
    }

    @Override public boolean equals(Object o) {
      return o instanceof Challenge
          && ((Challenge) o).scheme.equals(scheme)
          && ((Challenge) o).realm.equals(realm);
    }

    @Override public int hashCode() {
      return scheme.hashCode() + 31 * realm.hashCode();
    }
  }
}
