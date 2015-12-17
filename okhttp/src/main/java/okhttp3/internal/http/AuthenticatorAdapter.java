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
package okhttp3.internal.http;

import okhttp3.Authenticator;
import okhttp3.Challenge;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;
import java.io.IOException;
import java.net.Authenticator.RequestorType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.util.List;

/** Adapts {@link java.net.Authenticator} to {@link Authenticator}. */
public final class AuthenticatorAdapter implements Authenticator {
  /** Uses the global authenticator to get the password. */
  public static final Authenticator INSTANCE = new AuthenticatorAdapter();

  @Override public Request authenticate(Proxy proxy, Response response) throws IOException {
    List<Challenge> challenges = response.challenges();
    Request request = response.request();
    HttpUrl url = request.url();
    for (int i = 0, size = challenges.size(); i < size; i++) {
      Challenge challenge = challenges.get(i);
      if (!"Basic".equalsIgnoreCase(challenge.scheme())) continue;

      PasswordAuthentication auth = java.net.Authenticator.requestPasswordAuthentication(
          url.host(), getConnectToInetAddress(proxy, url), url.port(), url.scheme(),
          challenge.realm(), challenge.scheme(), url.url(), RequestorType.SERVER);
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
    HttpUrl url = request.url();
    for (int i = 0, size = challenges.size(); i < size; i++) {
      Challenge challenge = challenges.get(i);
      if (!"Basic".equalsIgnoreCase(challenge.scheme())) continue;

      InetSocketAddress proxyAddress = (InetSocketAddress) proxy.address();
      PasswordAuthentication auth = java.net.Authenticator.requestPasswordAuthentication(
          proxyAddress.getHostName(), getConnectToInetAddress(proxy, url), proxyAddress.getPort(),
          url.scheme(), challenge.realm(), challenge.scheme(), url.url(),
          RequestorType.PROXY);
      if (auth == null) continue;

      String credential = Credentials.basic(auth.getUserName(), new String(auth.getPassword()));
      return request.newBuilder()
          .header("Proxy-Authorization", credential)
          .build();
    }
    return null;
  }

  private InetAddress getConnectToInetAddress(Proxy proxy, HttpUrl url) throws IOException {
    return (proxy != null && proxy.type() != Proxy.Type.DIRECT)
        ? ((InetSocketAddress) proxy.address()).getAddress()
        : InetAddress.getByName(url.host());
  }
}
