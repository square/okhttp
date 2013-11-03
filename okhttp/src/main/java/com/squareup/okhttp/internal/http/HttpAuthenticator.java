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

import com.squareup.okhttp.OkAuthenticator;
import com.squareup.okhttp.OkAuthenticator.Challenge;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import static com.squareup.okhttp.OkAuthenticator.Credential;
import static java.net.HttpURLConnection.HTTP_PROXY_AUTH;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;

/** Handles HTTP authentication headers from origin and proxy servers. */
public final class HttpAuthenticator {
  /** Uses the global authenticator to get the password. */
  public static final OkAuthenticator SYSTEM_DEFAULT = new OkAuthenticator() {
    @Override public Credential authenticate(
        Proxy proxy, URL url, List<Challenge> challenges) throws IOException {
      for (Challenge challenge : challenges) {
        if (!"Basic".equalsIgnoreCase(challenge.getScheme())) {
          continue;
        }

        PasswordAuthentication auth = Authenticator.requestPasswordAuthentication(url.getHost(),
            getConnectToInetAddress(proxy, url), url.getPort(), url.getProtocol(),
            challenge.getRealm(), challenge.getScheme(), url, Authenticator.RequestorType.SERVER);
        if (auth != null) {
          return Credential.basic(auth.getUserName(), new String(auth.getPassword()));
        }
      }
      return null;
    }

    @Override public Credential authenticateProxy(
        Proxy proxy, URL url, List<Challenge> challenges) throws IOException {
      for (Challenge challenge : challenges) {
        if (!"Basic".equalsIgnoreCase(challenge.getScheme())) {
          continue;
        }

        InetSocketAddress proxyAddress = (InetSocketAddress) proxy.address();
        PasswordAuthentication auth = Authenticator.requestPasswordAuthentication(
            proxyAddress.getHostName(), getConnectToInetAddress(proxy, url), proxyAddress.getPort(),
            url.getProtocol(), challenge.getRealm(), challenge.getScheme(), url,
            Authenticator.RequestorType.PROXY);
        if (auth != null) {
          return Credential.basic(auth.getUserName(), new String(auth.getPassword()));
        }
      }
      return null;
    }

    private InetAddress getConnectToInetAddress(Proxy proxy, URL url) throws IOException {
      return (proxy != null && proxy.type() != Proxy.Type.DIRECT)
          ? ((InetSocketAddress) proxy.address()).getAddress()
          : InetAddress.getByName(url.getHost());
    }
  };

  private HttpAuthenticator() {
  }

  /**
   * React to a failed authorization response by looking up new credentials.
   *
   * @return true if credentials have been added to successorRequestHeaders
   *         and another request should be attempted.
   */
  public static boolean processAuthHeader(OkAuthenticator authenticator, int responseCode,
      RawHeaders responseHeaders, RawHeaders successorRequestHeaders, Proxy proxy, URL url)
      throws IOException {
    String responseField;
    String requestField;
    if (responseCode == HTTP_UNAUTHORIZED) {
      responseField = "WWW-Authenticate";
      requestField = "Authorization";
    } else if (responseCode == HTTP_PROXY_AUTH) {
      responseField = "Proxy-Authenticate";
      requestField = "Proxy-Authorization";
    } else {
      throw new IllegalArgumentException(); // TODO: ProtocolException?
    }
    List<Challenge> challenges = parseChallenges(responseHeaders, responseField);
    if (challenges.isEmpty()) {
      return false; // Could not find a challenge so end the request cycle.
    }
    Credential credential = responseHeaders.getResponseCode() == HTTP_PROXY_AUTH
        ? authenticator.authenticateProxy(proxy, url, challenges)
        : authenticator.authenticate(proxy, url, challenges);
    if (credential == null) {
      return false; // Could not satisfy the challenge so end the request cycle.
    }
    // Add authorization credentials, bypassing the already-connected check.
    successorRequestHeaders.set(requestField, credential.getHeaderValue());
    return true;
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

        if (!value.regionMatches(true, pos, "realm=\"", 0, "realm=\"".length())) {
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
}
