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
package com.squareup.okhttp.internal.huc;

import com.squareup.okhttp.Challenge;
import com.squareup.okhttp.Credentials;
import com.squareup.okhttp.OkAuthenticator;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import java.io.IOException;
import java.net.Authenticator;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.util.List;

/** Adapts {@link Authenticator} to {@link OkAuthenticator}. */
public final class AuthenticatorAdapter implements OkAuthenticator {
  /** Uses the global authenticator to get the password. */
  public static final OkAuthenticator INSTANCE = new AuthenticatorAdapter();

  @Override public Request authenticate(Proxy proxy, Response response) throws IOException {
    List<Challenge> challenges = response.challenges();
    Request request = response.request();
    URL url = request.url();
    for (int i = 0, size = challenges.size(); i < size; i++) {
      Challenge challenge = challenges.get(i);
      if (!"Basic".equalsIgnoreCase(challenge.getScheme())) continue;

      PasswordAuthentication auth = Authenticator.requestPasswordAuthentication(url.getHost(),
          getConnectToInetAddress(proxy, url), url.getPort(), url.getProtocol(),
          challenge.getRealm(), challenge.getScheme(), url, Authenticator.RequestorType.SERVER);
      if (auth == null) continue;

      String credential = Credentials.basic(auth.getUserName(), new String(auth.getPassword()));
      return request.newBuilder()
          .header("Authorization", credential)
          .build();
    }
    return null;

  }

  @Override public Request authenticateProxy(Proxy proxy, Response response) throws IOException {
    List<Challenge> challenges = response.challenges();
    Request request = response.request();
    URL url = request.url();
    for (int i = 0, size = challenges.size(); i < size; i++) {
      Challenge challenge = challenges.get(i);
      if (!"Basic".equalsIgnoreCase(challenge.getScheme())) continue;

      InetSocketAddress proxyAddress = (InetSocketAddress) proxy.address();
      PasswordAuthentication auth = Authenticator.requestPasswordAuthentication(
          proxyAddress.getHostName(), getConnectToInetAddress(proxy, url), proxyAddress.getPort(),
          url.getProtocol(), challenge.getRealm(), challenge.getScheme(), url,
          Authenticator.RequestorType.PROXY);
      if (auth == null) continue;

      String credential = Credentials.basic(auth.getUserName(), new String(auth.getPassword()));
      return request.newBuilder()
          .header("Proxy-Authorization", credential)
          .build();
    }
    return null;
  }

  private InetAddress getConnectToInetAddress(Proxy proxy, URL url) throws IOException {
    return (proxy != null && proxy.type() != Proxy.Type.DIRECT)
        ? ((InetSocketAddress) proxy.address()).getAddress()
        : InetAddress.getByName(url.getHost());
  }
}
