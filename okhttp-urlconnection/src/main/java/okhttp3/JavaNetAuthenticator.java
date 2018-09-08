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

import java.net.Authenticator.RequestorType;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;

import static java.net.Authenticator.RequestorType.PROXY;
import static java.net.Authenticator.RequestorType.SERVER;
import static java.net.Authenticator.requestPasswordAuthentication;
import static okhttp3.internal.Util.ISO_8859_1;
import static okhttp3.internal.Util.UTF_8;

/**
 * Adapts {@link java.net.Authenticator} to {@link Authenticator} for {@code Basic} auth.
 * Configure OkHttp to use {@link java.net.Authenticator} with
 * {@link OkHttpClient.Builder#authenticator} or
 * {@link OkHttpClient.Builder#proxyAuthenticator(Authenticator)}.
 */
public final class JavaNetAuthenticator implements Authenticator {
  @Override
  public Request authenticate(Route route, Response response) throws UnknownHostException {
    Request request = response.request();
    HttpUrl requestUrl = request.url();
    Proxy proxy = route.proxy();
    String host;
    InetAddress addr;
    int port;
    String protocol = requestUrl.scheme();
    URL url = requestUrl.url();
    RequestorType requestorType;
    String authorizationHeaderName;

    if (response.code() == 407) {
      InetSocketAddress proxyAddress = (InetSocketAddress) proxy.address();
      host = proxyAddress.getHostName();
      addr = proxyAddress.getAddress();
      port = proxyAddress.getPort();
      requestorType = PROXY;
      authorizationHeaderName = "Proxy-Authorization";
    } else {
      host = requestUrl.host();
      addr = InetAddress.getByName(host);
      port = requestUrl.port();
      requestorType = SERVER;
      authorizationHeaderName = "Authorization";
    }

challengeLoop:
    for (Challenge challenge : response.challenges()) {
      // only basic auth is supported
      String scheme = challenge.scheme();
      if (!"basic".equals(scheme)) continue;

      // for basic auth realm is mandatory
      String realm = challenge.realm();
      if (realm == null) continue;

      // for basic auth only the default ISO_8859_1 and an explicit UTF_8 charset are valid
      Charset charset = challenge.charset();
      if (!(ISO_8859_1.equals(charset) || UTF_8.equals(charset))) continue;

      PasswordAuthentication auth = requestPasswordAuthentication(
              host, addr, port, protocol, realm, scheme, url, requestorType);

      // no credentials for this challenge, try next if more challenges are left
      if (auth == null) continue;

      String credentials = Credentials.basic(
              auth.getUserName(), String.valueOf(auth.getPassword()), charset);

      for (String authorizationHeaderValue : request.headers(authorizationHeaderName)) {
        // previous request already had these credentials,
        // obviously they do not work, try next if more challenges are left
        if (credentials.equals(authorizationHeaderValue)) continue challengeLoop;
      }

      return request.newBuilder().header(authorizationHeaderName, credentials).build();
    }

    // no challenges could be satisfied
    return null;
  }
}
