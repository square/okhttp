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
package okhttp3;

import java.io.IOException;
import java.net.Authenticator.RequestorType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.util.List;

/**
 * Adapts {@link java.net.Authenticator} to {@link Authenticator}. Configure OkHttp to use {@link
 * java.net.Authenticator} with {@link OkHttpClient.Builder#authenticator} or {@link
 * OkHttpClient.Builder#proxyAuthenticator(Authenticator)}.
 */
public final class JavaNetAuthenticator implements Authenticator {
  @Override public Request authenticate(Route route, Response response) throws IOException {
    List<Challenge> challenges = response.challenges();
    Request request = response.request();
    HttpUrl url = request.url();
    boolean proxyAuthorization = response.code() == 407;
    Proxy proxy = route.proxy();

    for (int i = 0, size = challenges.size(); i < size; i++) {
      Challenge challenge = challenges.get(i);
      if (!"Basic".equalsIgnoreCase(challenge.scheme())) continue;

      PasswordAuthentication auth;
      if (proxyAuthorization) {
        InetSocketAddress proxyAddress = (InetSocketAddress) proxy.address();
        auth = java.net.Authenticator.requestPasswordAuthentication(
            proxyAddress.getHostName(), getConnectToInetAddress(proxy, url), proxyAddress.getPort(),
            url.scheme(), challenge.realm(), challenge.scheme(), url.url(),
            RequestorType.PROXY);
      } else {
        auth = java.net.Authenticator.requestPasswordAuthentication(
            url.host(), getConnectToInetAddress(proxy, url), url.port(), url.scheme(),
            challenge.realm(), challenge.scheme(), url.url(), RequestorType.SERVER);
      }

      if (auth != null) {
        String credential = Credentials.basic(auth.getUserName(), new String(auth.getPassword()));
        return request.newBuilder()
            .header(proxyAuthorization ? "Proxy-Authorization" : "Authorization", credential)
            .build();
      }
    }

    return null; // No challenges were satisfied!
  }

  private InetAddress getConnectToInetAddress(Proxy proxy, HttpUrl url) throws IOException {
    return (proxy != null && proxy.type() != Proxy.Type.DIRECT)
        ? ((InetSocketAddress) proxy.address()).getAddress()
        : InetAddress.getByName(url.host());
  }

  @Override
  public Request authenticatePreemptive(Route route, Request request) throws IOException {
    return null;
  }

  @Override
  public boolean isPreemptive() {
    return false;
  }
}
